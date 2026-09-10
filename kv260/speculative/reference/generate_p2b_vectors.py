"""Generate deterministic P2-B xsim vectors from the frozen P1 reference."""
from pathlib import Path
import sys
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from w4_reference import DenseW4Matrix, gemm_reference  # noqa: E402


def main(out_dir: str) -> None:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    q = np.full((2, 16), 8, dtype=np.uint8)
    q[0, (0, 8)] = 12
    q[1, (0, 8)] = 4
    zero = np.full((2, 2), 8, dtype=np.uint8)
    scale = np.array([[1.0, 1.0], [0.5, 0.5]], dtype=np.float16)
    matrix = DenseW4Matrix(q, scale, zero, 8)
    acts = np.zeros((4, 16), dtype=np.float16)
    for token in range(4):
        acts[token, 0] = token + 1
        acts[token, 8] = token + 2
    golden = gemm_reference(acts, matrix)

    def words16(row):
        return "".join(f"{int(x):04x}" for x in row.view(np.uint16)[::-1])

    with (out / "activation.mem").open("w", encoding="ascii") as f:
        for row in acts.reshape(4, 2, 8):
            f.write(words16(row[0]) + "\n")
            f.write(words16(row[1]) + "\n")
    with (out / "weight.mem").open("w", encoding="ascii") as f:
        for row in q:
            for beat in row.reshape(2, 8):
                packed = bytes((int(beat[i]) | (int(beat[i + 1]) << 4)) for i in range(0, 8, 2))
                f.write(packed[::-1].hex() + "\n")
    with (out / "golden.mem").open("w", encoding="ascii") as f:
        for token in range(4):
            for row in range(2):
                f.write(f"{int(golden[token, row].view(np.uint16)):04x}\n")
    print("P2B_REFERENCE", " ".join(f"{x:04x}" for x in golden.view(np.uint16).ravel()))


if __name__ == "__main__":
    main(sys.argv[1])
