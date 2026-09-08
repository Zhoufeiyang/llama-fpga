"""Verify an approved dense W4 block inside the KV260 Llama2-7B image.

This check is intentionally independent of AWQ and PyTorch.  It proves that
the P1 layout model can invert and reproduce a real block from the approved
``llama0.bin`` artifact, including the page-local four-way DMA permutation.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

import numpy as np

try:
    from .w4_reference import (
        decode_dense_w4,
        dma_join_pages,
        dma_split_pages,
        encode_dense_w4,
        packed_dense_size,
    )
except ImportError:
    from w4_reference import (
        decode_dense_w4,
        dma_join_pages,
        dma_split_pages,
        encode_dense_w4,
        packed_dense_size,
    )


MODEL_BYTES = 2_109_472_768
MODEL_SHA256 = "45bb125d50787badcc6df85fd99ce499ea3a43e160dcee4d736f6fa1b5c2c093"
VOCAB_SIZE = 32_000
HIDDEN_DIM = 4_096
FP16_BYTES = 2
PAGE_SIZE = 8_192
BUS_WIDTH_BITS = 512
DMA_SPLIT = 4
GROUP_SIZE = 128
Q_HEAD_OUTPUT_DIM = 128

# llama0 starts with the FP16 embedding table and one padded attention-norm
# page.  The next region is layer 0, attention head 0, Q projection.
BLOCK_OFFSET = VOCAB_SIZE * HIDDEN_DIM * FP16_BYTES + PAGE_SIZE
LOGICAL_BYTES = packed_dense_size(Q_HEAD_OUTPUT_DIM, HIDDEN_DIM, GROUP_SIZE)
STORED_BYTES = math.ceil(LOGICAL_BYTES / PAGE_SIZE) * PAGE_SIZE
STORED_SHA256 = "bc032a9bbd743fb6ef33e17e773576d5f48831ca24c7d0b08c612575d48fb68a"
LOGICAL_SHA256 = "ffb3064b4183dec833b031e8f42ad20728fc42f6cbed04f35ba41ae371424654"


def sha256_file(path: Path, chunk_size: int = 16 * 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(chunk_size), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(model_path: Path) -> dict[str, object]:
    errors: list[str] = []
    model_size = model_path.stat().st_size
    model_sha256 = sha256_file(model_path)
    if model_size != MODEL_BYTES:
        errors.append(f"model size {model_size} != {MODEL_BYTES}")
    if model_sha256 != MODEL_SHA256:
        errors.append(f"model SHA256 {model_sha256} != {MODEL_SHA256}")

    with model_path.open("rb") as stream:
        stream.seek(BLOCK_OFFSET)
        stored = stream.read(STORED_BYTES)
    if len(stored) != STORED_BYTES:
        errors.append(f"stored block size {len(stored)} != {STORED_BYTES}")

    stored_sha256 = hashlib.sha256(stored).hexdigest()
    if stored_sha256 != STORED_SHA256:
        errors.append(f"stored block SHA256 {stored_sha256} != {STORED_SHA256}")

    logical = dma_join_pages(
        stored,
        original_size=LOGICAL_BYTES,
        bus_width_bits=BUS_WIDTH_BITS,
        split=DMA_SPLIT,
        page_size=PAGE_SIZE,
    )
    logical_sha256 = hashlib.sha256(logical).hexdigest()
    if logical_sha256 != LOGICAL_SHA256:
        errors.append(f"logical block SHA256 {logical_sha256} != {LOGICAL_SHA256}")

    matrix = decode_dense_w4(
        logical,
        output_dim=Q_HEAD_OUTPUT_DIM,
        input_dim=HIDDEN_DIM,
        group_size=GROUP_SIZE,
    )
    logical_round_trip = encode_dense_w4(matrix) == logical
    stored_round_trip = (
        dma_split_pages(
            logical,
            bus_width_bits=BUS_WIDTH_BITS,
            split=DMA_SPLIT,
            page_size=PAGE_SIZE,
        )
        == stored
    )
    scales_finite = bool(np.all(np.isfinite(matrix.scale)))
    if not logical_round_trip:
        errors.append("decoded W4 matrix does not reproduce the logical bytes")
    if not stored_round_trip:
        errors.append("logical bytes do not reproduce the stored DMA pages")
    if not scales_finite:
        errors.append("decoded FP16 scales contain NaN or infinity")

    return {
        "schema_version": 1,
        "gate": "P1_REAL_W4_BLOCK_GO" if not errors else "P1_REAL_W4_BLOCK_NO_GO",
        "model": {
            "path": str(model_path.resolve()),
            "bytes": model_size,
            "sha256": model_sha256,
        },
        "block": {
            "name": "layer0.attention.head0.q_proj",
            "offset": BLOCK_OFFSET,
            "stored_bytes": len(stored),
            "logical_bytes": len(logical),
            "stored_sha256": stored_sha256,
            "logical_sha256": logical_sha256,
            "output_dim": matrix.output_dim,
            "input_dim": matrix.input_dim,
            "group_size": matrix.group_size,
            "qweight_range": [int(matrix.qweight.min()), int(matrix.qweight.max())],
            "zero_range": [int(matrix.zero.min()), int(matrix.zero.max())],
            "scale_range": [float(matrix.scale.min()), float(matrix.scale.max())],
            "scales_finite": scales_finite,
            "logical_round_trip": logical_round_trip,
            "stored_round_trip": stored_round_trip,
        },
        "errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path, help="approved llama0.bin path")
    parser.add_argument("--output", type=Path, help="optional JSON evidence path")
    args = parser.parse_args()

    try:
        result = verify(args.model)
    except (OSError, ValueError) as error:
        result = {
            "schema_version": 1,
            "gate": "P1_REAL_W4_BLOCK_NO_GO",
            "model": {"path": str(args.model.resolve())},
            "errors": [str(error)],
        }

    rendered = json.dumps(result, indent=2, sort_keys=True)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    return 0 if result["gate"] == "P1_REAL_W4_BLOCK_GO" else 1


if __name__ == "__main__":
    raise SystemExit(main())
