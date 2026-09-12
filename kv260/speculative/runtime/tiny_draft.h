#ifndef KV260_TINY_DRAFT_H
#define KV260_TINY_DRAFT_H

#include <stddef.h>
#include <stdint.h>

/* P7-F model format: little-endian, 64-byte header followed by two int8 matrices. */
#define TINY_DRAFT_MAGIC 0x54445246u /* "FRDT" */
#define TINY_DRAFT_VERSION 1u
#define TINY_DRAFT_HEADER_BYTES 64u
#define TINY_DRAFT_MAX_VOCAB 65536u
#define TINY_DRAFT_MAX_DIM 64u

typedef enum {
    TINY_DRAFT_OK = 0,
    TINY_DRAFT_BAD_ARGUMENT = -1,
    TINY_DRAFT_BAD_FORMAT = -2,
    TINY_DRAFT_BAD_CRC = -3,
    TINY_DRAFT_BOUNDS = -4,
    TINY_DRAFT_TOKEN = -5,
    TINY_DRAFT_OUTPUT = -6
} tiny_draft_status_t;

typedef struct {
    const uint8_t *blob;
    size_t blob_bytes;
    const int8_t *embedding_q;
    const int8_t *output_q;
    uint32_t vocab_size;
    uint16_t input_dim;
    float embedding_scale;
    float output_scale;
} tiny_draft_model_t;

/* Validates every header, offset/size relation, scale and the payload CRC. */
int tiny_draft_model_init(tiny_draft_model_t *model, const void *blob,
                          size_t blob_bytes);
int tiny_draft_model_validate(const tiny_draft_model_t *model);

/* One-layer quantized MLP: ReLU(dequantized embedding[token]) then W_out. */
int tiny_draft_logits(const tiny_draft_model_t *model, uint16_t token_id,
                      float *logits, size_t logits_count);
int tiny_draft_greedy(const tiny_draft_model_t *model, uint16_t token_id,
                      uint16_t *next_token);
int tiny_draft_callback(void *context, const uint16_t *prefix,
                        size_t prefix_length, uint16_t *next_token);

size_t tiny_draft_model_bytes(const tiny_draft_model_t *model);

#endif
