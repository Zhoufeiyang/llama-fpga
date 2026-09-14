#!/usr/bin/env python3
"""Derive the bounded INT8 draft from the approved target embedding table."""
import argparse
import hashlib
import json
import struct
import zlib
from pathlib import Path

import numpy as np

MAGIC = 0x54445246
VERSION = 1
HEADER = 64
VOCAB = 32000
TARGET_DIM = 4096
DRAFT_DIM = 8
APPROVED_BYTES = 2109472768
APPROVED_SHA256 = "45bb125d50787badcc6df85fd99ce499ea3a43e160dcee4d736f6fa1b5c2c093"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(16 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_embedding_slice(path: Path) -> np.ndarray:
    result = np.empty((VOCAB, DRAFT_DIM), dtype=np.float32)
    row_bytes = TARGET_DIM * 2
    with path.open("rb") as stream:
        for token in range(VOCAB):
            stream.seek(token * row_bytes)
            raw = stream.read(DRAFT_DIM * 2)
            if len(raw) != DRAFT_DIM * 2:
                raise ValueError(f"short embedding row {token}")
            result[token] = np.frombuffer(raw, dtype="<f2").astype(np.float32)
    if not np.all(np.isfinite(result)):
        raise ValueError("target embedding slice contains non-finite values")
    return result


def quantize(matrix: np.ndarray) -> tuple[np.ndarray, np.float32]:
    max_abs = float(np.max(np.abs(matrix)))
    if max_abs == 0.0:
        raise ValueError("target embedding slice is all zero")
    scale = np.float32(max_abs / 127.0)
    quantized = np.clip(np.rint(matrix / scale), -127, 127).astype(np.int8)
    return quantized, scale


def emit(source: Path, model_path: Path, reference_path: Path) -> None:
    if source.stat().st_size != APPROVED_BYTES:
        raise SystemExit("P7J_NO_GO target_bytes")
    source_sha = sha256_file(source)
    if source_sha != APPROVED_SHA256:
        raise SystemExit("P7J_NO_GO target_sha256")

    trained_embedding = read_embedding_slice(source)
    embedding_q, scale = quantize(trained_embedding)
    # Weight tying gives the tiny callback a genuine neural next-token score
    # without inventing random parameters. Both matrices therefore derive
    # solely from the approved target embedding artifact.
    output_q = embedding_q.copy()
    emb_bytes = embedding_q.tobytes(order="C")
    out_bytes = output_q.tobytes(order="C")
    emb_offset = HEADER
    out_offset = emb_offset + len(emb_bytes)
    total = out_offset + len(out_bytes)
    payload = emb_bytes + out_bytes
    crc = zlib.crc32(payload) & 0xFFFFFFFF
    header = bytearray(HEADER)
    struct.pack_into("<IHHIII I I I I ff I", header, 0, MAGIC, VERSION,
                     HEADER, total, VOCAB, DRAFT_DIM, emb_offset,
                     len(emb_bytes), out_offset, len(out_bytes), float(scale),
                     float(scale), crc)
    model_path.parent.mkdir(parents=True, exist_ok=True)
    model_path.write_bytes(bytes(header) + payload)

    tokens = [0, 1, 7, 42, 1234, 31999]
    rows = []
    for token in tokens:
        hidden = np.maximum(embedding_q[token].astype(np.float32) * scale,
                            np.float32(0.0))
        logits = np.zeros(VOCAB, dtype=np.float32)
        for out in range(VOCAB):
            acc = np.float32(0.0)
            for j in range(DRAFT_DIM):
                acc = np.float32(acc + np.float32(
                    np.float32(output_q[out, j]) * scale * hidden[j]))
            logits[out] = acc
        rows.append({"token": token, "argmax": int(np.argmax(logits)),
                     "scores": {str(i): float(logits[i]) for i in
                                [0, 1, 2, 7, 42, 1234, 31999]}})

    reference = {
        "format": "P7F-TINY-INT8-MLP-1",
        "derivation": "approved-target-embedding-first-8-weight-tied",
        "source_bytes": APPROVED_BYTES,
        "source_sha256": source_sha,
        "vocab": VOCAB,
        "dim": DRAFT_DIM,
        "embedding_scale": float(scale),
        "payload_crc32": f"{crc:08x}",
        "model_bytes": total,
        "model_sha256": hashlib.sha256(model_path.read_bytes()).hexdigest(),
        "tokens": rows,
    }
    reference_path.write_text(json.dumps(reference, indent=2) + "\n", encoding="ascii")
    print("P7J_TARGET_DERIVED_DRAFT_GO "
          f"SOURCE_SHA256={source_sha} MODEL_SHA256={reference['model_sha256']} "
          f"MODEL_BYTES={total} VOCAB={VOCAB} DIM={DRAFT_DIM} CRC32=1")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--reference", required=True, type=Path)
    args = parser.parse_args()
    emit(args.source, args.model, args.reference)
