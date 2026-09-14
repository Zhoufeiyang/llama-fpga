# P4-I serialized KV fetch frontend — 2026-09-14

`P4-I command/response ownership sub-gate: GO`

`P4 production DataPath hookup: IN PROGRESS`

`attn.P4KvFetchFrontend` composes the packed metadata and raw value requesters
behind one 72-bit logical command port and one 512-bit response port. A
registered state grants metadata tag 2 first and value tag 1 second. No
response can be presented to a non-owner, and local replay issues no DDR
command. Invalid descriptors, unsolicited responses, and child errors enter a
deterministic fault state instead of leaving the P4 phase controller waiting.

Generated-RTL Vivado xsim passed with independent metadata/value backpressure:

```text
P4I_FETCH_GO COMMAND_ORDER=TAG2_TAG1 METADATA=1 VALUE_BEATS=2
P4I_KV_FETCH_FRONTEND_GO OWNER_SERIAL=1 REPLAY_NO_DDR=1 BACKPRESSURE=1 DONE_IDENTITY=1
```

The AXI-Lite projection sequencer's real attention-barrier level is also now
exported through `DataPath_xN` to every core. No synthesis or implementation
run was made.
