import unittest

import numpy as np

try:
    from .w4_reference import (
        DenseW4Matrix,
        decode_dense_w4,
        dma_join_bytes,
        dma_join_pages,
        dma_split_bytes,
        dma_split_pages,
        encode_dense_w4,
        fp16_scale_down,
        gemm_reference,
        gemv_reference,
        packed_dense_size,
        pack_u4,
        unpack_u4,
        weight_stream_bytes,
    )
except ImportError:
    from w4_reference import (
        DenseW4Matrix,
        decode_dense_w4,
        dma_join_bytes,
        dma_join_pages,
        dma_split_bytes,
        dma_split_pages,
        encode_dense_w4,
        fp16_scale_down,
        gemm_reference,
        gemv_reference,
        packed_dense_size,
        pack_u4,
        unpack_u4,
        weight_stream_bytes,
    )


class DenseW4ReferenceTest(unittest.TestCase):
    def setUp(self) -> None:
        rng = np.random.default_rng(260)
        self.output_dim = 12
        self.group_size = 16
        self.input_dim = self.group_size * self.group_size // 4
        groups = self.input_dim // self.group_size
        self.matrix = DenseW4Matrix(
            qweight=rng.integers(
                0, 16, size=(self.output_dim, self.input_dim), dtype=np.uint8
            ),
            scale=(
                rng.uniform(0.02, 0.5, size=(self.output_dim, groups)) * 4.0
            ).astype(np.float16),
            zero=rng.integers(
                0, 16, size=(self.output_dim, groups), dtype=np.uint8
            ),
            group_size=self.group_size,
        )
        self.rng = rng

    def test_nibble_order_round_trip(self) -> None:
        values = np.arange(16, dtype=np.uint8)
        packed = pack_u4(values)
        self.assertEqual(packed[0], 0x10)
        self.assertEqual(packed[-1], 0xFE)
        np.testing.assert_array_equal(unpack_u4(packed, values.size), values)

    def test_dma_split_fixed_lane_order(self) -> None:
        source = bytes(range(8))
        split = dma_split_bytes(source, bus_width_bits=32, split=2)
        self.assertEqual(split, bytes([0, 1, 4, 5, 2, 3, 6, 7]))
        self.assertEqual(dma_join_bytes(split, 32, 2), source)

    def test_dma_page_round_trip_with_final_padding(self) -> None:
        source = bytes(range(192))
        stored = dma_split_pages(
            source, bus_width_bits=512, split=4, page_size=128
        )
        self.assertEqual(len(stored), 256)
        self.assertEqual(stored[192:], bytes(64))
        self.assertEqual(
            dma_join_pages(
                stored,
                original_size=len(source),
                bus_width_bits=512,
                split=4,
                page_size=128,
            ),
            source,
        )

    def test_dma_page_decoder_rejects_nonzero_padding(self) -> None:
        source = bytes(range(64))
        stored = bytearray(
            dma_split_pages(source, bus_width_bits=512, split=4, page_size=128)
        )
        stored[-1] = 1
        with self.assertRaisesRegex(ValueError, "nonzero bytes"):
            dma_join_pages(bytes(stored), len(source), 512, 4, 128)

    def test_dma_transform_rejects_partial_bus_beat(self) -> None:
        with self.assertRaisesRegex(ValueError, "multiple of the bus width"):
            dma_split_bytes(bytes(63), bus_width_bits=512, split=4)

    def test_dense_layout_round_trip(self) -> None:
        packed = encode_dense_w4(self.matrix)
        self.assertEqual(
            len(packed),
            packed_dense_size(self.output_dim, self.input_dim, self.group_size),
        )
        decoded = decode_dense_w4(
            packed, self.output_dim, self.input_dim, self.group_size
        )
        np.testing.assert_array_equal(decoded.qweight, self.matrix.qweight)
        np.testing.assert_array_equal(decoded.scale, self.matrix.scale)
        np.testing.assert_array_equal(decoded.zero, self.matrix.zero)

    def test_dense_layout_fixed_golden_bytes(self) -> None:
        matrix = DenseW4Matrix(
            qweight=np.arange(16, dtype=np.uint8).reshape(4, 4),
            scale=np.array([[1.0], [2.0], [3.0], [4.0]], dtype=np.float16),
            zero=np.array([[1], [2], [3], [4]], dtype=np.uint8),
            group_size=4,
        )
        expected = bytes.fromhex(
            "21 43 "
            "00 3c 10 32 "
            "00 40 54 76 "
            "00 42 98 ba "
            "00 44 dc fe"
        )
        self.assertEqual(encode_dense_w4(matrix), expected)

    def test_llama_4096_projection_size(self) -> None:
        # 4096x4096: 8 MiB W4 + 256 KiB FP16 scales + 64 KiB W4 zeros.
        self.assertEqual(packed_dense_size(4096, 4096, 128), 8_716_288)

    def test_fp16_scale_down_matches_exponent_edit(self) -> None:
        values = np.array([-8.0, -1.0, 0.0, 1.0, 7.0], dtype=np.float16)
        actual = fp16_scale_down(values, 2)
        expected = np.array([-2.0, -0.25, 0.0, 0.25, 1.75], dtype=np.float16)
        np.testing.assert_array_equal(actual, expected)

    def test_small_batch_matches_repeated_gemv(self) -> None:
        activations = self.rng.normal(
            0.0, 0.2, size=(4, self.input_dim)
        ).astype(np.float16)
        actual = gemm_reference(activations, self.matrix)
        expected = np.stack(
            [gemv_reference(activation, self.matrix) for activation in activations]
        )
        np.testing.assert_array_equal(actual, expected)

    def test_weight_stream_is_independent_of_batch_count(self) -> None:
        one = weight_stream_bytes(self.matrix, 1)
        for batch_count in (2, 3, 4):
            self.assertEqual(weight_stream_bytes(self.matrix, batch_count), one)

    def test_rejects_layout_not_supported_by_model_packer(self) -> None:
        with self.assertRaisesRegex(ValueError, "input_dim/group_size"):
            packed_dense_size(output_dim=4, input_dim=32, group_size=16)


if __name__ == "__main__":
    unittest.main()
