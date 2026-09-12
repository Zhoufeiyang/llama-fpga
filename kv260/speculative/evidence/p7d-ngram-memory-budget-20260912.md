# P7-D n-gram draft and DDR budget — 2026-09-12

## Verdict

`P7-D n-gram draft sub-gate: GO`

`P7-D static DDR-budget sub-gate: GO`

`P7 real-model and live-board gates: IN PROGRESS`

## Draft implementation

`draft_model.c` implements a fixed-capacity trigram lookup model over the same
unsigned 16-bit token IDs consumed by the target accelerator. Training is
online from accepted token sequences, uses no heap allocation, saturates entry
counts, and selects the most frequent continuation with token-ID tie breaking.
Unknown contexts return a configured deterministic fallback token.

A 4096-entry table occupies 49,152 bytes. Host tests cover learned and unknown
contexts, reset, deterministic selection, exact storage sizing, and 100
continuous generated tokens in the target vocabulary range. Both host and
freestanding ARM compilation pass.

```text
P7D_NGRAM_DRAFT_GO TOKEN_IDS=UINT16 FIXED_STORAGE=1 BYTES_4096=49152 DETERMINISTIC_TIEBREAK=1 CONTINUOUS_100=1
```

## Static memory budget

The budget uses the approved target image sizes:

- `llama0.bin`: 2,109,472,768 bytes at high DDR base `0x800000000`;
- `llama1.bin`: 1,915,437,056 bytes at low DDR offset `0x00036000`.

It conservatively reserves 5,533,696 bytes after the low target region for the
4096-entry n-gram table, K=4 FP16 activation storage, simultaneous Gate/Up
storage, 1 MiB tentative KV, candidate metadata, and a 4 MiB runtime guard.
The complete linker-defined A53 application window
`0x73000000..0x7ff00000` is reserved rather than treating it as free storage.
Every interval is checked for overlap and DDR bounds. After runtime reserves,
8,187,904 bytes remain between the low allocations and the A53 window;
38,010,880 bytes remain after the high target image.

```text
P7D_MEMORY_BUDGET_GO TARGET_BYTES=4024909824 RUNTIME_RESERVED=5533696 LOW_TAIL=8187904 OVERLAPS=0
```

Artifacts:

- draft test log: 112 bytes, SHA256
  `34707b7d660fc07a9a9b0d3b9c2f0ff29a8383a375900532e43c1e1ea637dbe7`;
- ARM draft object: 3,056 bytes, SHA256
  `aa0456cc9d50b34e1cbebe86b304654b78b21e4a68150502377eb79773bff9e0`;
- linker-aware memory report: 1,236 bytes, SHA256
  `3e060265b5a683c607b1c9396920d5556f548015307b359a95a820b41c7297db`.

This proves the deterministic and lookup draft stages fit the static budget.
It does not claim that an unselected neural draft model fits or meets the
required acceptance rate; a concrete quantized model artifact and tokenizer
compatibility test remain required for that claim.
