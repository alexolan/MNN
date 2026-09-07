#!/usr/bin/env python3
"""Verify the pinned embedding model inventory inside a built APK."""

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path, PurePosixPath


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def load_json_bytes(data, label):
    try:
        return json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"Invalid JSON in {label}: {error}") from error


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--pin", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()

    apk = Path(args.apk).resolve()
    pin_path = Path(args.pin).resolve()
    report_path = Path(args.report).resolve()
    pin = json.loads(pin_path.read_text(encoding="utf-8"))

    if not apk.is_file():
        raise ValueError(f"APK does not exist: {apk}")

    asset_prefix = "assets/" + pin["assetDestination"].split("/src/main/assets/", 1)[1].rstrip("/") + "/"
    expected_relative = set(pin["allowedFiles"]) | {pin["runtimeManifest"]}
    expected_entries = {asset_prefix + name for name in expected_relative}

    with zipfile.ZipFile(apk) as archive:
        infos = archive.infolist()
        names = [info.filename for info in infos]
        if len(names) != len(set(names)):
            raise ValueError("APK contains duplicate ZIP entries")

        model_infos = [info for info in infos if info.filename.startswith(asset_prefix)]
        actual_entries = {info.filename for info in model_infos}
        if actual_entries != expected_entries:
            missing = sorted(expected_entries - actual_entries)
            extra = sorted(actual_entries - expected_entries)
            raise ValueError(f"Bundled model inventory mismatch; missing={missing}, extra={extra}")

        for info in model_infos:
            relative = info.filename[len(asset_prefix):]
            path = PurePosixPath(relative)
            if not relative or path.is_absolute() or "." in path.parts or ".." in path.parts:
                raise ValueError(f"Unsafe model path in APK: {info.filename}")
            if info.is_dir():
                raise ValueError(f"Unexpected directory entry in model inventory: {info.filename}")
            if info.compress_type != zipfile.ZIP_STORED:
                raise ValueError(f"Bundled model file was recompressed: {info.filename}")

        runtime_name = asset_prefix + pin["runtimeManifest"]
        runtime_bytes = archive.read(runtime_name)
        runtime = load_json_bytes(runtime_bytes, runtime_name)
        if runtime.get("schemaVersion") != 1:
            raise ValueError("Unexpected runtime manifest schema")
        if runtime.get("bundleVersion") != pin["sourceRevision"]:
            raise ValueError("Runtime manifest revision mismatch")

        models = runtime.get("models")
        if not isinstance(models, list) or len(models) != 1:
            raise ValueError("Runtime manifest must declare exactly one model")
        model = models[0]
        if model.get("id") != pin["modelId"] or model.get("role") != "embedding" or model.get("format") != "mnn":
            raise ValueError("Runtime model identity mismatch")

        options = model.get("options", {})
        expected_options = {
            "configPath": pin["configPath"],
            "dimensions": str(pin["dimensions"]),
            "maxSequenceLength": str(pin["maxSequenceLength"]),
            "maxContentTokens": str(pin["maxContentTokens"]),
        }
        for key, expected in expected_options.items():
            if options.get(key) != expected:
                raise ValueError(f"Runtime model option mismatch: {key}")

        inputs = {item.get("name") for item in model.get("inputs", [])}
        if inputs != {"input_ids", "attention_mask", "position_ids"}:
            raise ValueError("Runtime model input contract mismatch")
        outputs = model.get("outputs", [])
        if len(outputs) != 1 or outputs[0].get("name") != "sentence_embeddings":
            raise ValueError("Runtime model output contract mismatch")
        if outputs[0].get("shape") != [-1, pin["dimensions"]]:
            raise ValueError("Runtime model output dimension mismatch")

        records = model.get("files", [])
        record_names = {record.get("relativePath") for record in records}
        if record_names != set(pin["allowedFiles"]):
            raise ValueError("Runtime manifest file inventory mismatch")

        verified = []
        for record in records:
            relative = record["relativePath"]
            entry = asset_prefix + relative
            payload = archive.read(entry)
            if len(payload) != record["sizeBytes"]:
                raise ValueError(f"Bundled model size mismatch: {relative}")
            digest = sha256_bytes(payload)
            if digest != record["sha256"]:
                raise ValueError(f"Bundled model SHA-256 mismatch: {relative}")
            info = archive.getinfo(entry)
            verified.append({
                "path": relative,
                "sizeBytes": len(payload),
                "compressedSizeBytes": info.compress_size,
                "sha256": digest,
                "stored": info.compress_type == zipfile.ZIP_STORED,
            })

        manifest_info = archive.getinfo(runtime_name)
        source_size = sum(info.file_size for info in model_infos)
        compressed_size = sum(info.compress_size for info in model_infos)

    report = {
        "schemaVersion": 1,
        "apk": apk.name,
        "apkSizeBytes": apk.stat().st_size,
        "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "modelId": pin["modelId"],
        "sourceRevision": pin["sourceRevision"],
        "assetPrefix": asset_prefix,
        "entryCount": len(expected_entries),
        "modelSizeBytes": source_size,
        "modelCompressedSizeBytes": compressed_size,
        "allEntriesStored": compressed_size == source_size,
        "runtimeManifestSha256": sha256_bytes(runtime_bytes),
        "runtimeManifestSizeBytes": manifest_info.file_size,
        "verifiedFiles": sorted(verified, key=lambda item: item["path"]),
        "status": "passed",
    }
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
