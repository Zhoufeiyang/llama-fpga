# P3 transformer projection scheduler gate — 2026-09-11

## Verdict

`P3 transformer projection scheduler sub-gate: GO`

`P3 P2-adapter sub-gate: GO`

`P3: GO`

No full KV260 synthesis, implementation, bitstream generation, or board
programming was run. P0 artifacts remain frozen.

## Layer graph and P2 contract

```text
Q -> K -> V -> attention barrier -> O -> G -> U -> MLP activation barrier -> D
```

After the final D projection the controller emits LM head. Existing tensor tags
are preserved: Q=5, K=6, V=7, O=16, G=22, U=24, D=31, logits=35.
Q/K/V/O/G/U/logits consume 32 activation beats per row; D consumes 86 because
11008/128=86. Output rows are 4096 for Q/K/V/O/D, 11008 for G/U, and 32000 for
logits.

The P3-to-P2 adapter accepts a descriptor only with the P2 configuration
handshake and retains tag/layer identity until P2 `done`.

## Simulation acceptance

```text
P3_K1 descriptors=15 barriers=4
P3_K2 descriptors=15 barriers=4
P3_K3 descriptors=15 barriers=4
P3_K4 descriptors=15 barriers=4
P3_TRANSFORMER_PROJECTION_SCHEDULER_GO K1_TO_K4=1
P3_P2_PROJECTION_ADAPTER_GO
```

The two-layer test emits seven descriptors per layer plus LM head for every K.
It also covers descriptor backpressure, illegal legacy K>1, wrong completion
tag, P2 configuration forwarding, and completion identity retention.

## OOC synthesis acceptance

The combined scheduler and P2 adapter synthesized with zero errors and zero
critical warnings. At 3.333 ns it reports WNS `+1.647 ns`, 70 LUTs, 69
registers, zero BRAM, and zero DSP. DCP SHA256:

`585b73cf3983ab49684859dce4d4e7074145ce67d24b7ac90f5192203926fced`

This proves the standalone controller only; P7 remains responsible for
full-design implementation and board timing. P4 next consumes the attention
barrier with causal K-token attention and tiled DDR KV streaming.
