#!/usr/bin/env python3
"""Validate and inject the pinned embedding bundle into Android assets."""
import argparse
import hashlib
import json
import os
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path, PurePosixPath


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def safe_relative(raw):
    normalized = raw.replace('\\', '/')
    path = PurePosixPath(normalized)
    if not normalized or path.is_absolute() or '.' in path.parts or '..' in path.parts:
        raise ValueError(f'Unsafe model archive path: {raw!r}')
    return path


def load_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def runtime_manifest(source_manifest, pin):
    records = {record['path']: record for record in source_manifest['files']}
    declared = []
    for name in pin['allowedFiles']:
        record = records[name]
        declared.append({
            'relativePath': name,
            'sha256': record['sha256'],
            'sizeBytes': record['size']
        })
    return {
        'schemaVersion': 1,
        'bundleVersion': pin['sourceRevision'],
        'models': [{
            'id': pin['modelId'],
            'role': 'embedding',
            'format': 'mnn',
            'files': declared,
            'inputs': [
                {'name': 'input_ids', 'dtype': 'int32', 'shape': [-1, -1]},
                {'name': 'attention_mask', 'dtype': 'int32', 'shape': [-1, -1]},
                {'name': 'position_ids', 'dtype': 'int32', 'shape': [-1, -1]}
            ],
            'outputs': [
                {'name': 'sentence_embeddings', 'dtype': 'float32', 'shape': [-1, 512]}
            ],
            'threads': 4,
            'options': {
                'configPath': pin['configPath'],
                'dimensions': str(pin['dimensions']),
                'maxSequenceLength': str(pin['maxSequenceLength']),
                'maxContentTokens': str(pin['maxContentTokens'])
            }
        }]
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', required=True)
    parser.add_argument('--pin', default='scripts/bundled_embedding_model.json')
    parser.add_argument('--repo', default='.')
    args = parser.parse_args()
    repo = Path(args.repo).resolve()
    archive = Path(args.archive).resolve()
    pin_path = Path(args.pin)
    if not pin_path.is_absolute():
        pin_path = repo / pin_path
    pin = load_json(pin_path)
    if archive.name != pin['archiveName']:
        raise ValueError('Model archive filename does not match the pin')
    if archive.stat().st_size != pin['archiveSizeBytes']:
        raise ValueError('Model archive size mismatch')
    if sha256_file(archive) != pin['archiveSha256']:
        raise ValueError('Model archive SHA-256 mismatch')

    root = pin['archiveRoot'] + '/'
    allowed = set(pin['allowedFiles'])
    with zipfile.ZipFile(archive) as bundle:
        members = bundle.infolist()
        names = [safe_relative(member.filename).as_posix() for member in members]
        if len(names) != len(set(names)):
            raise ValueError('Model archive contains duplicate paths')
        for member, name in zip(members, names):
            mode = (member.external_attr >> 16) & 0xffff
            if stat.S_IFMT(mode) not in (0, stat.S_IFREG):
                raise ValueError(f'Non-regular archive entry: {name}')
            if not name.startswith(root):
                raise ValueError(f'Entry is outside the fixed archive root: {name}')
        relative_names = {name[len(root):] for name in names}
        if relative_names != allowed:
            raise ValueError('Archive files do not match the fixed allowlist')

        with tempfile.TemporaryDirectory(prefix='mnn-bundled-model-') as temp_name:
            staging = Path(temp_name) / pin['modelId']
            staging.mkdir(parents=True)
            for member, name in zip(members, names):
                relative = name[len(root):]
                target = staging / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.open(member) as source, target.open('wb') as output:
                    shutil.copyfileobj(source, output)

            source_manifest = load_json(staging / 'model-manifest.json')
            if source_manifest['revision'] != pin['sourceRevision']:
                raise ValueError('Source manifest revision mismatch')
            records = source_manifest.get('files', [])
            record_names = {record['path'] for record in records}
            if record_names != allowed - {'model-manifest.json'}:
                raise ValueError('Source manifest file list is incomplete')
            for record in records:
                path = staging / record['path']
                if path.stat().st_size != record['size']:
                    raise ValueError(f"Size mismatch: {record['path']}")
                if sha256_file(path) != record['sha256']:
                    raise ValueError(f"SHA-256 mismatch: {record['path']}")

            model_manifest_record = {
                'path': 'model-manifest.json',
                'size': (staging / 'model-manifest.json').stat().st_size,
                'sha256': sha256_file(staging / 'model-manifest.json')
            }
            source_manifest['files'].append(model_manifest_record)
            manifest = runtime_manifest(source_manifest, pin)
            manifest_path = staging / pin['runtimeManifest']
            manifest_path.write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + '\n',
                encoding='utf-8'
            )

            destination = (repo / pin['assetDestination']).resolve()
            if repo != destination and repo not in destination.parents:
                raise ValueError('Asset destination escapes repository root')
            destination.parent.mkdir(parents=True, exist_ok=True)
            replacement = destination.with_name(destination.name + '.tmp')
            if replacement.exists():
                shutil.rmtree(replacement)
            shutil.copytree(staging, replacement)
            if destination.exists():
                if destination.is_symlink():
                    raise ValueError('Refusing to replace a symlink destination')
                shutil.rmtree(destination)
            os.replace(replacement, destination)

    summary = {
        'destination': str(destination),
        'modelId': pin['modelId'],
        'revision': pin['sourceRevision'],
        'runtimeManifest': str(destination / pin['runtimeManifest']),
        'files': sorted(path.name for path in destination.iterdir() if path.is_file())
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        print(f'ERROR: {error}')
        raise SystemExit(1)
