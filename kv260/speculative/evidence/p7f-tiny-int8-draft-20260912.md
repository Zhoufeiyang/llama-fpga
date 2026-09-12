# P7-F bounded INT8 tiny draft — 2026-09-12

## Gate status

`P7-F model-format sub-gate: GO`

`P7-F NumPy/C numerical-equivalence sub-gate: GO`

`P7-F AArch64 freestanding sub-gate: GO`

`P7 trained-neural-draft and live-board gates: IN PROGRESS`

## Implemented artifact

The implementation is a no-heap one-layer INT8 MLP with a 32,000-entry input
embedding, ReLU, and 32,000-entry output projection. Both matrices have width
8 and independent FP32 dequantization scales. Greedy argmax uses lowest-token
tie breaking and is exposed through the `spec_runtime` draft callback shape.

The 64-byte little-endian header validates magic, version, total length,
vocabulary, dimension, contiguous matrix offsets and sizes, positive finite
scales, and CRC32 before accepting a model.

## Reproduction and result

```powershell
& .\kv260\speculative\runtime\run_p7f_tiny_draft.ps1
```

```text
P7F_TINY_DRAFT_C_GO VOCAB=32000 DIM=8 INT8_MATRICES=2 CRC32=1 GREEDY=1
P7F_NUMPY_EQUIVALENCE_GO TOKENS=6 VOCAB=32000 MAX_ABS=0 MAX_REL=0 SHA256=97cbc36ce2600909144db74860d2d9279283a4f1ff4488d03cc82643893f02fb
P7F_TINY_DRAFT_GO MODEL_BYTES=512064 VOCAB=32000 DIM=8 INT8_MATRICES=2 CRC32=1 MODEL_SHA256=97cbc36ce2600909144db74860d2d9279283a4f1ff4488d03cc82643893f02fb ARM_FREESTANDING=1
```

The model is 512,064 bytes. It fits the corrected linker-aware DDR budget
alongside the target and K=4 runtime buffers. The model is generated from
deterministic synthetic INT8 weights specifically for parser, arithmetic, and
cross-language equivalence verification. It is not a trained language model,
so this gate does not claim useful acceptance length or speedup.

Audited objects:

- `tiny_draft_arm.o`: 3,032 bytes, SHA256
  `101e6d3a1a7f564fa98852e7e6673772fbaf775b4772237ea946a05be0f75ebe`;
- `p7f_arm_entry.o`: 1,184 bytes, SHA256
  `f26e33dab854c8ea90cd5f7dda7efad7cb238526a89a4a7a5bcb63fb0fa16e15`.
