#include "draft_model.h"

#include <limits.h>
#include <string.h>

static uint32_t context_key(uint16_t older, uint16_t newer)
{
    return ((uint32_t)older << 16) | newer;
}

static size_t entry_hash(uint32_t context, uint16_t token, size_t capacity)
{
    uint32_t mixed = context ^ ((uint32_t)token * 0x9e3779b1u);
    mixed ^= mixed >> 16;
    mixed *= 0x85ebca6bu;
    mixed ^= mixed >> 13;
    return (size_t)(mixed % capacity);
}

int draft_ngram_init(draft_ngram_t *model, draft_ngram_entry_t *storage,
                     size_t capacity, uint16_t fallback_token)
{
    if (model == NULL || storage == NULL || capacity == 0u) {
        return -1;
    }
    model->entries = storage;
    model->capacity = capacity;
    model->fallback_token = fallback_token;
    model->observations = 0u;
    model->dropped_observations = 0u;
    memset(storage, 0, capacity * sizeof(*storage));
    return 0;
}

void draft_ngram_reset(draft_ngram_t *model)
{
    if (model != NULL && model->entries != NULL && model->capacity != 0u) {
        memset(model->entries, 0, model->capacity * sizeof(*model->entries));
        model->observations = 0u;
        model->dropped_observations = 0u;
    }
}

int draft_ngram_observe(draft_ngram_t *model, const uint16_t *tokens,
                        size_t token_count)
{
    size_t i;
    if (model == NULL || model->entries == NULL || model->capacity == 0u ||
        tokens == NULL) {
        return -1;
    }
    if (token_count < 3u) {
        return 0;
    }
    for (i = 2u; i < token_count; ++i) {
        uint32_t context = context_key(tokens[i - 2u], tokens[i - 1u]);
        size_t start = entry_hash(context, tokens[i], model->capacity);
        size_t probe;
        int stored = 0;
        for (probe = 0u; probe < model->capacity; ++probe) {
            draft_ngram_entry_t *entry =
                &model->entries[(start + probe) % model->capacity];
            if (entry->used == 0u) {
                entry->context = context;
                entry->token = tokens[i];
                entry->count = 1u;
                entry->used = 1u;
                stored = 1;
                break;
            }
            if (entry->context == context && entry->token == tokens[i]) {
                if (entry->count != UINT16_MAX) {
                    ++entry->count;
                }
                stored = 1;
                break;
            }
        }
        ++model->observations;
        if (stored == 0) {
            ++model->dropped_observations;
        }
    }
    return 0;
}

int draft_ngram_predict(const draft_ngram_t *model, const uint16_t *prefix,
                        size_t prefix_length, uint16_t *next_token)
{
    uint32_t context;
    uint16_t best_token;
    uint16_t best_count = 0u;
    size_t i;
    if (model == NULL || model->entries == NULL || prefix == NULL ||
        next_token == NULL || model->capacity == 0u) {
        return -1;
    }
    best_token = model->fallback_token;
    if (prefix_length < 2u) {
        *next_token = best_token;
        return 0;
    }
    context = context_key(prefix[prefix_length - 2u],
                          prefix[prefix_length - 1u]);
    for (i = 0u; i < model->capacity; ++i) {
        const draft_ngram_entry_t *entry = &model->entries[i];
        if (entry->used != 0u && entry->context == context &&
            (entry->count > best_count ||
             (entry->count == best_count && entry->token < best_token))) {
            best_count = entry->count;
            best_token = entry->token;
        }
    }
    *next_token = best_token;
    return 0;
}

int draft_ngram_callback(void *context, const uint16_t *prefix,
                         size_t prefix_length, uint16_t *next_token)
{
    return draft_ngram_predict((const draft_ngram_t *)context, prefix,
                               prefix_length, next_token);
}

size_t draft_ngram_storage_bytes(size_t capacity)
{
    if (capacity > SIZE_MAX / sizeof(draft_ngram_entry_t)) {
        return SIZE_MAX;
    }
    return capacity * sizeof(draft_ngram_entry_t);
}
