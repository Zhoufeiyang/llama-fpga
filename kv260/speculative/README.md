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

`P2 standalone and production shared GEMV/GEMM source/numerical gates: GO`

`P3 transformer projection scheduler sub-gate: GO`

`P3 P2-adapter sub-gate: GO`

`P3-B production batch-descriptor/control sub-gate: GO`

`P3 production projection sequencing: IN PROGRESS`

`P4 causal KV tile scheduler sub-gate: GO`

`P4-B numerical-reference sub-gate: GO`

`P4-B attention-phase-controller sub-gate: GO`

`P4-B K-independent historical-tile fetch schedule sub-gate: GO`

`P4-C production-attention source-integration sub-gate: GO`

`P4-C generated-RTL sub-gate: GO`

`P4-A production softmax numerical sub-gate: GO`

`P4-A production QK numerical sub-gate: GO`

`P4-A V weighted-accumulation numerical sub-gate: GO`

`P4-D production tile-control boundary sub-gate: GO`

`P4-E production completion-adapter source/xsim sub-gate: GO`

`P4 production KV/softmax/V-AXPY completion hookup: IN PROGRESS`

`P5: IN PROGRESS`

`P5-A pointer-commit RTL sub-gate: GO`

`P5-A metadata-line RMW sub-gate: GO`

`P5-B production AXI-Lite control sub-gate: GO`

`P5-C production KV data-address sub-gate: GO`

`P5-D production metadata-position sub-gate: GO`

`P5-E retained metadata-line sub-gate: GO`

`P5 source-level address/metadata sub-gates: GO`

`P5 production transaction-completion gating: IN PROGRESS`

`P5-F transaction-active/address-freeze sub-gate: GO`

`P6 software acceptance/result-buffer source: GO`

`P6 refreshed production RTL/board sub-gate: IN PROGRESS`

`P6-A greedy-acceptance RTL sub-gate: GO`

`P6-A sequence-equivalence reference sub-gate: GO`

`P6-B production result-buffer sub-gate: GO`

`P7: IN PROGRESS`

`P7-A deterministic-draft PS runtime sub-gate: GO`

`P7-B A53 software-binding sub-gate: GO`

`P7-B live-board sub-gate: IN PROGRESS`

`P7-C passive runtime-metrics sub-gate: GO`

`P7-C board-measurement sub-gate: IN PROGRESS`

`P7-D n-gram draft sub-gate: GO`

`P7-D static DDR-budget sub-gate: GO`

`P7-E tokenizer-compatibility sub-gate: GO`

`P7-E 100-token host runtime sub-gate: GO`

`P7-E target-only fallback sub-gate: GO`

`P7-F INT8 draft format/numerical/ARM sub-gate: GO`

`P7-G production performance-counter source/register sub-gate: GO`

`P7-G board performance-measurement sub-gate: IN PROGRESS`

`P7-H PS hardware-metrics binding sub-gate: GO`

`P7-I single-start batched candidate ingress sub-gate: GO`

`P7 real quantized draft sub-gate: IN PROGRESS`

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

The production `MulAddSGNew` path now accepts a K×B activation tile, buffers
each physical B-beat weight row and its 32-bit post-scales once, and replays
both in row/token/beat order through the shared multiplier, reduction, and
FP32 accumulation infrastructure.  The internal effective row bound is 18
bits, so LM-head K=3/4 does not wrap the legacy 16-bit cfg field.  Focused
XSim passes K=1..4 on the actual Xilinx FP16 engine, and the complete
`EdgeLLMInst` source elaborates successfully. See
`evidence/p2-production-shared-replay-20260912.md`.

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
response metadata are checked before advancing.

P4-B adds a batch attention phase controller that sequences the existing QK,
stable-softmax, and V-accumulation interfaces with the committed tile as the
outer loop. Each historical DDR tile is fetched for query 0 and replayed from
the selected ping/pong buffer for the remaining queries; the tentative tail
remains query-dependent. Its NumPy oracle proves tiled/dense equivalence for
K=1..4, future-candidate isolation, INT8 dequantization, and tentative-only
startup. Focused RTL simulation with a 130-token prefix observes three DDR
fetches per phase for every K=1..4. Physical requester integration remains
open. See
`evidence/p4-causal-kv-tiles-20260911.md` and
`evidence/p4b-attention-phase-controller-20260911.md`, plus
`evidence/p4f-k-independent-tile-reuse-20260913.md`.

