# P3C production batch command collector (2026-09-13)

## Verdict

`P3 K-row embedding-command collection sub-gate: GO`

`P3 projection segment-grant sub-gate: IN PROGRESS`

No synthesis, implementation, or board programming was run.

## Production change

`GenMemCmdLenAlign` no longer advances from the token/embedding command segment
after candidate q=0. During an active speculative epoch it counts only actual
MM2S command handshakes and advances to the layer-weight sequence on the Kth
accepted command. Epoch changes or rollback discard a partial group. The four
embedding rows therefore enter the shared activation path as one transaction.

`DataPath` now injects only q=0 into the legacy transformer state queues. The
remaining q rows still generate their physical embedding reads but cannot add
extra token/layer contexts. Legacy decode and K=1 retain the original single
command transition.

## Acceptance evidence

```text
P3C_BATCH_COLLECT_K1 accepted=1
P3C_BATCH_COLLECT_K2 accepted=2
P3C_BATCH_COLLECT_K3 accepted=3
P3C_BATCH_COLLECT_K4 accepted=4
P3C_BATCH_COMMAND_COLLECTOR_GO K1_TO_K4=1 ADVANCE_ON_ACCEPTED_KTH=1 EPOCH_ABORT=1 INVALID_K=1
```

The complete `EdgeLLMInst` source elaborates with zero errors. This gate proves
the production entry boundary for a K-row activation tile. It does not yet
prove that every Q/K/V/O/G/U/D/LM command segment is descriptor-granted or that
row-major GEMM results are consumed by all downstream stages.
