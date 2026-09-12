#include "spec_runtime_xilinx.h"

#include "xil_io.h"

#define LEGACY_RESULT_OFFSET 0x004u
#define LEGACY_RESULT_CLEAR_OFFSET 0x080u
#define SPEC_ATTENTION_OFFSET 0x028u
#define TOKEN_LAUNCH_OFFSET 0x000u
#define LEGACY_RESULT_VALID (1u << 31)
#define TOKEN_VALID_DECODE 0x00090000u

static uint32_t xilinx_read32(void *context, uint32_t offset)
{
    spec_xilinx_context_t *platform = (spec_xilinx_context_t *)context;
    return Xil_In32(platform->base_address + (UINTPTR)offset);
}

static void xilinx_write32(void *context, uint32_t offset, uint32_t value)
{
    spec_xilinx_context_t *platform = (spec_xilinx_context_t *)context;
    Xil_Out32(platform->base_address + (UINTPTR)offset, value);
}

/*
 * Compatibility launcher for the current production token interface. The
 * query register is held constant until that candidate's LM-head result has
 * arrived, so KV/attention addressing cannot observe the following q value.
 * A future batched token ingress may replace only this callback.
 */
static int xilinx_launch_verify(void *context, const uint16_t *candidates,
                                uint8_t k, uint16_t committed_length)
{
    spec_xilinx_context_t *platform = (spec_xilinx_context_t *)context;
    uint8_t q;
    for (q = 0u; q < k; ++q) {
        uint32_t polls = 0u;
        uint32_t attention = 1u | ((uint32_t)q << 2) |
                             ((uint32_t)committed_length << 16);
        Xil_Out32(platform->base_address + SPEC_ATTENTION_OFFSET, attention);
        Xil_Out32(platform->base_address + TOKEN_LAUNCH_OFFSET,
                  TOKEN_VALID_DECODE | candidates[q]);
        while ((Xil_In32(platform->base_address + SPEC_REG_RESULT_COUNT) &
                0x7u) < (uint32_t)q + 2u) {
            if ((Xil_In32(platform->base_address + SPEC_REG_STATUS) &
                 SPEC_STATUS_FAULT) != 0u ||
                ++polls >= platform->launch_timeout_polls) {
                return -1;
            }
        }
    }
    return 0;
}

int spec_xilinx_runtime_init(spec_runtime_t *runtime,
                             spec_xilinx_context_t *platform,
                             UINTPTR base_address,
                             uint32_t timeout_polls,
                             spec_draft_fn draft, void *draft_context,
                             spec_emit_fn emit, void *emit_context)
{
    spec_runtime_config_t config;
    if (runtime == NULL || platform == NULL || base_address == (UINTPTR)0u ||
        timeout_polls == 0u) {
        return -1;
    }
    platform->base_address = base_address;
    platform->launch_timeout_polls = timeout_polls;
    config.read32 = xilinx_read32;
    config.write32 = xilinx_write32;
    config.mmio_context = platform;
    config.draft = draft;
    config.draft_context = draft_context;
    config.launch_verify = xilinx_launch_verify;
    config.verify_context = platform;
    config.emit = emit;
    config.emit_context = emit_context;
    config.timeout_polls = timeout_polls;
    return spec_runtime_init(runtime, &config);
}

int spec_xilinx_take_initial_target(spec_xilinx_context_t *platform,
                                    uint32_t timeout_polls,
                                    uint16_t *target)
{
    uint32_t value = 0u;
    uint32_t polls;
    if (platform == NULL || target == NULL || timeout_polls == 0u) {
        return -1;
    }
    for (polls = 0u; polls < timeout_polls; ++polls) {
        value = Xil_In32(platform->base_address + LEGACY_RESULT_OFFSET);
        if ((value & LEGACY_RESULT_VALID) != 0u) {
            *target = (uint16_t)((value >> 16) & 0x7fffu);
            Xil_Out32(platform->base_address + LEGACY_RESULT_CLEAR_OFFSET, 1u);
            return 0;
        }
    }
    return -1;
}
