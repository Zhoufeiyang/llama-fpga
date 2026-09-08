# P0 storage and raw-token diagnostics — 2026-09-08

## Result

The PS-only diagnostic application completed end-to-end inference without a
bitstream change. Exact model read lengths and raw generated token IDs pass.
The host-to-DDR sparse-probe check is **NO-GO** because one byte differs in the
middle probe of `llama0.bin`.

## Artifact and method

- Source parent commit: `be2b4f9`
- Bitstream SHA256: `893131c2e905901d669629506cd0f82173cb4ed88cb20edea570e54bcdced8a1`
- Diagnostic ELF: `llama_upstream_region0_p0diag.elf`
- Diagnostic ELF SHA256: `08a0badf704cdbaeb0a7504e64b5485abe6437b3765d0e75d8655af97dceba9f`
- ELF `region_0`: address `0x00036000`, size `0x722b4000`
- UART log: `D:\JSA paper\work\p0_instrumented_run1_20260908.log`
- UART log SHA256: `b1a4a70162097458ea81761d8a9c86c511194936395e12660df19c639da6d2e9`
- Prompt: `What is an FPGA?`

Only `kv260_sdk.c` was instrumented. The program now rejects short model reads,
prints post-flush/post-invalidate 64-byte FNV-1a probes, and prints encoded
prompt and generated token IDs. The PL image, PS initialization, linker
placement, weights, tokenizer, and prompt were unchanged.

## Read-length evidence

```text
MODEL_READ file=llama0.bin status=0 requested=2109472768 actual=2109472768
MODEL_READ file=llama1.bin status=0 requested=1915437056 actual=1915437056
```

Both model files were read completely according to FatFS.

## Sparse model probes

| File | Offset | Host FNV-1a | Board FNV-1a | Result |
|---|---:|---|---|---|
| `llama0.bin` | 0 | `5f876656` | `5f876656` | match |
| `llama0.bin` | 1054736384 | `4114a620` | `58122cd0` | **mismatch** |
| `llama0.bin` | 2109472704 | `07271d4e` | `07271d4e` | match |
| `llama1.bin` | 0 | `e13a03fe` | `e13a03fe` | match |
| `llama1.bin` | 957718528 | `dfde6ac5` | `dfde6ac5` | match |
| `llama1.bin` | 1915436992 | `dfde6ac5` | `dfde6ac5` | match |

An independent XSCT `mrd` of DDR address `0x83EDE0000` confirmed the board
window. After converting the JTAG words to little-endian bytes, the first four
bytes are:

```text
host:  6d 87 ba b8
board: 6d 87 ca b8
               ^
```

The differing byte is file offset `1054736386`: host `0xBA`, board `0xCA`.
The other 63 bytes in this probe match. This direct-memory observation rules
out a stale CPU-cache value in the diagnostic checksum, but does not yet
distinguish an SD-file difference from a deterministic DDR write/storage error.

## Token evidence

- Prompt token count: 15
- Generated token count: 690
- Generated token IDs equal to zero: 0
- Unique generated token IDs: 205
- First generated token ID: 29871
- Last generated token ID: 2 (EOS)
- CSV token-ID SHA256: `b73b482bf9c5c79370f54e7d4230f78c24505f95a800bf0591be2cbcf73ca48f`
- Total prompt plus generated tokens: 705
- Reported elapsed time: 140098656 us
- Reported throughput: 5.032168 token/s

The token hash is SHA256 over the comma-separated decimal generated IDs in
index order, without whitespace. Removing `RAW_TOKEN_ID` diagnostic records
from the captured response reconstructs 3224 response bytes with SHA256
`2c8b802fb09fee4d538f84127b5f319b660fcb3bc0f5802e6e363004d531428c`,
identical to all three non-instrumented baseline trials.

## Proven and open boundaries

This run proves complete FatFS transfer counts, broad host-to-DDR agreement at
five of six representative windows, stable end-to-end inference after PS-only
instrumentation, and a finite, varied, EOS-terminated raw token sequence. It
also localizes the only observed storage inconsistency to one byte in the
sampled `llama0.bin` middle window.

The run does not prove full-file SD/DDR identity, approved model provenance,
AXI RRESP/BRESP correctness, or timing closure for the exact bitstream.

## Next single experiment

Before loading the full model, reopen SD `llama0.bin`, seek to offset
`1054736384`, read 64 bytes into a small buffer, and report its FNV-1a and byte
2. Compare that direct SD result with both the host file and DDR result.

- **SD reports `0xCA` / `58122cd0`:** the SD file differs from the host copy;
  replace or formally identify the SD artifact.
- **SD reports `0xBA` / `4114a620`:** investigate the DDR write path or memory
  at physical address `0x83EDE0002`.
- **Rollback:** use the validated non-instrumented ELF SHA256
  `0b3ac1aa...2fc93` if instrumentation changes generation behavior.
