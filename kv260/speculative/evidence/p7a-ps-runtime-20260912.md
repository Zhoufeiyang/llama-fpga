# P7-A PS speculative transaction runtime — 2026-09-12

## Verdict

`P7-A deterministic-draft runtime sub-gate: GO`

`P7: IN PROGRESS`

## Actual product

`runtime/spec_runtime.c` is a freestanding C99 state machine that consumes the
production P6-B AXI-Lite contract. It performs deterministic draft callback
steps, writes K, candidate IDs, and the existing target prediction `g[0]`,
launches candidate verification through a platform callback, waits for K+1 retained
target results, applies first-mismatch or all-match acceptance, commits only
the accepted KV prefix, drains verified tokens with output backpressure, and
acknowledges the result block.

Timeout and PL-fault paths issue pointer rollback and result acknowledgement.
Descriptor validation rejects K outside 1..4 and KV ranges beyond token 1023.
MMIO, verification launch, draft, and emission are callbacks so the same
controller can be used in the Vitis bare-metal application and host tests
without duplicating protocol logic.

## Verification

The host test drives the complete FSM through a fake MMIO register file. It
covers every mismatch position and all-match for K=1..4, validates the exact
candidate/start/commit/ack register values, injects output backpressure, and
checks timeout and PL-fault rollback. The source is compiled both as a strict
host C99 executable and a freestanding Vitis ARM object.

```text
P7A_K1 mismatches_0_to_0_and_all_match=passed
P7A_K2 mismatches_0_to_1_and_all_match=passed
P7A_K3 mismatches_0_to_2_and_all_match=passed
P7A_K4 mismatches_0_to_3_and_all_match=passed
P7A_PS_RUNTIME_GO K1_TO_K4=1 BACKPRESSURE=1 TIMEOUT_ROLLBACK=1 PL_FAULT_ROLLBACK=1
```

Tools: host GCC 13.3.0 and Vitis ARM GCC 11.2.0.

```powershell
& .\kv260\speculative\runtime\run_tests.ps1
```

This sub-gate proves the transaction controller and ARM compilation. P7-B adds
the Xilinx MMIO binding; live board execution and P7-C publication metrics
remain. No full Vivado implementation was rerun for P7-A.
