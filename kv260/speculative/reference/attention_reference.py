"""Causal short-sequence attention reference for P4 verification."""
from __future__ import annotations
import numpy as np


def dequant_int8(values: np.ndarray, scale: np.ndarray, zero: np.ndarray) -> np.ndarray:
    values = np.asarray(values, dtype=np.uint8)
    return (values.astype(np.float32) - np.asarray(zero, dtype=np.float32)) * np.asarray(scale, dtype=np.float32)


def _attention_one(query: np.ndarray, keys: np.ndarray, values: np.ndarray, clip: float) -> np.ndarray:
    scores = np.einsum("hd,thd->ht", query.astype(np.float32), keys.astype(np.float32), optimize=False)
    scores *= np.float32(1.0 / np.sqrt(query.shape[-1]))
    shifted = scores - np.max(scores, axis=1, keepdims=True)
    exp = np.exp(np.maximum(shifted, np.float32(clip)), dtype=np.float32)
    prob = exp / np.sum(exp, axis=1, keepdims=True, dtype=np.float32)
    return np.einsum("ht,thd->hd", prob, values.astype(np.float32), optimize=False)


def causal_attention_reference(
    queries: np.ndarray, committed_k: np.ndarray, committed_v: np.ndarray,
    tentative_k: np.ndarray, tentative_v: np.ndarray, clip: float = -16.0,
) -> np.ndarray:
    """Dense oracle: candidate q sees committed KV and tentative KV through q."""
    queries = np.asarray(queries, dtype=np.float16)
    committed_k = np.asarray(committed_k, dtype=np.float16)
    committed_v = np.asarray(committed_v, dtype=np.float16)
    tentative_k = np.asarray(tentative_k, dtype=np.float16)
    tentative_v = np.asarray(tentative_v, dtype=np.float16)
    if queries.shape != tentative_k.shape or queries.shape != tentative_v.shape:
        raise ValueError("queries and tentative K/V must have identical [K,H,D] shape")
    if committed_k.shape != committed_v.shape or committed_k.shape[1:] != queries.shape[1:]:
        raise ValueError("committed K/V must have shape [T,H,D]")
    out = []
    for q in range(queries.shape[0]):
        keys = np.concatenate((committed_k, tentative_k[: q + 1]), axis=0)
        vals = np.concatenate((committed_v, tentative_v[: q + 1]), axis=0)
        out.append(_attention_one(queries[q], keys, vals, clip))
    return np.asarray(out, dtype=np.float32)


def tiled_causal_attention_reference(
    queries: np.ndarray, committed_k: np.ndarray, committed_v: np.ndarray,
    tentative_k: np.ndarray, tentative_v: np.ndarray, tile_tokens: int = 64,
    clip: float = -16.0,
) -> np.ndarray:
    """P4 ordering model: committed tiles followed by one causal tentative tile."""
    if tile_tokens < 1:
        raise ValueError("tile_tokens must be positive")
    out = []
    for q in range(len(queries)):
        key_parts = [committed_k[i:i+tile_tokens] for i in range(0, len(committed_k), tile_tokens)]
        val_parts = [committed_v[i:i+tile_tokens] for i in range(0, len(committed_v), tile_tokens)]
        key_parts.append(tentative_k[:q+1]); val_parts.append(tentative_v[:q+1])
        out.append(_attention_one(queries[q], np.concatenate(key_parts), np.concatenate(val_parts), clip))
    return np.asarray(out, dtype=np.float32)
