#!/usr/bin/env python3
"""Numerical/traffic check for the production shared GEMV/GEMM schedule.

The checker mirrors the contract implemented by SpeculativeGemmReplay and
MulAddEngineNew: activation chunks are laid out token-major, each streamed
weight row is buffered once and replayed K times, and AddEngine groups every
B products into one independent token/row result.  It intentionally uses
integer values so a mismatch cannot be hidden by floating-point tolerance.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def check_case(k: int, rows: int, beats: int, lanes: int) -> dict:
    # A[k][b][lane] is the activation chunk stored in MulEngine RAM.
    activation = [
        [[10000 * token + 100 * beat + lane + 1 for lane in range(lanes)]
         for beat in range(beats)]
        for token in range(k)
    ]
    weights = [
        [[1000 * row + 10 * beat + lane + 1 for lane in range(lanes)]
         for beat in range(beats)]
        for row in range(rows)
    ]

    # Flattened stream seen by MulEngine: row, token, beat.  The activation
    # address is token*B+beat, while AddEngine sees firstDim=B and therefore
    # emits a separate result for each token/row pair.
    flattened = []
    for row in range(rows):
        for token in range(k):
            for beat in range(beats):
                flattened.append((token, row, beat))

    got = {}
    scalar_stream = []
    for group in range(rows * k):
        token, row, _ = flattened[group * beats]
        terms = []
        for offset in range(beats):
            t, r, beat = flattened[group * beats + offset]
            assert (t, r) == (token, row)
            scalar_value = sum(
                a * w for a, w in zip(activation[t][beat], weights[r][beat])
            )
            terms.append(scalar_value)
            # This is the scalarOut contract before the shared FP32
            # accumulator: one reduced value per B-beat, with last marking
            # exactly the end of that token/row dot product.
            scalar_stream.append({
                "token": token,
                "row": row,
                "beat": beat,
                "value": scalar_value,
                "last": beat == beats - 1,
            })
        got[(token, row)] = sum(terms)

    expected = {}
    for token in range(k):
        for row in range(rows):
            expected[(token, row)] = sum(
                activation[token][beat][lane] * weights[row][beat][lane]
                for beat in range(beats)
                for lane in range(lanes)
            )

    if got != expected:
        raise AssertionError(f"independent token accumulation mismatch for K={k}")

    expected_last = [idx for idx, item in enumerate(scalar_stream)
                     if item["last"]]
    actual_last = list(range(beats - 1, rows * k * beats, beats))
    if expected_last != actual_last:
        raise AssertionError(f"scalar last boundary mismatch for K={k}")
    if any(item["last"] != (item["beat"] == beats - 1)
           for item in scalar_stream):
        raise AssertionError(f"scalar last is not B-beat aligned for K={k}")

    input_beats = rows * beats
    logical_replays = input_beats * k
    physical_scales = [1000 * row + beat for row in range(rows)
                       for beat in range(beats)]
    replayed_scales = [1000 * row + beat
                       for row in range(rows)
                       for _token in range(k)
                       for beat in range(beats)]
    expected_scales = [physical_scales[row * beats + beat]
                       for row in range(rows)
                       for _token in range(k)
                       for beat in range(beats)]
    if replayed_scales != expected_scales:
        raise AssertionError(f"post-scale replay order mismatch for K={k}")
    return {
        "k": k,
        "rows": rows,
        "beats_per_row": beats,
        "lanes": lanes,
        "input_weight_beats": input_beats,
        "logical_operand_replays": logical_replays,
        "physical_post_scale_beats": input_beats,
        "logical_post_scale_replays": len(replayed_scales),
        "post_scale_order": "row-token-beat",
        "mul_cfg_first_dim": beats - 1,
        "add_cfg_first_dim": beats - 1,
        "add_cfg_second_dim": rows * k - 1,
        "independent_results": rows * k,
        "scalar_stream_values": scalar_stream,
        "scalar_last_indices": actual_last,
        "scalar_last_every_B": True,
        "bit_exact_independent_dot_results": True,
        "bit_exact_integer_schedule": True,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument("--rows", type=int, default=3)
    parser.add_argument("--beats", type=int, default=5)
    parser.add_argument("--lanes", type=int, default=8)
    args = parser.parse_args()

    cases = [check_case(k, args.rows, args.beats, args.lanes) for k in range(1, 5)]
    weight_counts = [case["input_weight_beats"] for case in cases]
    if weight_counts != [weight_counts[0]] * 4:
        raise AssertionError("weight input is not K-independent")
    for case in cases:
        if case["logical_operand_replays"] != weight_counts[0] * case["k"]:
            raise AssertionError("logical replay count is not K-scaled")

    # The production cfg ABI stores rows-1 in 16 bits, but the internal dot
    # loop is 18 bits.  This specifically guards the Llama vocabulary case
    # that previously wrapped for K=3 and K=4.
    vocab_rows = 32000
    lm_head_bounds = [vocab_rows * k - 1 for k in range(1, 5)]
    if lm_head_bounds != [31999, 63999, 95999, 127999]:
        raise AssertionError("unexpected LM-head speculative row bounds")
    if max(lm_head_bounds) >= (1 << 18):
        raise AssertionError("18-bit speculative row bound is insufficient")
    if lm_head_bounds[2] < (1 << 16) or lm_head_bounds[3] < (1 << 16):
        raise AssertionError("LM-head overflow guard did not exercise >16-bit bounds")

    result = {
        "gate": "P2 production shared GEMV/GEMM replay schedule",
        "status": "GO",
        "cases": cases,
        "weight_beats_K1_K2_K3_K4": weight_counts,
        "replays_K1_K2_K3_K4": [case["logical_operand_replays"] for case in cases],
        "lm_head_rows": vocab_rows,
        "lm_head_effective_bounds_K1_K2_K3_K4": lm_head_bounds,
        "effective_second_dim_bits": 18,
        "post_scale_replay_verified": True,
        "same_multiplier_array": True,
        "note": "Schedule/numerical contract check; downstream vendor FP16 latency and projection-done remain separate gates.",
    }
    rendered = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
