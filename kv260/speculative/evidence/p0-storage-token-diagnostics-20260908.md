# P0 storage and raw-token diagnostics — 2026-09-08

## Result

The PS-only diagnostic application completed end-to-end inference without a
bitstream change. Exact model read lengths and raw generated token IDs pass.
An additional direct SD-window experiment proves that the sampled SD-to-DDR
transfer is correct, but the SD `llama0.bin` is not identical to the available
host copy. Model-artifact provenance therefore remains **NO-GO**.

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
out a stale CPU-cache value in the diagnostic checksum.

A follow-up PS-only experiment reopened SD `llama0.bin` before the full model
load, sought directly to offset `1054736384`, and read 64 bytes into a separate
small buffer. It reported:

```text
SD_PROBE file=llama0.bin offset=1054736384 bytes=64 byte2=ca fnv1a32=58122cd0
```

The follow-up ELF SHA256 is
`e87cda79b6fc66deb9108c53c75b4a91fb715bf36044aaf943d72162d72cd23f`.
Its 173-byte UART log is `D:\JSA paper\work\p0_sd_probe_20260908.log`, SHA256
`8748030f4cdb7a6859cbcca83cbbc461f70dedcffc2a247ae20abac6f1ecd8cb`.

The SD window and post-load DDR window are identical. The mismatch is therefore
between the SD file and `D:\JSA paper\work\llama0.bin`, not between SD and DDR.
The A53 was stopped immediately after this result, before the diagnostic
application completed another full model load.

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

This run proves complete FatFS transfer counts, sampled SD-to-DDR identity,
broad host-to-board agreement at five of six representative windows, stable
end-to-end inference after PS-only instrumentation, and a finite, varied,
EOS-terminated raw token sequence. It also localizes the observed storage
inconsistency to the SD `llama0.bin` artifact rather than the DDR transfer.

The run does not prove full-file SD/DDR identity, approved model provenance,
AXI RRESP/BRESP correctness, or timing closure for the exact bitstream.

## Next single experiment

Power down cleanly, mount the SD card on the host, and record the exact sizes
and SHA256 values of its `llama0.bin`, `llama1.bin`, and `tkz.bin`. Replace the
SD files with approved generated artifacts, or formally record their pinned
source if the current files are intentionally retained.

- **GO:** SD hashes match the approved generation manifest and repeat after a
  clean copy/eject cycle.
- **NO-GO:** any size/hash mismatch or unknown source remains.
- **Rollback:** preserve an image or hash manifest of the currently functional
  SD card before replacing files.
