#include "tiny_draft.h"

#include <stdio.h>
#include <stdlib.h>

static float logits[ TINY_DRAFT_MAX_VOCAB ];

int main(int argc, char **argv)
{
    FILE *input;
    FILE *output;
    long file_size;
    uint8_t *blob;
    tiny_draft_model_t model;
    const uint16_t tokens[] = {0u, 1u, 7u, 42u, 1234u, 31999u};
    size_t i;
    int errors = 0;

    if (argc != 3) {
        fprintf(stderr, "usage: test_tiny_draft MODEL LOGITS\n");
        return 2;
    }
    input = fopen(argv[1], "rb");
    if (input == NULL || fseek(input, 0, SEEK_END) != 0 ||
        (file_size = ftell(input)) <= 0 || fseek(input, 0, SEEK_SET) != 0) {
        fprintf(stderr, "P7F_NO_GO input\n");
        return 2;
    }
    blob = (uint8_t *)malloc((size_t)file_size);
    if (blob == NULL || fread(blob, 1u, (size_t)file_size, input) !=
        (size_t)file_size) {
        fprintf(stderr, "P7F_NO_GO read\n");
        fclose(input);
        free(blob);
        return 2;
    }
    fclose(input);
    errors += tiny_draft_model_init(&model, blob, (size_t)file_size) != 0;
    errors += model.vocab_size != 32000u || model.input_dim != 8u;
    /* Exercise the strict parser before any inference is accepted. */
    {
        tiny_draft_model_t rejected;
        uint8_t saved_crc = blob[44u];
        blob[44u] ^= 0x01u;
        errors += tiny_draft_model_init(&rejected, blob,
                                        (size_t)file_size) != TINY_DRAFT_BAD_CRC;
        blob[44u] = saved_crc;
        errors += tiny_draft_model_init(&rejected, blob,
                                        (size_t)file_size - 1u) == TINY_DRAFT_OK;
        errors += tiny_draft_logits(&model, 32000u, logits,
                                    TINY_DRAFT_MAX_VOCAB) != TINY_DRAFT_TOKEN;
        errors += tiny_draft_logits(&model, 0u, logits, 1u) != TINY_DRAFT_OUTPUT;
    }
    output = fopen(argv[2], "wb");
    if (output == NULL) {
        free(blob);
        return 2;
    }
    for (i = 0u; i < sizeof(tokens) / sizeof(tokens[0]); ++i) {
        uint16_t prediction = 0u;
        errors += tiny_draft_logits(&model, tokens[i], logits,
                                    TINY_DRAFT_MAX_VOCAB) != 0;
        errors += tiny_draft_greedy(&model, tokens[i], &prediction) != 0;
        errors += fwrite(logits, sizeof(float), model.vocab_size, output) !=
                  model.vocab_size;
        printf("P7F_C_TOKEN token=%u argmax=%u\n",
               (unsigned)tokens[i], (unsigned)prediction);
    }
    fclose(output);
    free(blob);
    if (errors != 0) {
        fprintf(stderr, "P7F_TINY_DRAFT_NO_GO errors=%d\n", errors);
        return 1;
    }
    printf("P7F_TINY_DRAFT_C_GO VOCAB=32000 DIM=8 INT8_MATRICES=2 CRC32=1 GREEDY=1\n");
    return 0;
}
