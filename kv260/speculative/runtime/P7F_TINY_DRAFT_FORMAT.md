# P7-F bounded INT8 draft format

This module is an independent, compact draft-inference primitive for A53
bring-up. It is not a trained Llama draft. The network is a one-layer MLP:

`h = ReLU(embedding_q[token] * embedding_scale)`

`logits[v] = sum_j(output_q[v,j] * output_scale * h[j])`

Both matrices are signed INT8 and stored row-major. Token IDs are `uint16_t`;
the exporter uses `vocab=32000`, `dim=8`, and emits a 512,064-byte model.

The little-endian 64-byte header is:

| Offset | Width | Field |
|---:|---:|---|
| 0 | 4 | magic `0x54445246` |
| 4 | 2 | version `1` |
| 6 | 2 | header bytes `64` |
| 8 | 4 | total model bytes |
| 12 | 4 | vocabulary size |
| 16 | 2 | input dimension |
| 20 | 4 | embedding offset (must be 64) |
| 24 | 4 | embedding byte count |
| 28 | 4 | output offset |
| 32 | 4 | output byte count |
| 36 | 4 | IEEE-754 embedding scale |
| 40 | 4 | IEEE-754 output scale |
| 44 | 4 | CRC32 of payload `[header_bytes,total_bytes)` |

The loader rejects truncated/extended blobs, integer-overflow-prone dimensions,
non-contiguous matrix offsets, non-finite/non-positive scales, bad CRC, invalid
token IDs and insufficient output buffers. It owns no storage and performs no
allocation; the caller keeps the model blob resident in DDR/flash.

## P7-F acceptance

`run_p7f_tiny_draft.ps1` invokes the deterministic exporter, host C test,
NumPy oracle comparison, and AArch64 freestanding object build when the Xilinx
compiler is present. The host test also corrupts CRC, truncates the blob and
checks token/output bounds. A passing run must contain:

`P7F_TINY_DRAFT_GO ... CRC32=1 ... ARM_FREESTANDING=1`

and

`P7F_NUMPY_EQUIVALENCE_GO ... MAX_ABS<=1e-6`.
