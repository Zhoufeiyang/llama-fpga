# PS speculative runtime

`spec_runtime.c` is a freestanding C99 transaction controller for the KV260
AXI-Lite speculative interface. Platform-specific code supplies MMIO, draft,
and token-output callbacks. The state sequence is:

`DRAFT -> TARGET_VERIFY -> READ_TARGET_RESULTS -> ACCEPT_OR_REJECT -> COMMIT_POINTER -> EMIT_TOKENS -> ACK_RESULTS`.

The initial draft callback is deliberately model-independent. The included
test uses a deterministic next-ID stub; a later board adapter can replace it
with an n-gram, lookup, or neural draft without changing the transaction FSM.

Run the host behavioral suite and Vitis ARM compile check from the repository
root:

```powershell
& .\kv260\speculative\runtime\run_tests.ps1
```

Build and audit the Xilinx A53 MMIO adapter against the existing standalone
BSP with:

```powershell
& .\kv260\speculative\runtime\run_p7b_baremetal_build.ps1
```

The current adapter deliberately launches one candidate at a time and holds q
until its LM-head event arrives. This is the safe board-bring-up path for the
existing token ingress. It validates end-to-end control semantics, not the
final bandwidth-amortized batch-launch performance claim.
