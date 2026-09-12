# P4-E production attention completion adapter — 2026-09-12

## Scope

P4-E removes the former single-`valid` completion shortcut from the
speculative attention controller boundary.  The adapter observes the real
production stream boundaries:

- QK completes only after `tokenCount` reduced-score events;
- softmax completes only on `output.valid && output.last`;
- V-AXPY completes only on the expected final reduced element with
  `Fragment.last`.

Each accepted tile locks phase, DDR/tentative source, ping/pong buffer, query,
and start-token identity.  The controller rejects a completion whose locked
identity differs, while early/late V `last` and unsolicited softmax `last`
produce sticky adapter faults.

## Focused Vivado simulation

```text
P4_SPINAL_CONTROLLER_GO K1_TO_K4=1 BUFFER_FAULT=1
P4_COMPLETION_ADAPTER_GO QK_COUNTED=1 SOFTMAX_LAST=1 V_LAST=1 METADATA=PHASE,SOURCE,BUFFER,QUERY,START EARLY_V_FAULT=1 UNSOLICITED_SOFTMAX_FAULT=1
```

The controller test covers K=1..4 with a 130-token committed prefix, forcing
three DDR tiles and one tentative tile in both QK and V phases for every
query.  The adapter test independently verifies the exact stream-boundary
rules and metadata returned to the controller.

## Gate decision

`P4-E completion adapter source and focused xsim: GO`

The production `AttnSubMod` now consumes real QKMul and softmax events and
exposes the type-correct V-AXPY completion input.  Full P4 production closure
still requires DataPath to connect that V input to the correct
`MulAddSGNew.scalarOut` transaction and the KV tile requester to drive real DDR
command/response traffic.  No physical KV read-amortization number is claimed
at this gate.

