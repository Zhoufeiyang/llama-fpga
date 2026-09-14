#!/usr/bin/env python3
"""Check host C logits against the independent NumPy oracle."""
import argparse
import hashlib
import json
import struct
from pathlib import Path

import numpy as np

from tiny_draft_export import MAGIC


def load_model(path: Path):
    blob = path.read_bytes()
    if len(blob) < 64:
        raise SystemExit("P7F_NO_GO short_model")
    (magic, version, header, total, vocab, dim, emb_off, emb_bytes,
     out_off, out_bytes, emb_scale, out_scale, _crc) = struct.unpack_from(
         "<IHHIII I I I I ff I", blob, 0)
    if (magic != MAGIC or version != 1 or header != 64 or total != len(blob) or
            emb_bytes != vocab * dim or out_bytes != vocab * dim):
        raise SystemExit("P7F_NO_GO model_header")
    embedding = np.frombuffer(blob, dtype=np.int8, count=emb_bytes,
                              offset=emb_off).reshape(vocab, dim)
    output = np.frombuffer(blob, dtype=np.int8, count=out_bytes,
                           offset=out_off).reshape(vocab, dim)
    return vocab, dim, np.float32(emb_scale), np.float32(out_scale), embedding, output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--c-logits", type=Path, required=True)
    args = parser.parse_args()
    ref = json.loads(args.reference.read_text(encoding="ascii"))
    vocab, dim, emb_scale, out_scale, embedding, output = load_model(args.model)
    raw = args.c_logits.read_bytes()
    expected_bytes = len(ref["tokens"]) * vocab * 4
    if len(raw) != expected_bytes:
        raise SystemExit(f"P7F_NO_GO logits_bytes={len(raw)} expected={expected_bytes}")
    c_logits = np.frombuffer(raw, dtype="<f4").reshape(len(ref["tokens"]), vocab)
    max_abs = 0.0
    max_rel = 0.0
    for row, item in enumerate(ref["tokens"]):
        hidden = np.maximum(embedding[item["token"]].astype(np.float32) * emb_scale,
                            np.float32(0.0))
        oracle = np.zeros(vocab, dtype=np.float32)
        for out in range(vocab):
            acc = np.float32(0.0)
            for j in range(dim):
                acc = np.float32(acc + np.float32(
                    np.float32(output[out, j]) * out_scale * hidden[j]))
            oracle[out] = acc
        delta = np.abs(c_logits[row] - oracle)
        max_abs = max(max_abs, float(delta.max()))
        denom = np.maximum(np.abs(oracle), np.float32(1e-7))
        max_rel = max(max_rel, float((delta / denom).max()))
        if int(np.argmax(c_logits[row])) != item["argmax"]:
            raise SystemExit(f"P7F_NO_GO argmax token={item['token']}")
        if float(delta.max()) > 1e-6:
            raise SystemExit(f"P7F_NO_GO numerical token={item['token']} max_abs={delta.max()}")
    model_sha = hashlib.sha256(args.model.read_bytes()).hexdigest()
    if model_sha != ref["model_sha256"]:
        raise SystemExit("P7F_NO_GO model_sha256")
    print(f"P7F_NUMPY_EQUIVALENCE_GO TOKENS={len(ref['tokens'])} VOCAB={vocab} "
          f"MAX_ABS={max_abs:.8g} MAX_REL={max_rel:.8g} SHA256={model_sha}")


if __name__ == "__main__":
    main()
