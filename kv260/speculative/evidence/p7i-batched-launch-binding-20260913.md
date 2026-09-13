# P7I batched launch binding 20260913

## Verdict

`P7 batched PS to PL launch protocol sub-gate: GO`

`P7 live-board sub-gate: IN PROGRESS`

No synthesis, implementation, bitstream generation, or board programming was
run for this sub-gate.

## Production change

The PS runtime now writes speculative attention enable and committed length
before pulsing START. START snapshots K and all candidate IDs once. The Xilinx
launcher no longer loops over candidates or writes the legacy token register;
it waits once for the complete K+1 target-result block. Candidate q travels in
the token Stream sideband and is frozen when the command generator accepts the
token, so queued candidates cannot change active attention or KV addresses.

Committed length now represents 0 through 1024 inclusive. A block beginning at
1020 with K=4 legally uses physical slots 1020 through 1023 and commits length
1024; any candidate position at or above 1024 faults.

## Acceptance evidence

```text
SPECULATIVE_TOKEN_INGRESS_GO ordered=3 q_metadata=1 backpressure=1 disable_quiet=1 invalid_k=1
SPECULATIVE_TOKEN_CONTEXT_GO Q_HELD_UNTIL_ACCEPT=1 ROUTE_TAG_COMPATIBLE=1 LEGACY_NONDESTRUCTIVE=1
P5C_POSITION_SELECTOR_GO LEGACY=1 K1_TO_K4=1 LAST_SLOT_1023=1 OVERFLOW=1
P5B_LAST_CONTEXT committed=1024
P5B_AXILITE_POINTER_CONTROL_GO ... LAST_CONTEXT_1024=1 ...
P7A_PS_RUNTIME_GO K1_TO_K4=1 ... TARGET_FALLBACK=1 ...
P7E_RUNTIME_100_GO K1_TO_K4=1 TARGET_ONLY_EQUIVALENCE=1 TIMEOUT_FALLBACK=1
P7B_BAREMETAL_BUILD_GO ARCH=AARCH64 MMIO_ADAPTER=1 INITIAL_TARGET=1 BATCH_START_ONCE=1 Q_METADATA_HW=1 NGRAM_BOUND=1 TARGET_FALLBACK=1
```

The refreshed freestanding AArch64 artifact is
`build/p7b_baremetal/p7b_baremetal_smoke.elf`, 119,040 bytes, SHA256
`761ae66e5f27254297fc21de8f988066004f42b99aadf427202c87057eecc2ea`.

The complete `EdgeLLMInst` Scala source elaborates with zero errors. This gate
proves the one-start control and metadata path; it does not yet prove one
target-weight DMA pass per projection, which remains a P3 production command
scheduler requirement.
