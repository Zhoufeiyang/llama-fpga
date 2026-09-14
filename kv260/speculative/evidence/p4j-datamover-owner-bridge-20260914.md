# P4-J DataMover owner bridge — 2026-09-14

`P4-J logical command/response bridge sub-gate: GO`

`top.P4DataMoverBridge` arbitrates legacy and P4 72-bit commands before
address remapping, holds command/owner stable under stall, and records
separate data-frame and status owners. The shared 512-bit response is routed
for the entire frame and retired only on accepted `last`; aggregate status is
retired independently. A response arriving while an accepted owner is still
inside a for-FMax FIFO is safely backpressured, while a response with zero
accepted commands is a sticky underflow error.

Generated-RTL Vivado xsim:

```text
P4J_DATAMOVER_BRIDGE_GO P4_PRIORITY=1 FRAME_STABLE=1 OWNER_DEMUX=1 BACKPRESSURE=1
```

No synthesis or implementation run was made. DataPath instantiation and P4
arithmetic consumer completion remain open.
