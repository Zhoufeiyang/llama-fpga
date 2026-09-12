# P7-B Xilinx bare-metal binding — 2026-09-12

## Verdict

`P7-B A53 software-binding sub-gate: GO`

`P7-B live-board sub-gate: IN PROGRESS`

`P7: IN PROGRESS`

## Implemented binding

`spec_runtime_xilinx.c` binds the P7-A transaction FSM to `Xil_In32` and
`Xil_Out32` at the 64-bit PL base address. It reads the valid legacy argmax as
`g[0]`, clears the legacy latch, and launches each candidate with the
speculative attention window set to the frozen committed length and q.

The compatibility launcher holds q until that candidate increments the
production target-result count. This is necessary because the present token
ingress captures the KV write slot but still exposes q globally to the causal
attention path. Timeout or sticky PL fault returns failure to the FSM, which
then issues rollback and result acknowledgement.

P6-B was correspondingly hardened with register `0x138`. On a valid start the
production control block atomically copies this initial target into
`target[0]` and sets result count to one; the following K LM-head events fill
`target[1..K]`.

## Verification

Generated production `AxiLiteCtrl.v` passes focused Vivado xsim:

```text
P6B_AXILITE_RESULTS_GO CANDIDATES=4 TARGETS=5 INITIAL_TARGET=1 HOLD_UNTIL_ACK=1 OVERWRITE_BLOCKED=1
```

The Xilinx adapter, runtime, and smoke entry point compile and link against the
existing standalone A53 BSP. ELF audit confirms ELF64/AArch64 and the global
adapter initialization symbol:

```text
P7B_BAREMETAL_BUILD_GO ARCH=AARCH64 MMIO_ADAPTER=1 INITIAL_TARGET=1 SEQUENTIAL_SAFE_LAUNCH=1
Class: ELF64
Machine: AArch64
spec_xilinx_runtime_init
```

The ELF is 118,696 bytes with SHA256
`bac498ccf94e09d0e46357a53e6338135e39f2471d9b113d012f3c25762bf54e`.
It was linked with BSP `libxil.a` SHA256
`f2cded302b15756ea2f7a84c751d1abeb70373714129571c3662a74dd4e3d36d`
and linker script SHA256
`20eef33ad6b21f6b362ba4a4f84723102f459e163333d8fad1a327d2a46f7d1f`.

```powershell
& .\kv260\speculative\runtime\run_tests.ps1
& .\kv260\speculative\runtime\run_p7b_baremetal_build.ps1
& .\kv260\speculative\rtl\run_p6b_axilite_xsim.ps1
```

This proves source integration, ABI compatibility, linkability, and the
control-register protocol. It does not prove live PL behavior or the final
GEMM bandwidth-amortization claim. The next gate is a board run that records
`g[0]`, result-count progression, accepted count, committed pointer, fault bit,
and raw emitted token IDs. No full Vivado implementation was run here.
