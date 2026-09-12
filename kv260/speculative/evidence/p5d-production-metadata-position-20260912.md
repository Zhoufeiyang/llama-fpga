# P5-D production metadata positioning — 2026-09-12

## Verdict

`P5-D production metadata-position sub-gate: GO`

`P5: IN PROGRESS`

This is a narrow source-integration gate. It does not yet prove DDR
read-modify-write after a rejected candidate has already caused a full metadata
line to be emitted.

## Production change

`KvScaleZeroPacker` now receives the same absolute physical KV token position
used by the production command generator. At every token launch, K and V
metadata insertion counters seek to `tokenPosition[3:0]`, matching the sixteen
32-bit entries in a 512-bit metadata line.

The line-end decision is also derived from the explicit position. Consequently,
rollback and replay inside an unflushed line overwrite the same tentative entry
instead of continuing an unrelated sequential counter. Legacy mode supplies
the original `StateGen` token position, so its addressing contract is retained.

## Verification

The production 512-bit packer with explicit positioning passed SpinalHDL
checks, transforms, and Verilog generation. The complete KV260 top also passed
elaboration. Structural inspection confirms that `io_tokenPosition[3:0]`
drives both K/V insertion indices, both line-end flags, and the global metadata
flush state.

Artifacts:

- Focused packer RTL: 106,003 bytes, SHA256
  `d43c368f86a75bd04f8f0c5d39ec40e996be558234731bdecc20930781b8fdc7`.
- Complete production top RTL: 2,054,005 bytes, SHA256
  `c04c384d5a93e64384e16fee5482237ab5135613ed0d00d7439ffd1912688a55`.

P5 cannot be marked GO until the emitted-line boundary case is connected to an
old-line-preserving DDR RMW or an equivalent retained-line implementation and
is tested after rejection at positions 15 and 31.
