#!/usr/bin/env python3
"""Safely validate and apply the fixed-name root-overlay incremental ZIP."""

import argparse
import hashlib
import json
import os
import shutil
import stat
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

ZIP_NAME = "mnn-3.6.1-local-rag-incremental.zip"
MANIFEST_NAME = "INCREMENTAL-MANIFEST.json"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_path(raw: str) -> str:
    normalized = raw.replace("\\", "/")
    path = PurePosixPath(normalized)
    if not normalized or path.is_absolute() or "." in path.parts or ".." in path.parts:
        raise ValueError(f"Unsafe incremental path: {raw!r}")
    return path.as_posix()


def validate_archive(archive: zipfile.ZipFile):
    members = archive.infolist()
    names = [safe_path(member.filename) for member in members]
    if len(names) != len(set(names)):
        raise ValueError("ZIP contains duplicate paths")
    if names.count(MANIFEST_NAME) != 1:
        raise ValueError(f"ZIP must contain exactly one {MANIFEST_NAME}")
    for member, name in zip(members, names):
        mode = (member.external_attr >> 16) & 0xFFFF
        file_type = stat.S_IFMT(mode)
        if file_type not in (0, stat.S_IFREG):
            raise ValueError(f"ZIP contains a non-regular entry: {name}")
    manifest = json.loads(archive.read(MANIFEST_NAME).decode("utf-8"))
    if manifest.get("schemaVersion") != 1:
        raise ValueError("Unsupported incremental manifest schema")
    if manifest.get("incremental", {}).get("zipFileName") != ZIP_NAME:
        raise ValueError("Manifest ZIP filename does not match the fixed contract")
    records = manifest.get("files")
    delete_files = manifest.get("deleteFiles")
    if not isinstance(records, list) or not isinstance(delete_files, list):
        raise ValueError("Manifest files and deleteFiles must be arrays")
    record_paths = [safe_path(record["path"]) for record in records]
    deletion_paths = [safe_path(path) for path in delete_files]
    expected = {MANIFEST_NAME, *record_paths}
    if set(names) != expected:
        raise ValueError("Manifest file list does not match ZIP contents")
    if set(record_paths) & set(deletion_paths):
        raise ValueError("A path cannot be both written and deleted")
    for record in records:
        path = safe_path(record["path"])
        data = archive.read(path)
        if len(data) != record.get("sizeBytes"):
            raise ValueError(f"Size mismatch: {path}")
        if hashlib.sha256(data).hexdigest() != record.get("sha256"):
            raise ValueError(f"SHA-256 mismatch: {path}")
    return manifest


def ensure_under_root(root: Path, relative: str) -> Path:
    target = (root / relative).resolve()
    if target != root and root not in target.parents:
        raise ValueError(f"Destination escapes repository root: {relative}")
    return target


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=".")
    parser.add_argument("--zip", dest="zip_path", default=ZIP_NAME)
    parser.add_argument("--overlay-list", default="incremental-overlay.txt")
    args = parser.parse_args()

    repo = Path(args.repo).resolve()
    zip_path = Path(args.zip_path).resolve()
    if zip_path.name != ZIP_NAME:
        raise ValueError(f"Only {ZIP_NAME} is accepted")
    if not zip_path.is_file():
        raise ValueError(f"Incremental ZIP not found: {zip_path}")

    with zipfile.ZipFile(zip_path, "r") as archive:
        manifest = validate_archive(archive)
        with tempfile.TemporaryDirectory(prefix="mnn-incremental-") as temp_name:
            staging = Path(temp_name)
            for record in manifest["files"]:
                relative = safe_path(record["path"])
                target = staging / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(relative) as source, target.open("wb") as destination:
                    shutil.copyfileobj(source, destination)
                if sha256_file(target) != record["sha256"]:
                    raise ValueError(f"Staged SHA-256 mismatch: {relative}")

            actions = []
            for relative in manifest["deleteFiles"]:
                relative = safe_path(relative)
                target = ensure_under_root(repo, relative)
                if target.is_symlink():
                    raise ValueError(f"Refusing to delete symlink: {relative}")
                if target.is_dir():
                    shutil.rmtree(target)
                elif target.exists():
                    target.unlink()
                actions.append(f"DELETE\t{relative}")

            for record in manifest["files"]:
                relative = safe_path(record["path"])
                source = staging / relative
                target = ensure_under_root(repo, relative)
                if target.is_symlink():
                    raise ValueError(f"Refusing to overwrite symlink: {relative}")
                target.parent.mkdir(parents=True, exist_ok=True)
                temporary = target.with_name(target.name + ".incremental.tmp")
                shutil.copy2(source, temporary)
                os.replace(temporary, target)
                if sha256_file(target) != record["sha256"]:
                    raise ValueError(f"Applied SHA-256 mismatch: {relative}")
                actions.append(f"WRITE\t{relative}\t{record['sha256']}")

    overlay_list = Path(args.overlay_list)
    if not overlay_list.is_absolute():
        overlay_list = repo / overlay_list
    overlay_list.write_text("\n".join(actions) + "\n", encoding="utf-8")
    print(json.dumps({"zip": str(zip_path), "written": len(manifest["files"]), "deleted": len(manifest["deleteFiles"]), "overlayList": str(overlay_list)}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