P4-C begins the production hookup. AXI-Lite register `0x28` now propagates
speculative enable, committed length, and query index through `DataPath_xN`
to the real `AttnSubMod`. The existing QKMul-to-SerialSafeSoftmax-to-V-AXPY
path is reused, while the softmax inclusive last index selects either legacy
`status.token` or speculative `committed + q`. The full Scala project compiles
with zero errors, and top-level RTL elaboration proves the register-to-softmax
and softmax-to-V-AXPY connections. Vendor-IP softmax simulation passes finite,
causal-length, and normalization checks for K=1..4. Production QK and focused V
weighted-accumulation simulations use the actual Xilinx FP16 multiplier and
accumulator models and pass for K=1..4, including bit-exact K=1 behavior. These
results close the arithmetic and source-elaboration sub-gates; the physical
KV requester and V-AXPY terminal handshake remain open. See
`evidence/p4c-production-attention-hookup-20260911.md` and
`evidence/p4a-vendor-qk-v-20260912.md`.

## P5 implementation status

P5-A adds a synthesizable transaction boundary for tentative KV writes. It
freezes `spec_base_token`, translates candidate slots to future DDR token
addresses, and keeps the attention-visible length equal to the committed
pointer. Accepting a prefix advances only the pointer; rejection, abort, and
timeout leave the committed pointer unchanged and make the tail eligible for
overwrite without a physical rollback copy.

A companion 64-byte metadata-line merge primitive preserves old committed
scale/zero entries while updating candidate entries across the 15/16 and 31/32
packing boundaries. Self-checking Vivado xsim covers K=1..4, accepted counts
0..4, address/context bounds, abort, timeout, repeated tentative-slot reuse, and
partial metadata lines. P5 remains in progress until the manager is connected
to the production KV write path. See
`evidence/p5a-pointer-commit-20260912.md`.

P5-B adds the planned production registers at `0x100`–`0x130`. Start freezes
the base, commit advances `COMMITTED_LEN` by the accepted prefix, rollback
restores only the speculative pointer, and illegal K/commit requests set a
sticky fault. Generated production RTL passes a self-checking AXI-Lite xsim
test, and the committed register already drives the P4 attention visibility
path. The remaining P5 gate is explicit integration with production KV write
addresses and metadata commands. See
`evidence/p5b-production-control-20260912.md`.

P5-C connects `committed + q` to the real KV read and write command generator.
The selected write slot is captured with the token launch and remains stable
for all layer/head commands. Speculative candidates no longer advance the
legacy physical write counter, and target-only fallback resumes at the accepted
pointer. Focused xsim covers legacy and q=0..3 selection, while complete-top
elaboration proves the selectors and FIFO are present in production RTL. P5
remains open only for partial scale/zero metadata RMW integration. See
`evidence/p5c-production-kv-address-20260912.md`.

P5-D makes the production `KvScaleZeroPacker` seek its K/V metadata insertion
and line-flush state from the same explicit physical token position. This fixes
rollback/replay within an unflushed 16-entry line and passes focused plus
complete-top elaboration. The final P5 blocker is the case where a rejected
candidate at position 15 or 31 has already emitted the line: production must
retain or reread the old line before overwriting that slot. See
`evidence/p5d-production-metadata-position-20260912.md`.

P5-E retains the most recently emitted production metadata line in the existing
K/V line FIFOs. Self-checking generated-RTL simulation proves rejected
line-ending candidates can be replayed at positions 15 and 31 while all fifteen
committed neighbours remain bit-identical. Together with P5-A through P5-D,
this closes the P5 pointer-commit gate. See
`evidence/p5e-retained-metadata-line-20260912.md`.

P5-F separates feature enable from an active speculative transaction and
freezes base, K, and epoch at START. Production KV addressing now uses the
frozen base only inside that epoch; commit is accepted only after all K+1
target results are present, while rollback prevents late results from entering
a later transaction. Focused AXI-Lite xsim and complete-top elaboration pass.
See `evidence/p5f-production-transaction-epoch-20260913.md`.

## P6 implementation status

P6-A implements ordered collection of `g[0..K]`, first-mismatch detection,
correction-token output, all-match bonus output, and commit delta generation.
Results remain stable until consumed, and timeout/abort requests rollback with
no commit. Self-checking xsim covers every mismatch and all-match for K=1..4.
A Python sequence oracle additionally proves 100-token equality with
target-only greedy decoding under an imperfect deterministic draft. P6-B
connects that protocol to production control: four candidates are stored, five
ordered LM-head argmax results are retained behind AXI-Lite, and an explicit
acknowledgement prevents unread results from being overwritten. See
`evidence/p6a-greedy-acceptance-20260912.md` and
`evidence/p6b-production-results-20260912.md`.

## P7 implementation status

