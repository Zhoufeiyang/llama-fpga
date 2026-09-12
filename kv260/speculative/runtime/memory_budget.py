#!/usr/bin/env python3
"""Deterministic KV260 DDR budget for the speculative runtime."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

DDR_LOW_START = 0x00000000
DDR_LOW_END = 0x7FF00000
DDR_HIGH_START = 0x800000000
DDR_HIGH_END = 0x880000000
REGION0_START = 0x00036000
A53_APP_START = 0x73000000
A53_APP_END = 0x7FF00000


def align(value: int, alignment: int = 64) -> int:
    return (value + alignment - 1) // alignment * alignment


def build_budget(model0_bytes: int, model1_bytes: int) -> dict:
    regions: list[dict] = [
        {"name": "target_region_0", "start": REGION0_START,
         "end": REGION0_START + model1_bytes},
        {"name": "target_region_1", "start": DDR_HIGH_START,
         "end": DDR_HIGH_START + model0_bytes},
        {"name": "a53_app_window", "start": A53_APP_START,
         "end": A53_APP_END},
    ]
    cursor = align(REGION0_START + model1_bytes, 4096)
    allocations = [
        ("draft_ngram_4096", 4096 * 12),
        ("tiny_draft_int8", 512064),
        ("activation_k4_fp16", 4 * 4096 * 2),
        ("gate_up_k4_fp16", 2 * 4 * 11008 * 2),
        ("tentative_kv_k4", 1024 * 1024),
        ("candidate_metadata", 32 * 1024),
        ("runtime_guard", 4 * 1024 * 1024),
    ]
    for name, size in allocations:
        start = align(cursor)
        end = start + size
        regions.append({"name": name, "start": start, "end": end})
        cursor = end

    ordered_low = sorted((r for r in regions if r["start"] < DDR_LOW_END),
                         key=lambda r: r["start"])
    overlaps = []
    for left, right in zip(ordered_low, ordered_low[1:]):
        if left["end"] > right["start"]:
            overlaps.append([left["name"], right["name"]])
    target_low = next(r for r in regions if r["name"] == "target_region_0")
    target_high = next(r for r in regions if r["name"] == "target_region_1")
    bounds_ok = (target_low["end"] <= A53_APP_START and
                 target_high["end"] <= DDR_HIGH_END and
                 cursor <= A53_APP_START)
    return {
        "ddr_total_bytes": (DDR_LOW_END - DDR_LOW_START) +
                           (DDR_HIGH_END - DDR_HIGH_START),
        "target_bytes": model0_bytes + model1_bytes,
        "theoretical_tail_bytes":
            (A53_APP_START - target_low["end"]) +
            (DDR_HIGH_END - target_high["end"]),
        "runtime_reserved_bytes": sum(size for _, size in allocations),
        "low_tail_after_runtime_bytes": A53_APP_START - cursor,
        "high_tail_bytes": DDR_HIGH_END - target_high["end"],
        "a53_app_window_bytes": A53_APP_END - A53_APP_START,
        "regions": regions,
        "overlaps": overlaps,
        "go": bounds_ok and not overlaps,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model0-bytes", type=int, default=2109472768)
    parser.add_argument("--model1-bytes", type=int, default=1915437056)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = build_budget(args.model0_bytes, args.model1_bytes)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n",
                               encoding="ascii")
    print("P7D_MEMORY_BUDGET_{} TARGET_BYTES={} RUNTIME_RESERVED={} "
          "LOW_TAIL={} OVERLAPS={}".format(
              "GO" if report["go"] else "NO_GO",
              report["target_bytes"], report["runtime_reserved_bytes"],
              report["low_tail_after_runtime_bytes"],
              len(report["overlaps"])))
    return 0 if report["go"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
