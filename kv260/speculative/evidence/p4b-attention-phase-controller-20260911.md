# P4-B causal attention phase controller — 2026-09-11

## Verdict

`P4-B numerical-reference sub-gate: GO`

`P4-B attention-phase-controller sub-gate: GO`

`P4: IN PROGRESS`

No full synthesis, implementation, bitstream generation, or board programming
was run. This gate covers the causal numerical contract and the controller
boundary to the existing QKMul, SerialSafeSoftmax, and V-AXPY kernels.

## Contract

For each candidate query `q`, the controller performs a QK tile sweep over the
committed prefix followed by tentative tokens `[0..q]`, launches softmax with
exactly `committed_tokens + q + 1` scores, and repeats the identical causal tile
sweep for V accumulation. Phase, source, query, and tile-start identities stay
locked until completion. Wrong or unsolicited completions enter a sticky fault.

## Numerical acceptance

The NumPy oracle uses stable max-subtracted softmax with the production `-16`
clip and models INT8 KV scale/zero dequantization. Sixteen reference tests pass.
For K=1..4, tiled attention is elementwise equivalent to dense causal attention;
mutating a future tentative candidate cannot change an earlier query result.

## RTL simulation acceptance

```text
P4B_K1 qk_tiles=4 softmax=1 v_tiles=4
P4B_K2 qk_tiles=8 softmax=2 v_tiles=8
P4B_K3 qk_tiles=12 softmax=3 v_tiles=12
P4B_K4 qk_tiles=16 softmax=4 v_tiles=16
P4B_ATTENTION_PHASE_CONTROLLER_GO K1_TO_K4=1
```

The 130-token committed prefix forces three DDR tiles plus one tentative tile
per phase and query, proving the QK/softmax/V ordering and causal visible length.

## OOC synthesis acceptance

Vivado 2022.2 synthesized the controller with zero errors and zero critical
warnings. At 3.333 ns it reports WNS `+1.186 ns`, 118 LUTs and 56 registers,
with no BRAM or DSP. DCP SHA256:

`33d216f8531a921425db382bbe67d2b29a87d9a13fa3380cab1bd916c73e524e`

P4 remains in progress until the controller is connected to the actual
floating-point attention datapath and vendor-IP numerical co-simulation passes.
