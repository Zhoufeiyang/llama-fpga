# P2-C row-major lane contract — 2026-09-14

`P2-C K-lane sideband router sub-gate: GO`

`P2 downstream consumer adoption: IN PROGRESS`

`top.P2RowMajorLaneRouter` preserves the production P2 order (row, then
candidate lane) without a tensor-sized transpose. Every accepted beat carries
`laneId`, `rowIndex`, `rowLast`, and `batchLast`; only the selected K lane sees
valid, and upstream backpressure follows that lane. The router freezes K for
the batch, checks row/lane ordering and boundaries, and drains malformed lane
traffic through a separate fault stream.

Generated-RTL Vivado xsim:

```text
P2_ROW_ROUTER_K1_GO accepted=6 rows=2 lanes=1 backpressure=1
P2_ROW_ROUTER_K2_GO accepted=12 rows=2 lanes=2 backpressure=1
P2_ROW_ROUTER_K3_GO accepted=18 rows=2 lanes=3 backpressure=1
P2_ROW_ROUTER_K4_GO accepted=24 rows=2 lanes=4 backpressure=1
P2_ROW_MAJOR_LANE_ROUTER_GO K1_TO_K4=1 SIDEBAND=1 FAULT_DRAINABLE=1
```

No synthesis or implementation run was made. The remaining closure item is
to carry this sideband through attention, residual, normalization, and logits
consumer boundaries.
