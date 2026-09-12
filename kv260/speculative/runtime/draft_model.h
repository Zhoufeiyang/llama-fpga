#ifndef KV260_DRAFT_MODEL_H
#define KV260_DRAFT_MODEL_H

#include <stddef.h>
#include <stdint.h>

typedef struct {
    uint32_t context;
    uint16_t token;
    uint16_t count;
    uint8_t used;
    uint8_t reserved[3];
} draft_ngram_entry_t;

typedef struct {
    draft_ngram_entry_t *entries;
    size_t capacity;
    uint16_t fallback_token;
    uint32_t observations;
    uint32_t dropped_observations;
} draft_ngram_t;

int draft_ngram_init(draft_ngram_t *model, draft_ngram_entry_t *storage,
                     size_t capacity, uint16_t fallback_token);
void draft_ngram_reset(draft_ngram_t *model);
int draft_ngram_observe(draft_ngram_t *model, const uint16_t *tokens,
                        size_t token_count);
int draft_ngram_predict(const draft_ngram_t *model, const uint16_t *prefix,
                        size_t prefix_length, uint16_t *next_token);
int draft_ngram_callback(void *context, const uint16_t *prefix,
                         size_t prefix_length, uint16_t *next_token);
size_t draft_ngram_storage_bytes(size_t capacity);

#endif
