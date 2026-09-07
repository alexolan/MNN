#!/usr/bin/env python3
"""Build the fixed-name root-overlay incremental ZIP from a Git baseline."""

import argparse
import hashlib
import json
import os
import stat
import subprocess
import sys
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

ZIP_NAME = "mnn-3.6.1-local-rag-incremental.zip"
MANIFEST_NAME = "INCREMENTAL-MANIFEST.json"
DEFAULT_BASELINE = "d0a32f5e55b9c8c57ac077d51887c951922320c1"
BASELINE_ARCHIVE_SHA256 = "93d60848879e68be554c247afe376657630a30652a7d8ec713e9e7d1bbf69260"
EXCLUDED_PREFIXES = (".git/", ".gradle/", "build/", "local.properties")
EXCLUDED_SUFFIXES = (".apk", ".aab", ".jks", ".keystore", ".p12", ".pem", ".key")
EXCLUDED_FILES = (ZIP_NAME, MANIFEST_NAME, "BUILD-REPORT.md")
EXCLUDED_REPORT_PREFIXES = ("PHASE-",)


def should_exclude(raw: str) -> bool:
    normalized = raw.replace("\\", "/")
    name = PurePosixPath(normalized).name
    return normalized in EXCLUDED_FILES or name.startswith(EXCLUDED_REPORT_PREFIXES)



def run(repo: Path, *args: str, text: bool = True):
    return subprocess.check_output(args, cwd=repo, text=text, stderr=subprocess.DEVNULL)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_path(raw: str) -> str:
    normalized = raw.replace("\\", "/")
    path = PurePosixPath(normalized)
    if not normalized or path.is_absolute() or ".." in path.parts or "." in path.parts:
        raise ValueError(f"Unsafe incremental path: {raw!r}")
    if normalized.startswith(EXCLUDED_PREFIXES) or normalized.endswith(EXCLUDED_SUFFIXES):
        raise ValueError(f"Forbidden incremental artifact: {raw!r}")
    return path.as_posix()


def git_file(repo: Path, revision: str, path: str):
    try:
        return run(repo, "git", "show", f"{revision}:{path}", text=False)
    except subprocess.CalledProcessError:
        return None


def collect_changes(repo: Path, baseline: str, target: str):
    output = run(repo, "git", "diff", "--name-status", "--find-renames", f"{baseline}..{target}")
    files = []
    deleted = []
    for line in output.splitlines():
        parts = line.split("\t")
        status = parts[0]
        if status.startswith("R") or status.startswith("C"):
            old_path = validate_path(parts[1])
            new_path = validate_path(parts[2])
            if status.startswith("R"):
                deleted.append(old_path)
            files.append(new_path)
        elif status == "D":
            deleted.append(validate_path(parts[1]))
        elif status in {"A", "M", "T"}:
            files.append(validate_path(parts[1]))
        else:
            raise ValueError(f"Unsupported Git change status: {line}")
    files = [path for path in files if not should_exclude(path)]
    deleted = [path for path in deleted if not should_exclude(path)]
    return sorted(set(files)), sorted(set(deleted))


def file_record(repo: Path, baseline: str, path: str):
    source = repo / path
    info = source.lstat()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise ValueError(f"Only regular files may be packaged: {path}")
    baseline_data = git_file(repo, baseline, path)
    return {
        "path": path,
        "sizeBytes": info.st_size,
        "sha256": sha256_file(source),
        "baselineSha256": sha256_bytes(baseline_data) if baseline_data is not None else None,
    }


def write_zip(repo: Path, output: Path, manifest: dict):
    epoch = int(os.environ.get("SOURCE_DATE_EPOCH", "315532800"))
    timestamp = datetime.fromtimestamp(max(epoch, 315532800), timezone.utc)
    zip_time = (timestamp.year, timestamp.month, timestamp.day, timestamp.hour, timestamp.minute, timestamp.second)
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
    with tempfile.NamedTemporaryFile(dir=output.parent, suffix=".zip", delete=False) as temp:
        temp_path = Path(temp.name)
    try:
        with zipfile.ZipFile(temp_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            manifest_info = zipfile.ZipInfo(MANIFEST_NAME, zip_time)
            manifest_info.external_attr = 0o100644 << 16
            archive.writestr(manifest_info, manifest_bytes)
            for record in manifest["files"]:
                path = record["path"]
                info = zipfile.ZipInfo(path, zip_time)
                mode = (repo / path).stat().st_mode & 0o777
                info.external_attr = (0o100000 | mode) << 16
                archive.writestr(info, (repo / path).read_bytes())
        temp_path.replace(output)
    finally:
        temp_path.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=".")
    parser.add_argument("--baseline", default=DEFAULT_BASELINE)
    parser.add_argument("--target", default="HEAD")
    parser.add_argument("--output", default=ZIP_NAME)
    parser.add_argument("--incremental-version", default="local-rag-phase-h")
    args = parser.parse_args()

    repo = Path(args.repo).resolve()
    output = Path(args.output)
    if not output.is_absolute():
        output = repo / output
    if output.name != ZIP_NAME:
        raise ValueError(f"Output filename must be {ZIP_NAME}")

    run(repo, "git", "cat-file", "-e", f"{args.baseline}^{{commit}}")
    target_commit = run(repo, "git", "rev-parse", args.target).strip()
    baseline_commit = run(repo, "git", "rev-parse", args.baseline).strip()
    changed, deleted = collect_changes(repo, baseline_commit, target_commit)
    if not changed and not deleted:
        raise ValueError("No incremental changes found")

    records = [file_record(repo, baseline_commit, path) for path in changed]
    manifest = {
        "schemaVersion": 1,
        "baseline": {
            "version": "MNN-3.6.1",
            "commit": baseline_commit,
            "sourceArchiveSha256": BASELINE_ARCHIVE_SHA256,
        },
        "incremental": {
            "version": args.incremental_version,
            "targetCommit": target_commit,
            "createdAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
            "zipFileName": ZIP_NAME,
        },
        "files": records,
        "deleteFiles": deleted,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    write_zip(repo, output, manifest)
    print(json.dumps({"zip": str(output), "files": len(records), "deleted": len(deleted), "sha256": sha256_file(output)}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
