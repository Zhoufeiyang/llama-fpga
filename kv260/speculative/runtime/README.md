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
