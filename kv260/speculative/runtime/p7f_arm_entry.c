#include "tiny_draft.h"

/* Freestanding A53 entry point: the caller owns model storage and has no heap dependency. */
int p7f_tiny_draft_arm_smoke(const void *model_blob, size_t model_bytes,
                             uint16_t token_id, uint16_t *next_token)
{
    tiny_draft_model_t model;
    if (tiny_draft_model_init(&model, model_blob, model_bytes) != TINY_DRAFT_OK) {
        return -1;
    }
    return tiny_draft_greedy(&model, token_id, next_token);
}
