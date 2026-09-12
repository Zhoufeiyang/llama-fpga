#include "draft_model.h"

#include <stdio.h>

static int expect_prediction(draft_ngram_t *model, const uint16_t *prefix,
                             size_t count, uint16_t expected)
{
    uint16_t result = 0u;
    return draft_ngram_predict(model, prefix, count, &result) == 0 &&
           result == expected ? 0 : 1;
}

int main(void)
{
    draft_ngram_entry_t storage[32];
    draft_ngram_t model;
    const uint16_t corpus[] = {
        1u, 10u, 11u, 12u, 1u, 10u, 11u, 13u,
        1u, 10u, 11u, 12u, 1u, 10u, 11u, 12u
    };
    const uint16_t known[] = {1u, 10u, 11u};
    const uint16_t unknown[] = {8u, 9u};
    uint16_t rolling[3] = {1u, 10u, 11u};
    unsigned generated;
    int errors = 0;

    errors += draft_ngram_init(&model, storage, 32u, 2u) != 0;
    errors += draft_ngram_observe(&model, corpus,
                                  sizeof(corpus) / sizeof(corpus[0])) != 0;
    errors += expect_prediction(&model, known, 3u, 12u);
    errors += expect_prediction(&model, unknown, 2u, 2u);
    errors += model.observations !=
              sizeof(corpus) / sizeof(corpus[0]) - 2u;
    errors += model.dropped_observations != 0u;
    errors += draft_ngram_storage_bytes(4096u) !=
              4096u * sizeof(draft_ngram_entry_t);
    for (generated = 0u; generated < 100u; ++generated) {
        uint16_t next = 0u;
        errors += draft_ngram_predict(&model, rolling, 3u, &next) != 0;
        errors += next >= 32000u;
        rolling[0] = rolling[1];
        rolling[1] = rolling[2];
        rolling[2] = next;
        errors += draft_ngram_observe(&model, rolling, 3u) != 0;
    }
    draft_ngram_reset(&model);
    errors += model.observations != 0u;
    errors += expect_prediction(&model, known, 3u, 2u);

    if (errors != 0) {
        fprintf(stderr, "P7-D n-gram draft failed with %d errors\n", errors);
        return 1;
    }
    printf("P7D_NGRAM_DRAFT_GO TOKEN_IDS=UINT16 FIXED_STORAGE=1 BYTES_4096=%u DETERMINISTIC_TIEBREAK=1 CONTINUOUS_100=1\n",
           (unsigned)draft_ngram_storage_bytes(4096u));
    return 0;
}
