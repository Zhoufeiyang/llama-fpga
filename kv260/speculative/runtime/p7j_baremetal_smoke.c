#include "spec_runtime_xilinx.h"
#include "tiny_draft.h"

#define TARGET_DERIVED_DRAFT_ADDR ((const void *)(UINTPTR)0x722F6000ULL)
#define TARGET_DERIVED_DRAFT_BYTES 512064u

static int emit_stub(void *context, uint16_t token)
{
    (void)context;
    (void)token;
    return 1;
}

int main(void)
{
    static tiny_draft_model_t draft;
    static spec_runtime_t runtime;
    static spec_xilinx_context_t platform;
    if (tiny_draft_model_init(&draft, TARGET_DERIVED_DRAFT_ADDR,
                              TARGET_DERIVED_DRAFT_BYTES) != TINY_DRAFT_OK) {
        return 1;
    }
    return spec_xilinx_runtime_init(&runtime, &platform,
                                    (UINTPTR)0x1000000000ULL, 1000000u,
                                    tiny_draft_callback, &draft,
                                    emit_stub, 0);
}
