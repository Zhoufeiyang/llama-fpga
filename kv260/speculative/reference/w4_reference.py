"""Reference model for the dense W4 layout consumed by the KV260 datapath.

The implementation intentionally has no AWQ or PyTorch dependency.  It mirrors
the dense layout emitted by ``python/model2bin.py::pack_weight_scale_zero_fast``
and provides a small-batch projection model used by the P1/P2 stage gates.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np


ROWS_PER_ZERO_BLOCK = 4


def _dma_geometry(bus_width_bits: int, split: int) -> tuple[int, int]:
    if bus_width_bits <= 0 or bus_width_bits % 8:
        raise ValueError("bus_width_bits must be a positive multiple of eight")
    bus_bytes = bus_width_bits // 8
    if split <= 0 or bus_bytes % split:
        raise ValueError("split must be a positive divisor of the bus byte width")
    return bus_bytes, bus_bytes // split


def dma_split_bytes(data: bytes, bus_width_bits: int = 512, split: int = 4) -> bytes:
    """Mirror model2bin.py's split-major DMA byte permutation.

    Each input bus beat is divided into ``split`` contiguous lanes.  The file
    layout stores all beats for lane zero first, then all beats for lane one,
    and so on.  This is a permutation only; it does not add padding.
    """

    bus_bytes, lane_bytes = _dma_geometry(bus_width_bits, split)
    source = np.frombuffer(data, dtype=np.uint8)
    if source.size % bus_bytes:
        raise ValueError("DMA payload length must be a multiple of the bus width")
    if source.size == 0:
        return b""
    split_major = source.reshape(-1, split, lane_bytes).transpose(1, 0, 2)
    return split_major.tobytes()


def dma_join_bytes(data: bytes, bus_width_bits: int = 512, split: int = 4) -> bytes:
    """Invert :func:`dma_split_bytes` and restore bus-beat byte order."""

    bus_bytes, lane_bytes = _dma_geometry(bus_width_bits, split)
    source = np.frombuffer(data, dtype=np.uint8)
    if source.size % bus_bytes:
        raise ValueError("DMA payload length must be a multiple of the bus width")
    if source.size == 0:
        return b""
    beat_major = source.reshape(split, -1, lane_bytes).transpose(1, 0, 2)
    return beat_major.tobytes()


def dma_split_pages(
    data: bytes,
    bus_width_bits: int = 512,
    split: int = 4,
    page_size: int = 8192,
) -> bytes:
    """Mirror model2bin.py's page-local DMA permutation and zero padding."""

    bus_bytes, _ = _dma_geometry(bus_width_bits, split)
    if page_size <= 0 or page_size % bus_bytes:
        raise ValueError("page_size must be a positive multiple of the bus width")
    if len(data) == 0:
        return b""

    result = bytearray()
    for offset in range(0, len(data), page_size):
        page = data[offset : offset + page_size]
        result.extend(dma_split_bytes(page, bus_width_bits, split))
        result.extend(b"\x00" * (page_size - len(page)))
    return bytes(result)


