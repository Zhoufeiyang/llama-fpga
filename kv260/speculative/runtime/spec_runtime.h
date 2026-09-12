#ifndef KV260_SPEC_RUNTIME_H
#define KV260_SPEC_RUNTIME_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SPEC_KMAX 4u
#define SPEC_TARGET_MAX (SPEC_KMAX + 1u)

#define SPEC_REG_START          0x100u
#define SPEC_REG_BATCH_K        0x104u
#define SPEC_REG_COMMITTED      0x108u
#define SPEC_REG_CANDIDATE0     0x110u
#define SPEC_REG_COMMIT         0x120u
#define SPEC_REG_ROLLBACK       0x124u
#define SPEC_REG_STATUS         0x130u
#define SPEC_REG_RESULT_COUNT   0x134u
#define SPEC_REG_INITIAL_TARGET 0x138u
#define SPEC_REG_TARGET0        0x140u
#define SPEC_REG_RESULT_ACK     0x154u

#define SPEC_STATUS_IDLE  (1u << 0)
#define SPEC_STATUS_ACTIVE (1u << 1)
#define SPEC_STATUS_FAULT (1u << 2)

typedef uint32_t (*spec_mmio_read32_fn)(void *context, uint32_t offset);
typedef void (*spec_mmio_write32_fn)(void *context, uint32_t offset,
                                     uint32_t value);
typedef int (*spec_draft_fn)(void *context, const uint16_t *prefix,
                             size_t prefix_length, uint16_t *next_token);
typedef int (*spec_verify_launch_fn)(void *context,
                                     const uint16_t *candidates, uint8_t k,
                                     uint16_t committed_length);
typedef int (*spec_emit_fn)(void *context, uint16_t token);

typedef enum {
    SPEC_STATE_IDLE = 0,
    SPEC_STATE_DRAFT,
    SPEC_STATE_TARGET_VERIFY,
    SPEC_STATE_READ_TARGET_RESULTS,
    SPEC_STATE_ACCEPT_OR_REJECT,
    SPEC_STATE_COMMIT_POINTER,
    SPEC_STATE_EMIT_TOKENS,
    SPEC_STATE_ACK_RESULTS,
    SPEC_STATE_COMPLETE,
    SPEC_STATE_ERROR
} spec_runtime_state_t;

typedef enum {
    SPEC_STEP_ERROR = -1,
    SPEC_STEP_WAITING = 0,
    SPEC_STEP_PROGRESS = 1,
    SPEC_STEP_COMPLETE = 2
} spec_step_result_t;

typedef enum {
    SPEC_ERROR_NONE = 0,
    SPEC_ERROR_ARGUMENT,
    SPEC_ERROR_DRAFT,
    SPEC_ERROR_PL_FAULT,
    SPEC_ERROR_TIMEOUT,
    SPEC_ERROR_EMIT
} spec_error_t;

typedef struct {
    spec_mmio_read32_fn read32;
    spec_mmio_write32_fn write32;
    void *mmio_context;
    spec_draft_fn draft;
    void *draft_context;
    spec_verify_launch_fn launch_verify;
    void *verify_context;
    spec_emit_fn emit;
    void *emit_context;
    uint32_t timeout_polls;
} spec_runtime_config_t;

typedef struct {
    spec_runtime_config_t config;
    spec_runtime_state_t state;
    spec_error_t error;
    uint16_t candidates[SPEC_KMAX];
    uint16_t targets[SPEC_TARGET_MAX];
    uint16_t emit_tokens[SPEC_TARGET_MAX];
    uint16_t seed_token;
    uint16_t initial_target;
    uint16_t committed_length;
    uint8_t k;
    uint8_t draft_count;
    uint8_t accepted_count;
    uint8_t emit_count;
    uint8_t emit_index;
    uint32_t poll_count;
} spec_runtime_t;

int spec_runtime_init(spec_runtime_t *runtime,
                      const spec_runtime_config_t *config);
int spec_runtime_begin(spec_runtime_t *runtime, uint16_t committed_length,
                       uint16_t seed_token, uint16_t initial_target, uint8_t k);
spec_step_result_t spec_runtime_step(spec_runtime_t *runtime);
int spec_runtime_is_terminal(const spec_runtime_t *runtime);
const char *spec_runtime_state_name(spec_runtime_state_t state);

#ifdef __cplusplus
}
#endif

#endif
