# P7-J target-derived INT8 draft — 2026-09-14

`P7 real-weight quantized draft artifact/source/A53 sub-gate: GO`

`P7 board acceptance-rate measurement: IN PROGRESS`

## Model provenance and derivation

The exporter accepts only the P0-approved target artifact:

- source: `llama0.bin`
- bytes: `2,109,472,768`
- SHA256: `45bb125d50787badcc6df85fd99ce499ea3a43e160dcee4d736f6fa1b5c2c093`

It extracts the first eight FP16 dimensions from every row of the trained
32,000 x 4,096 target embedding table, applies deterministic symmetric INT8
quantization, and uses the same trained embedding slice as a tied output
matrix. This is a bounded target-derived neural draft, not the earlier
synthetic arithmetic fixture. The existing versioned 64-byte header, payload
CRC32, no-heap C inference, and callback ABI are retained.

## Generated artifact

- bytes: `512,064`
- SHA256: `af3e121ac30e8ad3a7fd671c67120f64a8749f41009e64f16c39b0a5fb288c21`
- DDR address from the checked memory budget: `0x722F6000`
- A53 ELF bytes: `123,248`
- A53 ELF SHA256:
  `5bcd4e60bb681af5b9b4dadd9a165ba649707ab56c769ad7340c4bbbf8f02ad3`

The model binary and ELF are generated build products and are not committed.

## Acceptance evidence

```text
P7J_TARGET_DERIVED_DRAFT_GO SOURCE_SHA256=45bb125d50787badcc6df85fd99ce499ea3a43e160dcee4d736f6fa1b5c2c093 MODEL_SHA256=af3e121ac30e8ad3a7fd671c67120f64a8749f41009e64f16c39b0a5fb288c21 MODEL_BYTES=512064 VOCAB=32000 DIM=8 CRC32=1
P7F_NUMPY_EQUIVALENCE_GO TOKENS=6 VOCAB=32000 MAX_ABS=0 MAX_REL=0 SHA256=af3e121ac30e8ad3a7fd671c67120f64a8749f41009e64f16c39b0a5fb288c21
P7J_TARGET_DERIVED_RUNTIME_GO MODEL_BYTES=512064 MODEL_SHA256=af3e121ac30e8ad3a7fd671c67120f64a8749f41009e64f16c39b0a5fb288c21 C_NUMPY_EQUIVALENT=1
P7J_A53_BINDING_GO ARCH=AARCH64 DRAFT_ADDR=0x722F6000 DRAFT_BYTES=512064 ELF_BYTES=123248 ELF_SHA256=5bcd4e60bb681af5b9b4dadd9a165ba649707ab56c769ad7340c4bbbf8f02ad3
```

The generalized verifier was also rerun against the original synthetic P7-F
fixture and remains bit/numerically equivalent. Model quality, acceptance rate,
and speedup are intentionally deferred to the final live-board run.
