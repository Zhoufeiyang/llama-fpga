# KV260 Vitis 2022.2 application build

The KV260 PL address generator reads the second model-weight bank from the
fixed DDR base address `0x00036000`. A default Vitis 2022.2 rebuild can place
the large `region_0` C object at another address, which makes inference return
token ID 0 (`<unk>`) even though both weight files load successfully.

After generating a standalone A53 application from `kv260bd_wrapper.xsa`, run:

```powershell
& 'F:\Xilinx2022\Vitis\2022.2\bin\xsct.bat' `
  '.\kv260\build_fixed_region0.tcl' `
  '<generated_app_directory>'
```

The script copies `kv260_sdk.c`, enables GCC common-symbol layout, patches the
generated linker script, builds `executable.elf`, and verifies that:

```text
0000000000036000 00000000722b4000 B region_0
```

Do not deploy an ELF unless the script prints `KV260_BUILD_OK`. This is an
application-linking fix; it does not require Vivado synthesis, implementation,
or a new bitstream.