P7-A adds a freestanding C99 PS transaction controller over the production
AXI-Lite interface. Its callback-based state machine executes draft, target
verification, result collection, greedy acceptance, pointer commit, verified
token emission, and result acknowledgement. Host tests cover K=1..4, every
mismatch and all-match, output backpressure, timeout rollback, PL-fault
rollback, and invalid descriptors; Vitis ARM GCC also compiles the controller
with warnings promoted to errors.

P7-B adds the Xilinx A53 MMIO adapter and closes the missing `g[0]` contract:
PS writes the already-available target argmax at `0x138`, start seeds result
slot zero, and K following LM-head events complete the verification block. A
safe compatibility launcher holds each q through its result event. The adapter
links against the existing standalone BSP as an audited ELF64/AArch64 image.
Live-board validation and publication measurements remain. See
`evidence/p7a-ps-runtime-20260912.md` and
`evidence/p7b-baremetal-binding-20260912.md`.

P7-C adds passive cumulative runtime counters for transactions, drafted and
target tokens, accepted prefixes, emitted tokens, all-match/mismatch outcomes,
rollback, result polling, and output backpressure. Unit tests verify exact
counter values for every K and acceptance path. Live-board timing, traffic,
power, and throughput values remain unclaimed until measured. See
`evidence/p7c-runtime-metrics-20260912.md`.

P7-D adds a fixed-capacity trigram/lookup draft with deterministic fallback in
the target's 16-bit tokenizer-ID space. A 4096-entry instance uses 49,152 bytes
and passes a 100-token continuous-generation test. A static interval audit
places the 4,024,909,824-byte target, conservative K=4 buffers, tentative KV,
metadata, and runtime guard without overlap. The linker-aware budget reserves the complete A53
window at `0x73000000..0x7ff00000`; including the P7-F INT8 model, it leaves 7,675,840 bytes before that
window and 38,010,880 bytes in high DDR. A concrete neural draft artifact and its measured acceptance
rate remain open. See `evidence/p7d-ngram-memory-budget-20260912.md`.

P7-E binds the n-gram callback into the A53 build, adds target-only recovery
after verification timeout or PL fault, and verifies the complete draft,
target-result, acceptance, rollback, commit, and emission FSM for 100
continuous tokens at K=1..4. Every emitted stream is identical to a
target-only greedy oracle; K=2 additionally injects a timeout and resumes via
the known target `g[0]`. A full 32,000-entry comparison also proves that the
selected Llama-compatible draft tokenizer has identical token IDs, normalized
pieces, scores, and special-token IDs to `au250/tkz.bin`. The live-board and
real neural-draft gates remain open. See
`evidence/p7e-runtime-tokenizer-20260912.md`.

P7-F implements a bounded INT8 tiny-MLP draft primitive over the shared
32,000-token ID space. Its 512,064-byte binary has a versioned header,
dimension/offset/length checks, positive finite scales, and payload CRC32.
The no-heap C greedy result is exactly equal to the NumPy reference for all
32,000 logits in the acceptance vectors, and the module compiles as an
AArch64 freestanding object. The runtime-compatible callback consumes the
latest prefix token. This is a numerical/format implementation built from
deterministic synthetic weights; it is not represented as a trained Llama
draft and no acceptance-rate claim is made. See
`runtime/P7F_TINY_DRAFT_FORMAT.md` and
`evidence/p7f-tiny-int8-draft-20260912.md`.

P7-G adds passive 64-bit production counters for target-weight bytes, KV read
and write bytes, verification-active cycles, and target-memory stall cycles.
Their low words are readable at AXI-Lite `0x180`–`0x190`; focused xsim verifies
event scaling, active-window gating, clearing, and all five register mappings.
These counters are instrumentation only and do not influence any ready/valid
path.  Board-derived performance values remain unclaimed until the final
source integration, one-time hardware build, and live measurement.  See
`evidence/p7g-production-performance-counters-20260912.md`.

P7-H samples those five PL counters after each complete `K+1` result block and
accumulates them into 64-bit PS runtime totals before acknowledging the result
buffer. Host K=1..4 tests check exact values, the 100-token equivalence suite
still passes, and the A53 freestanding ELF rebuild succeeds. See
`evidence/p7h-runtime-hardware-metrics-20260912.md`.

P7-I removes the sequential PS candidate-launch loop. Software now enables the
speculative window and pulses START once after writing K candidate IDs; the PL
snapshots and emits the ordered block with q metadata held across each active
token transaction. The 1024-token endpoint is represented without truncation,
and the final physical slots 1020 through 1023 pass focused tests. See
`evidence/p7i-batched-launch-binding-20260913.md`.
