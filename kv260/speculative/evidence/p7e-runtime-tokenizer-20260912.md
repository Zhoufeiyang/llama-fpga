# P7-E continuous runtime, fallback, and tokenizer contract — 2026-09-12

## Gate status

`P7-E tokenizer-compatibility sub-gate: GO`

`P7-E 100-token host runtime sub-gate: GO`

`P7-E target-only fallback sub-gate: GO`

`P7 live-board and neural-draft gates: IN PROGRESS`

No Vivado synthesis or implementation was run for this software gate.

## Implemented path

- `spec_runtime` can recover from draft failure, verification launch failure,
  timeout, or PL fault. It rolls back tentative KV/result state, emits the
  already target-computed `g[0]`, and completes the transaction so the outer
  decode loop can continue.
- The A53 adapter zero-initializes its complete configuration, explicitly
  enables fallback, and links a fixed-storage 4096-entry n-gram callback
  instead of the former arithmetic stub.
- `test_spec_decode_100.c` drives the actual runtime state machine, MMIO
  contract, n-gram predictor, target result interface, acceptance, commit,
  rollback, and emission path for exactly 100 continuous tokens at K=1..4.
- The K=2 run injects a result timeout at token 100 and proves target-only
  recovery without sequence drift.

## Reproduction

```powershell
& .\kv260\speculative\runtime\run_tests.ps1
& .\kv260\speculative\runtime\run_p7b_baremetal_build.ps1
$env:PYTHONPATH='D:\JSA paper\work\codex_tools\pydeps'
python .\kv260\speculative\runtime\verify_tokenizer_compatibility.py `
  .\au250\tkz.bin `
  'D:\JSA paper\work\draft_llama68m\tokenizer.model' `
  --output .\kv260\speculative\build\p7e_tokenizer_compatibility.json
```

Observed markers:

```text
P7E_K1_END_TO_END_GO TOKENS=100 NGRAM_CALLBACK=1 FALLBACKS=0 SEQUENCE_EQUIVALENT=1
P7E_K2_END_TO_END_GO TOKENS=100 NGRAM_CALLBACK=1 FALLBACKS=1 SEQUENCE_EQUIVALENT=1
P7E_K3_END_TO_END_GO TOKENS=100 NGRAM_CALLBACK=1 FALLBACKS=0 SEQUENCE_EQUIVALENT=1
P7E_K4_END_TO_END_GO TOKENS=100 NGRAM_CALLBACK=1 FALLBACKS=0 SEQUENCE_EQUIVALENT=1
P7E_RUNTIME_100_GO K1_TO_K4=1 TARGET_ONLY_EQUIVALENCE=1 TIMEOUT_FALLBACK=1
P7B_BAREMETAL_BUILD_GO ARCH=AARCH64 MMIO_ADAPTER=1 INITIAL_TARGET=1 SEQUENTIAL_SAFE_LAUNCH=1 NGRAM_BOUND=1 TARGET_FALLBACK=1
P7E_TOKENIZER_GO VOCAB=32000 PIECE_MISMATCHES=0 SCORE_MISMATCHES=0 UNK=0 BOS=1 EOS=2
```

## Audited artifacts

- 100-token test log: 412 bytes, SHA256
  `97bf247869cf0bbb8ffeb4ea31a5ef2a64dfe95e014714c6b1141ed23a76faa1`
- tokenizer report: 291 bytes, SHA256
  `0f45e0c720bb701939bfab7a2d6c63ffc86ce9482bada72a8db9039534e8ade9`
- A53 smoke ELF: 119,040 bytes, SHA256
  `c84741f8cfc1e8bac3643d1670052e44bf712c3da5286dc19611e0c20c948fbe`

The selected tokenizer is used only to establish ID compatibility here. This
gate does not claim a neural draft model, acceptance rate, bandwidth reduction,
or live-board performance.
