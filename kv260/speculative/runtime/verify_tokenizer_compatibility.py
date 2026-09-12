#!/usr/bin/env python3
"""Compare the target tkz.bin vocabulary with a SentencePiece draft model."""

from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path

import sentencepiece as spm


def exported_piece(index: int, piece: str) -> str:
    """Mirror the llama2.c tokenizer.bin text normalization."""
    if index == 1:
        return "\n<s>\n"
    if index == 2:
        return "\n</s>\n"
    return piece.replace("▁", " ")


def read_tkz(path: Path, vocab_size: int) -> tuple[int, list[tuple[float, str]]]:
    data = path.read_bytes()
    if len(data) < 4:
        raise ValueError("tkz header is truncated")
    max_length = struct.unpack_from("<I", data, 0)[0]
    offset = 4
    entries: list[tuple[float, str]] = []
    for index in range(vocab_size):
        if offset + 8 > len(data):
            raise ValueError(f"tkz entry {index} header is truncated")
        score, length = struct.unpack_from("<fI", data, offset)
        offset += 8
        if length > max_length or offset + length > len(data):
            raise ValueError(f"tkz entry {index} payload is invalid")
        piece = data[offset:offset + length].decode("utf-8")
        offset += length
        entries.append((score, piece))
    if offset != len(data):
        raise ValueError(f"tkz has {len(data) - offset} trailing bytes")
    return max_length, entries


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("target_tkz", type=Path)
    parser.add_argument("draft_tokenizer", type=Path)
    parser.add_argument("--vocab-size", type=int, default=32000)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    max_length, target = read_tkz(args.target_tkz, args.vocab_size)
    draft = spm.SentencePieceProcessor(model_file=str(args.draft_tokenizer))
    piece_mismatches = []
    score_mismatches = []
    if draft.vocab_size() == args.vocab_size:
        for index, (target_score, target_piece) in enumerate(target):
            draft_piece = exported_piece(index, draft.id_to_piece(index))
            if target_piece != draft_piece and len(piece_mismatches) < 16:
                piece_mismatches.append(
                    {"id": index, "target": target_piece, "draft": draft_piece})
            draft_score = draft.get_score(index)
            if abs(target_score - draft_score) > 1e-6 and len(score_mismatches) < 16:
                score_mismatches.append(
                    {"id": index, "target": target_score, "draft": draft_score})
    report = {
        "target_vocab_size": len(target),
        "draft_vocab_size": draft.vocab_size(),
        "target_max_token_length": max_length,
        "unk_id": draft.unk_id(),
        "bos_id": draft.bos_id(),
        "eos_id": draft.eos_id(),
        "piece_mismatch_count_capped": len(piece_mismatches),
        "score_mismatch_count_capped": len(score_mismatches),
        "piece_mismatches": piece_mismatches,
        "score_mismatches": score_mismatches,
    }
    report["go"] = (
        report["target_vocab_size"] == report["draft_vocab_size"] ==
        args.vocab_size and not piece_mismatches and not score_mismatches and
        report["unk_id"] == 0 and report["bos_id"] == 1 and
        report["eos_id"] == 2
    )
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) +
                               "\n", encoding="utf-8")
    print("P7E_TOKENIZER_{} VOCAB={} PIECE_MISMATCHES={} SCORE_MISMATCHES={} "
          "UNK={} BOS={} EOS={}".format(
              "GO" if report["go"] else "NO_GO", draft.vocab_size(),
              len(piece_mismatches), len(score_mismatches), draft.unk_id(),
              draft.bos_id(), draft.eos_id()))
    return 0 if report["go"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
