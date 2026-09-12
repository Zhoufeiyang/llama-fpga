#include "spec_runtime_xilinx.h"

static int draft_stub(void *context, const uint16_t *prefix,
                      size_t prefix_length, uint16_t *next_token)
{
    (void)context;
    *next_token = (uint16_t)(prefix[prefix_length - 1u] + 1u);
    return 0;
}

static int emit_stub(void *context, uint16_t token)
{
    (void)context;
    (void)token;
    return 1;
}

int main(void)
{
    spec_runtime_t runtime;
    spec_xilinx_context_t platform;
    return spec_xilinx_runtime_init(&runtime, &platform,
                                    (UINTPTR)0x1000000000ULL, 1000000u,
                                    draft_stub, 0, emit_stub, 0);
}
