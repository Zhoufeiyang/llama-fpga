# PS speculative runtime

`spec_runtime.c` is a freestanding C99 transaction controller for the KV260
AXI-Lite speculative interface. Platform-specific code supplies MMIO, draft,
and token-output callbacks. The state sequence is:

`DRAFT -> TARGET_VERIFY -> READ_TARGET_RESULTS -> ACCEPT_OR_REJECT -> COMMIT_POINTER -> EMIT_TOKENS -> ACK_RESULTS`.

The draft callback is model-independent. The tests exercise deterministic,
n-gram, and INT8 tiny-MLP callbacks without changing the transaction FSM.

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

The current adapter writes all K candidate IDs, enables speculative addressing,
and pulses START exactly once. The PL token ingress snapshots the block and
carries q as hardware metadata; software performs one wait for the complete
K+1 result block. This closes the former sequential compatibility launcher.
Full target-weight amortization still depends on the remaining P3 projection
command integration and is not inferred from the launch protocol alone.

`spec_runtime_metrics_t` provides passive cumulative counters for transactions,
drafted/target/accepted/emitted tokens, all-match and mismatch outcomes,
rollback, result polling, and output backpressure. Use
`spec_runtime_get_metrics()` for board logging and
`spec_runtime_reset_metrics()` at a measurement boundary.

The same metrics record accumulates the production PL counters sampled after
each complete `K+1` target-result block: target-weight bytes, KV read/write
bytes, verification-window cycles, and target-memory stall cycles.  These are
read from `0x180`–`0x190` before result acknowledgement and are kept separate
from PS software counters.

`draft_model.c` implements a fixed-capacity trigram/lookup draft in the same
16-bit tokenizer-ID space as the target. It performs no allocation, saturates
observation counts, uses deterministic token-ID tie breaking, and exposes a
runtime-compatible callback. A 4096-entry table occupies 49,152 bytes.

`memory_budget.py` places the target images and conservative K=4 runtime
buffers into the two KV260 DDR ranges, checks every interval for overlap, and
records the remaining tail. It intentionally reserves activation, Gate/Up,
tentative KV, metadata, and a 4 MiB runtime guard in addition to the n-gram
table.

`tiny_draft.c` adds a runtime-compatible no-heap INT8 tiny-MLP callback and a
strict versioned/CRC-checked model loader. `run_p7f_tiny_draft.ps1` regenerates
the deterministic 512,064-byte test model, compares every generated C logit
against NumPy, and compiles an AArch64 freestanding entry object. Its weights
are synthetic validation vectors, not a trained language model.