def dma_join_pages(
    data: bytes,
    original_size: int,
    bus_width_bits: int = 512,
    split: int = 4,
    page_size: int = 8192,
    require_zero_padding: bool = True,
) -> bytes:
    """Recover logical bytes from a page-padded, split-major DMA region.

    ``original_size`` is required because the model binary does not encode the
    useful length in the page padding.  Rejecting nonzero padding catches an
    incorrect offset or matrix extent before numerical comparison begins.
    """

    bus_bytes, _ = _dma_geometry(bus_width_bits, split)
    if page_size <= 0 or page_size % bus_bytes:
        raise ValueError("page_size must be a positive multiple of the bus width")
    if original_size < 0 or original_size % bus_bytes:
        raise ValueError("original_size must be a nonnegative bus-width multiple")
    expected_size = (
        0 if original_size == 0 else ((original_size + page_size - 1) // page_size) * page_size
    )
    if len(data) != expected_size:
        raise ValueError(f"DMA region has {len(data)} bytes; expected {expected_size}")

    result = bytearray()
    remaining = original_size
    for offset in range(0, len(data), page_size):
        useful_size = min(page_size, remaining)
        page = data[offset : offset + page_size]
        result.extend(dma_join_bytes(page[:useful_size], bus_width_bits, split))
        if require_zero_padding and any(page[useful_size:]):
            raise ValueError("DMA region contains nonzero bytes in page padding")
        remaining -= useful_size
    return bytes(result)


def pack_u4(values: np.ndarray) -> bytes:
    """Pack unsigned four-bit values, placing the first value in the low nibble."""

    flat = np.asarray(values, dtype=np.uint8).reshape(-1)
    if np.any(flat > 0x0F):
        raise ValueError("W4 values must be in the range [0, 15]")
    if flat.size % 2:
        flat = np.pad(flat, (0, 1))
    packed = flat[0::2] | (flat[1::2] << 4)
    return packed.tobytes()


def unpack_u4(data: bytes, count: int) -> np.ndarray:
    """Unpack low-nibble-first W4 data into a uint8 vector."""

    packed = np.frombuffer(data, dtype=np.uint8)
    unpacked = np.empty(packed.size * 2, dtype=np.uint8)
    unpacked[0::2] = packed & 0x0F
    unpacked[1::2] = packed >> 4
    if count > unpacked.size:
        raise ValueError("Packed W4 data is shorter than the requested count")
    return unpacked[:count].copy()


@dataclass(frozen=True)
class DenseW4Matrix:
    """Decoded dense W4 matrix and its per-group quantization parameters."""

    qweight: np.ndarray
    scale: np.ndarray
    zero: np.ndarray
    group_size: int

    def __post_init__(self) -> None:
        qweight = np.asarray(self.qweight)
        scale = np.asarray(self.scale)
        zero = np.asarray(self.zero)
        if qweight.ndim != 2:
            raise ValueError("qweight must be a two-dimensional matrix")
        if qweight.shape[1] % self.group_size:
            raise ValueError("input dimension must be divisible by group_size")
        expected_params = (qweight.shape[0], qweight.shape[1] // self.group_size)
        if scale.shape != expected_params or zero.shape != expected_params:
            raise ValueError(
                f"scale and zero must both have shape {expected_params}, got "
                f"{scale.shape} and {zero.shape}"
            )
        if np.any(qweight < 0) or np.any(qweight > 15):
            raise ValueError("qweight values must be in the range [0, 15]")
        if np.any(zero < 0) or np.any(zero > 15):
            raise ValueError("zero values must be in the range [0, 15]")

    @property
    def output_dim(self) -> int:
        return int(self.qweight.shape[0])

    @property
    def input_dim(self) -> int:
        return int(self.qweight.shape[1])

    @property
    def groups_per_row(self) -> int:
        return self.input_dim // self.group_size


def _validate_dense_layout(output_dim: int, input_dim: int, group_size: int) -> None:
    if output_dim <= 0 or output_dim % ROWS_PER_ZERO_BLOCK:
        raise ValueError(f"output_dim must be a positive multiple of {ROWS_PER_ZERO_BLOCK}")
    if input_dim <= 0 or input_dim % group_size:
        raise ValueError("input_dim must be a positive multiple of group_size")
    if group_size <= 0 or group_size % 4:
        raise ValueError("group_size must be a positive multiple of four")

    # model2bin.py derives each zero/scale burst from group_size rather than
    # input_dim.  This equality is true for the current 4096xN Llama2 dense
    # projections (4096 / 128 == 128 / 4) and is therefore an explicit format
    # invariant rather than a hidden assumption.
    if input_dim // group_size != group_size // 4:
        raise ValueError(
            "the checked-in dense packer requires input_dim/group_size == group_size/4"
        )


def packed_dense_size(output_dim: int, input_dim: int, group_size: int = 128) -> int:
    """Return the exact byte count of a dense W4 matrix in the current layout."""

    _validate_dense_layout(output_dim, input_dim, group_size)
    groups_per_row = input_dim // group_size
    zero_bytes_per_block = ROWS_PER_ZERO_BLOCK * groups_per_row // 2
    scale_bytes_per_row = groups_per_row * np.dtype("<f2").itemsize
    weight_bytes_per_row = input_dim // 2
    block_bytes = zero_bytes_per_block + ROWS_PER_ZERO_BLOCK * (
        scale_bytes_per_row + weight_bytes_per_row
    )
    return output_dim // ROWS_PER_ZERO_BLOCK * block_bytes


def encode_dense_w4(matrix: DenseW4Matrix) -> bytes:
    """Encode a matrix using the exact dense layout from model2bin.py."""

    _validate_dense_layout(matrix.output_dim, matrix.input_dim, matrix.group_size)
    qweight = np.asarray(matrix.qweight, dtype=np.uint8)
    scale = np.asarray(matrix.scale, dtype="<f2")
    zero = np.asarray(matrix.zero, dtype=np.uint8)
    result = bytearray()

    for row_base in range(0, matrix.output_dim, ROWS_PER_ZERO_BLOCK):
        rows = slice(row_base, row_base + ROWS_PER_ZERO_BLOCK)
        result.extend(pack_u4(zero[rows]))
        for row in range(row_base, row_base + ROWS_PER_ZERO_BLOCK):
            result.extend(scale[row].tobytes())
            result.extend(pack_u4(qweight[row]))

    expected_size = packed_dense_size(
        matrix.output_dim, matrix.input_dim, matrix.group_size
    )
    if len(result) != expected_size:
        raise AssertionError(f"internal pack-size mismatch: {len(result)} != {expected_size}")
    return bytes(result)


def decode_dense_w4(
    data: bytes, output_dim: int, input_dim: int, group_size: int = 128
) -> DenseW4Matrix:
    """Decode one dense W4 matrix from the current KV260 binary layout."""

    expected_size = packed_dense_size(output_dim, input_dim, group_size)
    if len(data) != expected_size:
        raise ValueError(f"packed matrix has {len(data)} bytes; expected {expected_size}")

    groups_per_row = input_dim // group_size
    zero_bytes_per_block = ROWS_PER_ZERO_BLOCK * groups_per_row // 2
    scale_bytes_per_row = groups_per_row * np.dtype("<f2").itemsize
    weight_bytes_per_row = input_dim // 2

    qweight = np.empty((output_dim, input_dim), dtype=np.uint8)
    scale = np.empty((output_dim, groups_per_row), dtype=np.float16)
    zero = np.empty((output_dim, groups_per_row), dtype=np.uint8)
    offset = 0

    for row_base in range(0, output_dim, ROWS_PER_ZERO_BLOCK):
        zero_end = offset + zero_bytes_per_block
        zero[row_base : row_base + ROWS_PER_ZERO_BLOCK] = unpack_u4(
            data[offset:zero_end], ROWS_PER_ZERO_BLOCK * groups_per_row
        ).reshape(ROWS_PER_ZERO_BLOCK, groups_per_row)
        offset = zero_end

        for row in range(row_base, row_base + ROWS_PER_ZERO_BLOCK):
            scale_end = offset + scale_bytes_per_row
            scale[row] = np.frombuffer(data[offset:scale_end], dtype="<f2")
            offset = scale_end

            weight_end = offset + weight_bytes_per_row
            qweight[row] = unpack_u4(data[offset:weight_end], input_dim)
            offset = weight_end

    if offset != len(data):
        raise AssertionError(f"decoder consumed {offset} of {len(data)} bytes")
    return DenseW4Matrix(qweight, scale, zero, group_size)


def fp16_scale_down(values: np.ndarray, right_shift: int = 2) -> np.ndarray:
    """Mirror util.Fp16ScaleDown's exponent edit, including its underflow rule."""

    if right_shift < 0 or right_shift > 31:
        raise ValueError("right_shift must be in the range [0, 31]")
    src = np.asarray(values, dtype=np.float16)
    bits = src.view(np.uint16).copy()
    exponent = (bits >> 10) & 0x1F
    new_exponent = np.where(exponent > right_shift, exponent - right_shift, 0)
    bits = (bits & np.uint16(0x83FF)) | (new_exponent.astype(np.uint16) << 10)
    return bits.view(np.float16)


def hardware_pre_scale_weights(matrix: DenseW4Matrix) -> np.ndarray:
    """Return the FP16 W4 values presented to the shared MAC before scale."""

    groups = matrix.groups_per_row
    zero_expanded = np.repeat(matrix.zero, matrix.group_size, axis=1)
    signed = matrix.qweight.astype(np.int16) - zero_expanded.astype(np.int16)
    converted = signed.astype(np.float16)
    return fp16_scale_down(converted, right_shift=2).reshape(
        matrix.output_dim, groups, matrix.group_size
    )


def gemv_reference(activation: np.ndarray, matrix: DenseW4Matrix) -> np.ndarray:
    """Evaluate one projection using the hardware's group-scale convention."""

    activation = np.asarray(activation, dtype=np.float16)
    if activation.shape != (matrix.input_dim,):
        raise ValueError(f"activation must have shape ({matrix.input_dim},)")

    act = activation.reshape(matrix.groups_per_row, matrix.group_size).astype(np.float32)
    weight = hardware_pre_scale_weights(matrix).astype(np.float32)
    group_dot = np.einsum("gi,ogi->og", act, weight, optimize=False)
    result = np.sum(group_dot * matrix.scale.astype(np.float32), axis=1, dtype=np.float32)
    return result.astype(np.float16)


def gemm_reference(activations: np.ndarray, matrix: DenseW4Matrix) -> np.ndarray:
    """Evaluate K activation rows while reusing one decoded weight matrix."""

    activations = np.asarray(activations, dtype=np.float16)
    if activations.ndim != 2 or activations.shape[1] != matrix.input_dim:
        raise ValueError(f"activations must have shape (K, {matrix.input_dim})")
    if activations.shape[0] < 1:
        raise ValueError("K must be at least one")

    act = activations.reshape(
        activations.shape[0], matrix.groups_per_row, matrix.group_size
    ).astype(np.float32)
    weight = hardware_pre_scale_weights(matrix).astype(np.float32)
    group_dot = np.einsum("kgi,ogi->kog", act, weight, optimize=False)
    result = np.sum(
        group_dot * matrix.scale.astype(np.float32)[None, :, :],
        axis=2,
        dtype=np.float32,
    )
    return result.astype(np.float16)


def weight_stream_bytes(matrix: DenseW4Matrix, batch_count: int) -> int:
    """Model the P2 contract: one packed weight stream for any nonzero K."""

    if batch_count < 1:
        raise ValueError("batch_count must be at least one")
    return packed_dense_size(matrix.output_dim, matrix.input_dim, matrix.group_size)
