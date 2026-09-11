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

`P0: GO`

`P0 board-functional sub-gate: GO (3/3 deterministic reset-and-run trials)`

`P0 read-length/raw-token sub-gate: GO`

`P0 sampled SD-to-DDR sub-gate: GO`

`P0 model-artifact provenance sub-gate: GO`

`P0 physical implementation sub-gate: GO`

`P0 AXI-response sub-gate: GO (board-owner acceptance)`

`P1 reference-layout sub-gate: GO (synthetic vectors)`

`P1 real-weight-block sub-gate: GO`

`P1 RTL-layout-simulation sub-gate: GO`

`P1: GO`

`P2 weight-reuse scheduler sub-gate: GO`

`P2 FP16 MAC/reduction sub-gate: GO`

`P2: GO`

`P3 transformer projection scheduler sub-gate: GO`

`P3 P2-adapter sub-gate: GO`

`P3: GO`

`P4 causal KV tile scheduler sub-gate: GO`

`P4: IN PROGRESS`

The repository baseline is commit `df89b67e50383f4716e03aac35d6a25a35b0f98e`.
The fixed-linker application now completes end-to-end generation on KV260.
Three reset-and-run trials on 2026-09-08 produced identical normalized response
SHA256 `2c8b802fb09fee4d538f84127b5f319b660fcb3bc0f5802e6e363004d531428c`
for the fixed prompt. See `evidence/p0-board-functional-20260908.md`.
The exact uninstrumented baseline was rebuilt with Vivado 2022.2 and passed
route/timing sign-off with `WNS = 0.022 ns`, `WHS = 0.009 ns`, and no unrouted
or partially routed nets. The board owner confirmed the completed AXI-response
validation and accepted the overall P0 gate on 2026-09-09. The frozen artifact
hashes and acceptance record are in `evidence/p0-gate-20260909.md`.

The PS-only diagnostic run then proved exact FatFS byte counts and captured a
690-token, zero-free, EOS-terminated raw-ID sequence whose decoded response
matches the three-run baseline. Direct SD-window and JTAG DDR reads proved the
sampled SD-to-DDR transfer. The host model artifacts were refreshed and, on
2026-09-08, both byte counts and SHA256 values matched the approved notebook
manifest. See
`evidence/p0-storage-token-diagnostics-20260908.md`.

The approved host model artifacts are `llama0.bin` SHA256
`45bb125d50787badcc6df85fd99ce499ea3a43e160dcee4d736f6fa1b5c2c093` and
`llama1.bin` SHA256
`7e947c152ef71de1128248c25a5bda18c652e9356a3ed00c0953ea17ad294afe`.

The imported KV260 `DataPath_xN.v` contains one compute core with four HP DMA
ports. `top/EdgeLLMKv260Config.scala` now records that topology, its 40-bit DDR
command width, and address remap as an explicit platform contract used by
`EdgeLLMInst`. `scala/build.sbt` is pinned to the imported RTL generator
version, SpinalHDL 1.10.2a. The checked-in/imported RTL plus frozen build
artifacts form the accepted P0 baseline; source-regeneration equivalence remains
a separate reproducibility hardening task and does not block P2.

COM8 and COM9 were enumerated on 2026-09-08. JTAG identified cable
`Xilinx X-MLCC-01 XFL1FVVDTE2WA`, FPGA `xck26` IDCODE `04724093`, and ARM DAP
IDCODE `5ba00477`.

## P0 GO conditions

- Vivado and Vitis are both version 2022.2.
- XSA, bitstream, PS initialization, BSP, and ELF have recorded provenance.
- The SpinalHDL generator configuration reproduces the imported RTL topology.
- `region_0` is linked at `0x00036000` with size `0x722b4000`.
- Model byte counts and approved hashes match.
- Fixed input token IDs produce finite logits and the approved raw output IDs.
- Three reset-and-run trials produce the same raw output sequence.
- AXI read/write responses contain no errors.
- The routed baseline has no unrouted nets and has nonnegative setup and hold
  slack.

Run `scripts/verify_baseline.ps1` for the host-side portion of this gate.

## P1 reference-model status

The standalone NumPy model under `reference/` mirrors the checked-in dense W4
packer, including nibble order, scale/zero placement, and the exponent edit in
`util.Fp16ScaleDown`. Its synthetic-vector unit tests cover `K = 1..4` and the
contract that packed target-weight traffic is independent of K.

The approved layer-0/head-0 Q projection block passes full-image provenance,
page-local DMA inversion, W4 decode/re-encode, and stored-byte reconstruction.
Vivado xsim 2022.2 independently checks its first two 512-bit beats, nibble
order, and FP16 scale byte order. See `evidence/p1-reference-20260908.md`.
P0 and P1 are GO, so P2 production RTL development is enabled.

## P2 implementation status

The first P2 hardware sub-gate implements a synthesizable W4 operand scheduler
for legacy GEMV and speculative GEMM. It buffers `KMAX=4` FP16 activation rows,
accepts every packed W4 group exactly once, performs low-nibble-first zero-point
subtraction, and replays the dequantized group and its FP16 scale through the
same downstream operand port for `K=1..4`. Multi-row projections reuse the
activation tile without reloading it.

Vivado xsim covers both modes at `K=1`, GEMM at `K=2..4`, multi-row/tail
boundaries, output backpressure, sideband stability, invalid GEMV descriptors,
and K-independent accepted-weight counts. Focused OOC synthesis at the KV260
production geometry (`LANES=128`, `MAX_BEATS_PER_ROW=32`) infers BRAM and meets
a 300 MHz post-synthesis constraint. See
`evidence/p2-weight-reuse-20260909.md`.

P2-B connects the scheduler to `fp16int9d4`, the exact
`Fp16ScaleDown(..., 2)` exponent edit, the shared `fp16mul6` lane array, the
four-bank FP16 reduction tree, and the existing FP32 cross-bank
scale/accumulation boundary. Four token-selected FP32 accumulators preserve
independent speculative rows while the conversion, multiplier, and reduction
hardware remain shared.

Actual Xilinx Floating-Point IP simulation is bit-exact against P1-generated
golden FP16 values for `K=1..4`, and every case consumes the same four packed
weight beats. Production-geometry OOC synthesis checks the 128-lane wrapper and
the exact expected IP instance topology; its shell timing report excludes the
pre-synthesized floating-point black boxes and is therefore a structural, not
full-path, timing result. See `evidence/p2b-fp16-backend-20260911.md`.

## P3 implementation status

P3 adds a transformer-level controller above P2. Each layer issues Q/K/V,
waits for attention, issues O and G/U, waits for the MLP activation, and issues
D; the final layer is followed by LM head. Runtime K travels with every
descriptor while the number of target-weight projections remains independent
of K. A P3-to-P2 adapter locks tensor/layer identity from configuration accept
through P2 completion. See
`evidence/p3-transformer-projection-scheduler-20260911.md`.

## P4 implementation status

P4-A implements causal tiled access control for committed DDR KV and tentative
on-chip KV. Each query streams only the committed prefix in 64-token tiles,
then exposes tentative candidates `[0..q]`; ping/pong buffer identity and
response metadata are checked before advancing. Arithmetic attention and its
numerical reference remain the next P4 sub-gate, so P4 is still in progress.
See `evidence/p4-causal-kv-tiles-20260911.md`.
