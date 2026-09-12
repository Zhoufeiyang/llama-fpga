#include "tiny_draft.h"

#include <string.h>

static uint16_t load_u16(const uint8_t *p)
{
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static uint32_t load_u32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static float load_f32(const uint8_t *p)
{
    uint32_t bits = load_u32(p);
    float value;
    memcpy(&value, &bits, sizeof(value));
    return value;
}

static int finite_positive(float value)
{
    uint32_t bits;
    memcpy(&bits, &value, sizeof(bits));
    /* Positive, normal finite IEEE-754 number. */
    return (bits & 0x80000000u) == 0u &&
           (bits & 0x7f800000u) != 0u &&
           (bits & 0x7f800000u) != 0x7f800000u;
}

static uint32_t crc32(const uint8_t *data, size_t length)
{
    uint32_t crc = 0xffffffffu;
    size_t i;
    unsigned bit;
    for (i = 0u; i < length; ++i) {
        crc ^= data[i];
        for (bit = 0u; bit < 8u; ++bit) {
            uint32_t mask = (uint32_t)-(int)(crc & 1u);
            crc = (crc >> 1) ^ (0xedb88320u & mask);
        }
    }
    return ~crc;
}

int tiny_draft_model_init(tiny_draft_model_t *model, const void *blob,
                          size_t blob_bytes)
{
    const uint8_t *p = (const uint8_t *)blob;
    uint32_t total, vocab, emb_off, emb_bytes, out_off, out_bytes;
    uint16_t header, version, dim;
    size_t matrix_bytes;

    if (model == NULL || p == NULL) {
        return TINY_DRAFT_BAD_ARGUMENT;
    }
    memset(model, 0, sizeof(*model));
    if (blob_bytes < TINY_DRAFT_HEADER_BYTES ||
        load_u32(p + 0u) != TINY_DRAFT_MAGIC) {
        return TINY_DRAFT_BAD_FORMAT;
    }
    version = load_u16(p + 4u);
    header = load_u16(p + 6u);
    total = load_u32(p + 8u);
    vocab = load_u32(p + 12u);
    dim = load_u16(p + 16u);
    emb_off = load_u32(p + 20u);
    emb_bytes = load_u32(p + 24u);
    out_off = load_u32(p + 28u);
    out_bytes = load_u32(p + 32u);
    if (version != TINY_DRAFT_VERSION || header != TINY_DRAFT_HEADER_BYTES ||
        total < header || (size_t)total != blob_bytes ||
        vocab == 0u || vocab > TINY_DRAFT_MAX_VOCAB ||
        dim == 0u || dim > TINY_DRAFT_MAX_DIM) {
        return TINY_DRAFT_BAD_FORMAT;
    }
    if ((size_t)vocab > SIZE_MAX / (size_t)dim) {
        return TINY_DRAFT_BOUNDS;
    }
    matrix_bytes = (size_t)vocab * (size_t)dim;
    if (emb_bytes != (uint32_t)matrix_bytes || out_bytes != (uint32_t)matrix_bytes ||
        emb_off != header || out_off != emb_off + emb_bytes ||
        (size_t)out_off > blob_bytes ||
        (size_t)out_bytes > blob_bytes - (size_t)out_off ||
        (size_t)out_off + out_bytes != blob_bytes) {
        return TINY_DRAFT_BOUNDS;
    }
    if (!finite_positive(load_f32(p + 36u)) ||
        !finite_positive(load_f32(p + 40u)) ||
        crc32(p + header, blob_bytes - header) != load_u32(p + 44u)) {
        return crc32(p + header, blob_bytes - header) == load_u32(p + 44u) ?
               TINY_DRAFT_BAD_FORMAT : TINY_DRAFT_BAD_CRC;
    }
    model->blob = p;
    model->blob_bytes = blob_bytes;
    model->embedding_q = (const int8_t *)(p + emb_off);
    model->output_q = (const int8_t *)(p + out_off);
    model->vocab_size = vocab;
    model->input_dim = dim;
    model->embedding_scale = load_f32(p + 36u);
    model->output_scale = load_f32(p + 40u);
    return TINY_DRAFT_OK;
}

int tiny_draft_model_validate(const tiny_draft_model_t *model)
{
    tiny_draft_model_t checked;
    if (model == NULL || model->blob == NULL) {
        return TINY_DRAFT_BAD_ARGUMENT;
    }
    return tiny_draft_model_init(&checked, model->blob, model->blob_bytes);
}

int tiny_draft_logits(const tiny_draft_model_t *model, uint16_t token_id,
                      float *logits, size_t logits_count)
{
    uint16_t j;
    uint32_t row;
    if (model == NULL || model->embedding_q == NULL || model->output_q == NULL ||
        logits == NULL) {
        return TINY_DRAFT_BAD_ARGUMENT;
    }
    if (tiny_draft_model_validate(model) != TINY_DRAFT_OK) {
        return TINY_DRAFT_BAD_FORMAT;
    }
    if ((uint32_t)token_id >= model->vocab_size) {
        return TINY_DRAFT_TOKEN;
    }
    if (logits_count < model->vocab_size) {
        return TINY_DRAFT_OUTPUT;
    }
    row = (uint32_t)token_id * model->input_dim;
    for (uint32_t out = 0u; out < model->vocab_size; ++out) {
        float acc = 0.0f;
        uint32_t out_row = out * model->input_dim;
        for (j = 0u; j < model->input_dim; ++j) {
            float hidden = (float)model->embedding_q[row + j] *
                           model->embedding_scale;
            if (hidden < 0.0f) {
                hidden = 0.0f;
            }
            acc += ((float)model->output_q[out_row + j] *
                    model->output_scale) * hidden;
        }
        logits[out] = acc;
    }
    return TINY_DRAFT_OK;
}

int tiny_draft_greedy(const tiny_draft_model_t *model, uint16_t token_id,
                      uint16_t *next_token)
{
    uint32_t out;
    uint16_t j;
    uint32_t row;
    float best = 0.0f;
    uint32_t best_id = 0u;
    int initialized = 0;
    if (model == NULL || next_token == NULL || model->embedding_q == NULL ||
        model->output_q == NULL) {
        return TINY_DRAFT_BAD_ARGUMENT;
    }
    if (tiny_draft_model_validate(model) != TINY_DRAFT_OK) {
        return TINY_DRAFT_BAD_FORMAT;
    }
    if ((uint32_t)token_id >= model->vocab_size) {
        return TINY_DRAFT_TOKEN;
    }
    row = (uint32_t)token_id * model->input_dim;
    for (out = 0u; out < model->vocab_size; ++out) {
        float score = 0.0f;
        uint32_t out_row = out * model->input_dim;
        for (j = 0u; j < model->input_dim; ++j) {
            float hidden = (float)model->embedding_q[row + j] *
                           model->embedding_scale;
            if (hidden < 0.0f) {
                hidden = 0.0f;
            }
            score += ((float)model->output_q[out_row + j] *
                      model->output_scale) * hidden;
        }
        if (!initialized || score > best) {
            initialized = 1;
            best = score;
            best_id = out;
        }
    }
    *next_token = (uint16_t)best_id;
    return TINY_DRAFT_OK;
}

int tiny_draft_callback(void *context, const uint16_t *prefix,
                        size_t prefix_length, uint16_t *next_token)
{
    if (context == NULL || prefix == NULL || prefix_length == 0u) {
        return TINY_DRAFT_BAD_ARGUMENT;
    }
    return tiny_draft_greedy((const tiny_draft_model_t *)context,
                             prefix[prefix_length - 1u], next_token);
}

size_t tiny_draft_model_bytes(const tiny_draft_model_t *model)
{
    return model == NULL ? 0u : model->blob_bytes;
}
