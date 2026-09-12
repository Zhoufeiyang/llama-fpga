#ifndef KV260_SPEC_RUNTIME_XILINX_H
#define KV260_SPEC_RUNTIME_XILINX_H

#include "spec_runtime.h"

#include "xil_types.h"

typedef struct {
    UINTPTR base_address;
    uint32_t launch_timeout_polls;
} spec_xilinx_context_t;

int spec_xilinx_runtime_init(spec_runtime_t *runtime,
                             spec_xilinx_context_t *platform,
                             UINTPTR base_address,
                             uint32_t timeout_polls,
                             spec_draft_fn draft, void *draft_context,
                             spec_emit_fn emit, void *emit_context);

int spec_xilinx_take_initial_target(spec_xilinx_context_t *platform,
                                    uint32_t timeout_polls,
                                    uint16_t *target);

#endif
