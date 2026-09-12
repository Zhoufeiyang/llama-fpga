#include "spec_runtime_xilinx.h"
#include "draft_model.h"

#define DRAFT_ENTRIES 4096u

static draft_ngram_entry_t draft_storage[DRAFT_ENTRIES];

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
    draft_ngram_t draft;
    if (draft_ngram_init(&draft, draft_storage, DRAFT_ENTRIES, 2u) != 0) {
        return 1;
    }
    return spec_xilinx_runtime_init(&runtime, &platform,
                                    (UINTPTR)0x1000000000ULL, 1000000u,
                                    draft_ngram_callback, &draft,
                                    emit_stub, 0);
}
