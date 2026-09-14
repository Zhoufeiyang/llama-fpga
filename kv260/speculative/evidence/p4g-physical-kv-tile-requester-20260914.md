# P4-G physical KV tile requester — 2026-09-14

`P4-G DDR value-tile requester sub-gate: GO`

`P4 production KV/scale-zero frontend integration: IN PROGRESS`

## Delivered artifact

`attn.P4KvTileRequester` is a synthesizable transport between the P4 tile
descriptor and the existing logical DataMover boundary. A fetched historical
tile emits exactly one 72-bit logical DataMover command, is forwarded under
normal ready/valid backpressure, and is stored in the selected ping/pong BRAM.
A later request with `fetch=0` replays the selected BRAM and emits no DDR
command. Completion is generated only with the last accepted output beat and
echoes phase/source/buffer/query/start identity.

The requester latches its base address, SplitAxiDatamover stripe tag, and local
bus tag at request acceptance. The generated logical command therefore remains
compatible with the production 32-to-40-bit `AddressRemap` rather than bypassing
the KV260 physical address mapping. The P4 phase controller was also corrected
to express tentative `startToken` as the absolute frozen committed position,
preventing candidate tails from reading physical slot zero.

## Acceptance evidence

Focused generated-RTL xsim:

```text
P4G_FETCH_GO beats=8 commands=1
P4G_REPLAY_GO beats=8 commands=1
P4G_KV_TILE_REQUESTER_GO DDR_FETCH_ONCE=1 PING_PONG=1 REPLAY_NO_DDR=1 BACKPRESSURE_SAFE=1 COMPLETION_IDENTITY=1
```

The K=1..4 phase-controller regression also passes after the absolute tentative
address correction:

```text
P4B_TILE_REUSE_K1 qk_ops=4 v_ops=4 qk_ddr=3 v_ddr=3
P4B_TILE_REUSE_K2 qk_ops=8 v_ops=8 qk_ddr=3 v_ddr=3
P4B_TILE_REUSE_K3 qk_ops=12 v_ops=12 qk_ddr=3 v_ddr=3
P4B_TILE_REUSE_K4 qk_ops=16 v_ops=16 qk_ddr=3 v_ddr=3
P4_SPINAL_CONTROLLER_GO K1_TO_K4=1 DDR_TILE_READS_K_INDEPENDENT=1 BUFFER_FAULT=1
```

No synthesis or implementation run was performed.

## Remaining production gate

P4 is not yet globally GO. The value requester must be composed with aligned
scale/zero tile reads, connected ahead of the existing int8 dequantization
path, arbitrated with legacy commands before `AddressRemap`, and driven from
the real DataMover response owner. QK, softmax, and V-AXPY terminal events must
then close the controller without synthetic completion pulses.
