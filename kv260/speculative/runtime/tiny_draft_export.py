#!/usr/bin/env python3
"""Create a deterministic, compact INT8 P7-F draft model and NumPy oracle."""
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
DIM = 8
EMB_SCALE = np.float32(1.0 / 64.0)
OUT_SCALE = np.float32(1.0 / 64.0)


def make_matrices():
    e = np.fromfunction(lambda i, j: ((i * 13 + j * 17) % 127) - 63,
                        (VOCAB, DIM), dtype=np.int64).astype(np.int8)
    w = np.fromfunction(lambda i, j: ((i * 7 + j * 19 + 11) % 127) - 63,
                        (VOCAB, DIM), dtype=np.int8)
    return e, w


def emit(model_path: Path, reference_path: Path):
    embedding, output = make_matrices()
    emb_bytes = embedding.tobytes(order="C")
    out_bytes = output.tobytes(order="C")
    emb_offset = HEADER
    out_offset = emb_offset + len(emb_bytes)
    total = out_offset + len(out_bytes)
    payload = emb_bytes + out_bytes
    crc = zlib.crc32(payload) & 0xffffffff
    header = bytearray(HEADER)
    struct.pack_into("<IHHIII I I I I ff I", header, 0, MAGIC, VERSION,
                     HEADER, total, VOCAB, DIM, emb_offset, len(emb_bytes),
                     out_offset, len(out_bytes), float(EMB_SCALE),
                     float(OUT_SCALE), crc)
    model_path.parent.mkdir(parents=True, exist_ok=True)
    model_path.write_bytes(bytes(header) + payload)

    tokens = [0, 1, 7, 42, 1234, 31999]
    rows = []
    for token in tokens:
        hidden = np.maximum(embedding[token].astype(np.float32) * EMB_SCALE,
                            np.float32(0.0))
        logits = np.zeros(VOCAB, dtype=np.float32)
        for out in range(VOCAB):
            acc = np.float32(0.0)
            for j in range(DIM):
                acc = np.float32(acc + np.float32(
                    np.float32(output[out, j]) * OUT_SCALE * hidden[j]))
            logits[out] = acc
        rows.append({"token": token, "argmax": int(np.argmax(logits)),
                     "scores": {str(i): float(logits[i]) for i in
                                [0, 1, 2, 7, 42, 1234, 31999]}})
    reference_path.write_text(json.dumps({
        "format": "P7F-TINY-INT8-MLP-1", "magic": hex(MAGIC),
        "version": VERSION, "vocab": VOCAB, "dim": DIM,
        "model_bytes": total,
        "model_sha256": hashlib.sha256(model_path.read_bytes()).hexdigest(),
        "payload_crc32": f"{crc:08x}", "tokens": rows,
    }, indent=2) + "\n", encoding="ascii")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--reference", required=True, type=Path)
    args = parser.parse_args()
    emit(args.model, args.reference)
