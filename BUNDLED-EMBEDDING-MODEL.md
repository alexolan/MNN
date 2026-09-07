# Bundled Embedding Model Contract

The pinned `bge-small-zh-v1.5-MNN` archive is not committed to Git. GitHub Actions downloads the immutable Release asset described by `scripts/bundled_embedding_model.json`, verifies its filename, byte size and SHA-256, and invokes `scripts/prepare_bundled_embedding_model.py`.

The preparation script rejects absolute paths, traversal, duplicate entries, non-regular entries, unexpected files, revision mismatches, size mismatches and SHA-256 mismatches. It writes the validated bundle to:

```text
apps/Android/MnnLlmChat/app/src/main/assets/rag/models/bge-small-zh-v1.5/
```

The final directory contains all pinned source files plus a generated runtime `manifest.json`. The runtime manifest conforms to `ModelManifestValidator` schema version 1, declares exactly one MNN embedding model, fixes the output dimension at 512, and exposes `sentence_embeddings`.

The model remains absent from the source repository and incremental ZIP. Build workflows inject it only after applying the incremental overlay. No workflow may use a floating `latest` URL or accept an archive with a different digest.
