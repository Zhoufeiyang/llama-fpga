# P5-E retained metadata line and P5 closure — 2026-09-12

## Verdict

`P5-E retained metadata-line sub-gate: GO`

`P5: GO`

No full synthesis, implementation, bitstream generation, or board programming
was run. P5 is closed at source, generated-RTL, and self-checking simulation
level; physical timing and board DDR observation remain later-stage gates.

## Implementation

The production `KvScaleZeroPacker` now retains the most recently emitted
512-bit scale/zero line in its existing K and V line FIFOs. Retention does not
allocate a second metadata store. When the next physical line starts, slot zero
is built on a cleared line while the retained previous line is replaced in the
same FIFO transaction.

If a speculative line-end token is rejected, replaying the same position merges
the replacement 32-bit metadata entry into the retained line and emits it
again. Therefore committed neighbours are preserved without copying the KV
block or physically rolling back DDR.

## Production xsim result

The test uses the generated production 512-bit packer, not a behavioural merge
shell. It fills both metadata lines, emits their line-end candidate, rejects and
replays that candidate with a different value, and compares all sixteen entries
bit-for-bit:

```text
P5E_FIRST_WRITE position=15 tail=a5 committed_neighbors=preserved
P5E_REPLAY_WRITE position=15 tail=b5 committed_neighbors=preserved
P5E_FIRST_WRITE position=31 tail=a6 committed_neighbors=preserved
P5E_REPLAY_WRITE position=31 tail=b6 committed_neighbors=preserved
P5E_METADATA_RETAINED_LINE_GO BOUNDARIES_15_16_31_32=1 REJECTED_LINE_END_REPLAY=1 BIT_PRESERVE=1
```

The complete KV260 top subsequently passed SpinalHDL checks, transforms, and
Verilog generation.

## Artifacts

- Production packer RTL: 106,455 bytes, SHA256
  `7285aa16131b8b1dff71ce758fdfc1816e99fd86f5f306507ac515450c8a515b`.
- Production packer xsim log: 895 bytes, SHA256
  `efa435b3c92986673cc7a61d338df7075cadf51f09d5163b4c68f3e6b54629bd`.
- Complete production top: 2,054,147 bytes, SHA256
  `b9a9479d2e2f40bbf0300c5b1ed7f17c775310d52e66dc1411f731160dddc64d`.

Together with P5-A through P5-D, this satisfies accepted lengths 0..4, invisible
rejected tails, abort/timeout pointer restoration, bounded DDR addresses,
explicit future-slot writes, and partial metadata-line preservation.
