# P4-H packed KV metadata requester — 2026-09-14

`P4-H packed scale/zero requester sub-gate: GO`

`P4 production response-owner integration: IN PROGRESS`

## Delivered artifact

`attn.P4KvMetadataRequester` converts each P4 tile descriptor into an aligned
72-bit logical DataMover command for the packed 32-bit KV scale/zero table. It
aligns the first token to the containing 512-bit line, extracts only the
requested entries, crosses line boundaries without exposing padding entries,
and stores the result in the selected ping/pong BRAM. A `fetch=0` request
replays the same logical tile without another DDR command.

Base address and command tag are captured with the request. The intended
production tag is `2`; address expansion to the KV260 40-bit physical map
remains downstream in the existing `AddressRemap`.

## Acceptance evidence

Generated-RTL Vivado xsim:

```text
P4H_UNALIGNED_FETCH_GO START=130 COUNT=3 ALIGNED=128 BYTES=64
P4H_REPLAY_GO ITEMS=3 DDR_COMMANDS=1
P4H_CROSS_LINE_GO START=143 COUNT=4 DDR_LINES=2
P4H_KV_METADATA_REQUESTER_GO PACKED32=1 UNALIGNED=1 CROSS_LINE=1 REPLAY_NO_DDR=1 BACKPRESSURE_SAFE=1 TAG2=1
```

The self-checking test applies downstream metadata backpressure and verifies
request identity at completion. No synthesis or implementation run was made.

## Remaining production gate

The metadata and value requesters must be sequenced through a single command
arbiter and a response-ownership queue, then connected to the existing
scale/zero and int8 value consumers. Rollback must explicitly quarantine any
old-epoch MM2S payload before the next speculative transaction starts.
