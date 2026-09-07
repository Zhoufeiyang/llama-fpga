# KV260 speculative verification development

This directory contains the staged development records and reproducibility
checks for the bandwidth-amortized speculative-verification design.

## Frozen scope

- Target model: Llama2-7B using the existing W4 weight format.
- Activation format: FP16.
- KV-cache format: INT8 data with the existing scale/zero metadata.
- Initial hardware limit: `KMAX = 4`, with runtime `K = 1..4`.
- Initial acceptance policy: greedy speculative decoding.
- Compatibility requirement: legacy single-token decode remains available.
- Hardware requirement: GEMV and verification share the same dequantizer and
  DSP MAC datapath.

## Stage gates

1. `P0`: establish a correct and reproducible target-only board baseline.
2. `P1`: implement the packed-W4 numerical reference and test vectors.
3. `P2`: implement and verify a dual-mode W4 projection kernel.
4. `P3`: integrate batched projections into the transformer scheduler.
5. `P4`: implement causal multi-token attention and tiled KV streaming.
6. `P5`: implement tentative KV writes and pointer-based commit.
7. `P6`: implement the PS-side greedy speculative runtime.
8. `P7`: close timing, validate on board, and collect publication metrics.

No stage may be used as evidence for a later claim until its GO conditions are
met. Failed builds and experiments must retain their logs and artifact hashes.

## Current status

`P0: NO-GO`

The repository baseline is commit `df89b67e50383f4716e03aac35d6a25a35b0f98e`.
The fixed-linker application exists, but no board log currently demonstrates
correct raw-token generation with that application.

The locally available model files have the expected byte counts but do not
match the SHA256 values recorded for `llama0.bin` and `llama1.bin` in the
checked-in `python/gen_bin.ipynb`. Until their exact provenance is established,
they are classified as unverified artifacts rather than a publication
baseline.

Historical FTDI registry entries identify COM8 and COM9, but neither port was
enumerated during the current audit. Board validation must resume only after
the cable and board are present.

## P0 GO conditions

- Vivado and Vitis are both version 2022.2.
- XSA, bitstream, PS initialization, BSP, and ELF have recorded provenance.
- `region_0` is linked at `0x00036000` with size `0x722b4000`.
- Model byte counts and approved hashes match.
- Fixed input token IDs produce finite logits and the approved raw output IDs.
- Three reset-and-run trials produce the same raw output sequence.
- AXI read/write responses contain no errors.
- The routed baseline has no unrouted nets and has nonnegative setup and hold
  slack.

Run `scripts/verify_baseline.ps1` for the host-side portion of this gate.
