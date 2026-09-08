# P0 board-functional evidence — 2026-09-08

## Scope and result

This record covers three independent reset, download, and run trials of the
fixed-`region_0` target application. The board-functional sub-gate is **GO**:
all three trials completed generation and produced an identical normalized
response. The overall P0 gate remains **NO-GO** because model provenance,
raw-token capture, explicit transfer-length/DDR checks, AXI response checks,
and timing evidence for the exact programmed bitstream remain open.

## Frozen configuration

- Repository commit: `c7ebcd2c9d9052e91444c81a17cbceed5abe217a`
- Board/cable: `Xilinx X-MLCC-01 XFL1FVVDTE2WA`
- FPGA JTAG target: `xck26`, IDCODE `04724093`, IR length 12
- ARM DAP IDCODE: `5ba00477`, IR length 4
- UART: COM8, 115200 baud, 8-N-1, no flow control
- JTAG server: `tcp:127.0.0.1:3122`
- Vivado: 2022.2, build 3671981
- XSCT: 2022.2.0
- Prompt: `What is an FPGA?`

Artifacts:

| Artifact | SHA256 |
|---|---|
| `outputs/kv260_upstream_fixed/kv260bd_wrapper.bit` | `893131c2e905901d669629506cd0f82173cb4ed88cb20edea570e54bcdced8a1` |
| `outputs/kv260_upstream_fixed/llama_upstream_region0_fixed.elf` | `0b3ac1aa6b0e6db952e195f59d294c862a9f4624662f7e1011cdec49a4b2fc93` |
| `outputs/kv260_upstream_fixed/psu_init.tcl` | `b1afba0c79e8326871492d66dce9c7b98c4be02532d69e14ec2ee7fc12b33cdf` |
| SD `llama0.bin` corresponding host file | `38f65d06cfb579dcf874459b6216900fe9d49d44b32677d1d6c2d938b2e2bb35` (unverified provenance) |
| SD `llama1.bin` corresponding host file | `ebbfdfb112f5a839a56c46d4e879124022cfae67e06c61b0db4aff95d86e7180` (unverified provenance) |

The application ELF has `region_0` at `0x00036000`, size `0x722b4000`.

## Launch procedure

For every trial, serial capture started before executing:

```powershell
& 'F:\Xilinx2022\Vitis\2022.2\bin\xsct.bat' `
  'D:\JSA paper\work\run_kv260_upstream_fixed_region0_psinit_jtag.tcl'
```

That script selected temporary JTAG boot, reset the PS, applied the matching
`psu_init.tcl`, programmed the bitstream, released PS–PL isolation/reset,
downloaded the fixed ELF to Cortex-A53 #0, and continued execution.

## Observations

Each trial printed successful application startup, six FatFS return codes of
zero for opening, reading, and closing the two model files, `Load Finish!`,
tokenizer initialization/sort completion, the fixed prompt, a coherent model
response ending in `</s>`, and `Finish Decoding!`.

| Run | Raw log SHA256 | Response bytes | Response SHA256 | Elapsed (us) | Reported TPS | Total tokens |
|---:|---|---:|---|---:|---:|---:|
| 1 | `29925ebdb89891c09ee50103119854d7c1bc0ca0a15fe7e23d7c93f01ebb0a97` | 3224 | `2c8b802fb09fee4d538f84127b5f319b660fcb3bc0f5802e6e363004d531428c` | 140086542 | 5.032603 | 705 |
| 2 | `26844ecfdce322d36db4874252a793c4c3f972ce2d7e36a3b70d0a0f555ed487` | 3224 | `2c8b802fb09fee4d538f84127b5f319b660fcb3bc0f5802e6e363004d531428c` | 140092279 | 5.032398 | 705 |
| 3 | `f15d47f7c095e37a6b47bb50b305667d2239475185d913c69d46b4ffdaafdf66` | 3224 | `2c8b802fb09fee4d538f84127b5f319b660fcb3bc0f5802e6e363004d531428c` | 140093769 | 5.032343 | 705 |

The raw logs are retained under `D:\JSA paper\work` as
`p0_fixed_region0_run{1,2,3}_20260908.log`. Response hashes were calculated
after extracting the text between `LLM Response:` and `Elapsed PS` and
normalizing CRLF to LF. Timing lines and capture headers are not part of the
response hash.

## Proven boundary

This evidence proves that the exact bitstream/ELF/PS-init combination can boot,
mount the SD filesystem, access both weight files, initialize the tokenizer,
execute end-to-end target inference, and produce deterministic coherent text
across three reset cycles. The prior all-NaN/repeated-token-zero symptom is not
present with the fixed-`region_0` ELF.

It does **not** yet prove:

- that each `f_read` returned the requested byte count, because `wr_tot` is not
  printed;
- that SD data matches host data at representative DDR boundaries;
- the raw generated token-ID sequence, because this application prints text;
- that all AXI RRESP/BRESP values are error-free;
- that the two model files match the approved generation provenance;
- timing/route closure for bitstream SHA256 `893131c2...ced8a1`;
- source-to-RTL reproduction while the configured SpinalHDL version is 1.11.0
  and the imported RTL reports 1.10.2a.

## Next single experiment

Instrument only the PS application to print requested/actual model read byte
counts, sparse DDR checksums, and every raw output token ID. Do not change the
bitstream.

- **GO:** exact read counts, matching host/DDR probes, three identical raw-ID
  sequences, finite nonzero generation, and normal completion.
- **NO-GO:** short read, mismatched probe, raw-ID divergence, repeated zero,
  hang, reset, or file error.
- **Rollback:** restore the current fixed ELF and hashes above if application
  instrumentation changes inference behavior.
