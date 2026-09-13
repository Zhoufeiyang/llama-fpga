#include "spec_runtime.h"

#include <string.h>

static void fail_transaction(spec_runtime_t *runtime, spec_error_t error)
{
    runtime->config.write32(runtime->config.mmio_context, SPEC_REG_ROLLBACK, 1u);
    runtime->config.write32(runtime->config.mmio_context, SPEC_REG_ATTENTION, 0u);
    runtime->config.write32(runtime->config.mmio_context, SPEC_REG_RESULT_ACK, 1u);
    runtime->error = error;
    ++runtime->metrics.transactions_failed;
    ++runtime->metrics.rollback_transactions;
    if (runtime->config.enable_target_fallback != 0u) {
        runtime->emit_tokens[0] = runtime->initial_target;
        runtime->emit_count = 1u;
        runtime->emit_index = 0u;
        runtime->accepted_count = 0u;
        runtime->state = SPEC_STATE_EMIT_FALLBACK;
        ++runtime->metrics.fallback_invocations;
    } else {
        runtime->state = SPEC_STATE_ERROR;
    }
}

int spec_runtime_init(spec_runtime_t *runtime,
                      const spec_runtime_config_t *config)
{
    if (runtime == NULL || config == NULL || config->read32 == NULL ||
        config->write32 == NULL || config->draft == NULL ||
        config->launch_verify == NULL || config->emit == NULL ||
        config->timeout_polls == 0u) {
        return -1;
    }
    memset(runtime, 0, sizeof(*runtime));
    runtime->config = *config;
    runtime->state = SPEC_STATE_IDLE;
    return 0;
}

int spec_runtime_begin(spec_runtime_t *runtime, uint16_t committed_length,
                       uint16_t seed_token, uint16_t initial_target, uint8_t k)
{
    if (runtime == NULL || k == 0u || k > SPEC_KMAX ||
        committed_length > SPEC_MAX_CONTEXT ||
        (uint32_t)committed_length + k > SPEC_MAX_CONTEXT ||
        (runtime->state != SPEC_STATE_IDLE &&
         runtime->state != SPEC_STATE_COMPLETE &&
         runtime->state != SPEC_STATE_ERROR)) {
        return -1;
    }
    runtime->state = SPEC_STATE_DRAFT;
    runtime->error = SPEC_ERROR_NONE;
    runtime->seed_token = seed_token;
    runtime->initial_target = initial_target;
    runtime->committed_length = committed_length;
    runtime->k = k;
    runtime->draft_count = 0u;
    runtime->accepted_count = 0u;
    runtime->emit_count = 0u;
    runtime->emit_index = 0u;
    runtime->poll_count = 0u;
    memset(runtime->candidates, 0, sizeof(runtime->candidates));
    memset(runtime->targets, 0, sizeof(runtime->targets));
    memset(runtime->emit_tokens, 0, sizeof(runtime->emit_tokens));
    ++runtime->metrics.transactions_started;
    return 0;
}

spec_step_result_t spec_runtime_step(spec_runtime_t *runtime)
{
    uint32_t status;
    uint32_t count;
    uint8_t i;

    if (runtime == NULL) {
        return SPEC_STEP_ERROR;
    }

    switch (runtime->state) {
    case SPEC_STATE_DRAFT: {
        uint16_t prefix[SPEC_TARGET_MAX];
        uint16_t next_token = 0u;
        prefix[0] = runtime->seed_token;
        for (i = 0u; i < runtime->draft_count; ++i) {
            prefix[i + 1u] = runtime->candidates[i];
        }
        if (runtime->config.draft(runtime->config.draft_context, prefix,
                                  (size_t)runtime->draft_count + 1u,
                                  &next_token) != 0) {
            fail_transaction(runtime, SPEC_ERROR_DRAFT);
            return runtime->state == SPEC_STATE_ERROR ? SPEC_STEP_ERROR :
                                                        SPEC_STEP_PROGRESS;
        }
        runtime->candidates[runtime->draft_count++] = next_token;
        ++runtime->metrics.draft_tokens;
        if (runtime->draft_count == runtime->k) {
            runtime->state = SPEC_STATE_TARGET_VERIFY;
        }
        return SPEC_STEP_PROGRESS;
    }

    case SPEC_STATE_TARGET_VERIFY:
        runtime->config.write32(runtime->config.mmio_context,
                                SPEC_REG_COMMITTED,
                                runtime->committed_length);
        runtime->config.write32(runtime->config.mmio_context,
                                SPEC_REG_BATCH_K, runtime->k);
        runtime->config.write32(runtime->config.mmio_context,
                                SPEC_REG_INITIAL_TARGET,
                                runtime->initial_target);
        for (i = 0u; i < runtime->k; ++i) {
            runtime->config.write32(runtime->config.mmio_context,
                                    SPEC_REG_CANDIDATE0 + 4u * i,
                                    runtime->candidates[i]);
        }
        /* Enable the production candidate ingress before START snapshots K
         * and candidate IDs. Candidate q is carried in the hardware stream,
         * so software does not rewrite this register between candidates. */
        runtime->config.write32(runtime->config.mmio_context,
                                SPEC_REG_ATTENTION,
                                SPEC_ATTENTION_ENABLE |
                                ((uint32_t)runtime->committed_length <<
                                 SPEC_ATTENTION_COMMITTED_SHIFT));
        runtime->config.write32(runtime->config.mmio_context,
                                SPEC_REG_START, 1u);
        if (runtime->config.launch_verify(
                runtime->config.verify_context, runtime->candidates,
                runtime->k, runtime->committed_length) != 0) {
            fail_transaction(runtime, SPEC_ERROR_PL_FAULT);
            return SPEC_STEP_ERROR;
        }
        runtime->metrics.target_result_tokens += (uint64_t)runtime->k + 1u;
        runtime->poll_count = 0u;
        runtime->state = SPEC_STATE_READ_TARGET_RESULTS;
        return SPEC_STEP_PROGRESS;

    case SPEC_STATE_READ_TARGET_RESULTS:
        ++runtime->metrics.result_polls;
        status = runtime->config.read32(runtime->config.mmio_context,
                                        SPEC_REG_STATUS);
        if ((status & SPEC_STATUS_FAULT) != 0u) {
            fail_transaction(runtime, SPEC_ERROR_PL_FAULT);
            return SPEC_STEP_ERROR;
        }
        count = runtime->config.read32(runtime->config.mmio_context,
                                       SPEC_REG_RESULT_COUNT) & 0x7u;
        if (count < (uint32_t)runtime->k + 1u) {
            if (++runtime->poll_count >= runtime->config.timeout_polls) {
                fail_transaction(runtime, SPEC_ERROR_TIMEOUT);
                return SPEC_STEP_ERROR;
            }
            return SPEC_STEP_WAITING;
        }
        for (i = 0u; i <= runtime->k; ++i) {
            runtime->targets[i] = (uint16_t)
                runtime->config.read32(runtime->config.mmio_context,
                                       SPEC_REG_TARGET0 + 4u * i);
        }
        runtime->metrics.hardware_weight_bytes +=
            runtime->config.read32(runtime->config.mmio_context,
                                   SPEC_REG_PERF_WEIGHT_BYTES);
        runtime->metrics.hardware_kv_read_bytes +=
            runtime->config.read32(runtime->config.mmio_context,
                                   SPEC_REG_PERF_KV_READ_BYTES);
        runtime->metrics.hardware_kv_write_bytes +=
            runtime->config.read32(runtime->config.mmio_context,
                                   SPEC_REG_PERF_KV_WRITE_BYTES);
        runtime->metrics.hardware_verify_cycles +=
            runtime->config.read32(runtime->config.mmio_context,
                                   SPEC_REG_PERF_VERIFY_CYCLES);
        runtime->metrics.hardware_memory_stall_cycles +=
            runtime->config.read32(runtime->config.mmio_context,
                                   SPEC_REG_PERF_MEMORY_STALL_CYCLES);
        runtime->state = SPEC_STATE_ACCEPT_OR_REJECT;
        return SPEC_STEP_PROGRESS;

    case SPEC_STATE_ACCEPT_OR_REJECT:
        runtime->accepted_count = runtime->k;
        for (i = 0u; i < runtime->k; ++i) {
            if (runtime->candidates[i] != runtime->targets[i]) {
                runtime->accepted_count = i;
                break;
            }
        }
        for (i = 0u; i < runtime->accepted_count; ++i) {
            runtime->emit_tokens[i] = runtime->candidates[i];
        }
        runtime->emit_tokens[runtime->accepted_count] =
            runtime->targets[runtime->accepted_count];
        runtime->emit_count = runtime->accepted_count + 1u;
        runtime->metrics.accepted_draft_tokens += runtime->accepted_count;
        if (runtime->accepted_count == runtime->k) {
            ++runtime->metrics.all_match_transactions;
        } else {
            ++runtime->metrics.mismatch_transactions;
        }
        runtime->emit_index = 0u;
        runtime->state = SPEC_STATE_COMMIT_POINTER;
        return SPEC_STEP_PROGRESS;

    case SPEC_STATE_COMMIT_POINTER:
        runtime->config.write32(runtime->config.mmio_context, SPEC_REG_COMMIT,
                                0x100u | runtime->accepted_count);
        runtime->config.write32(runtime->config.mmio_context,
                                SPEC_REG_ATTENTION, 0u);
        runtime->committed_length = (uint16_t)
            (runtime->committed_length + runtime->accepted_count);
        runtime->state = SPEC_STATE_EMIT_TOKENS;
        return SPEC_STEP_PROGRESS;

    case SPEC_STATE_EMIT_TOKENS: {
        int emit_result;
        if (runtime->emit_index == runtime->emit_count) {
            runtime->state = SPEC_STATE_ACK_RESULTS;
            return SPEC_STEP_PROGRESS;
        }
        emit_result = runtime->config.emit(
            runtime->config.emit_context,
            runtime->emit_tokens[runtime->emit_index]);
        if (emit_result < 0) {
            runtime->config.write32(runtime->config.mmio_context,
                                    SPEC_REG_RESULT_ACK, 1u);
            runtime->error = SPEC_ERROR_EMIT;
            runtime->state = SPEC_STATE_ERROR;
            ++runtime->metrics.transactions_failed;
            return SPEC_STEP_ERROR;
        }
        if (emit_result == 0) {
            ++runtime->metrics.output_backpressure_stalls;
            return SPEC_STEP_WAITING;
        }
        ++runtime->emit_index;
        ++runtime->metrics.emitted_tokens;
        return SPEC_STEP_PROGRESS;
    }

    case SPEC_STATE_ACK_RESULTS:
        runtime->config.write32(runtime->config.mmio_context,
                                SPEC_REG_RESULT_ACK, 1u);
        runtime->state = SPEC_STATE_COMPLETE;
        ++runtime->metrics.transactions_completed;
        return SPEC_STEP_COMPLETE;

    case SPEC_STATE_EMIT_FALLBACK: {
        int emit_result = runtime->config.emit(runtime->config.emit_context,
                                               runtime->initial_target);
        if (emit_result < 0) {
            runtime->error = SPEC_ERROR_EMIT;
            runtime->state = SPEC_STATE_ERROR;
            return SPEC_STEP_ERROR;
        }
        if (emit_result == 0) {
            ++runtime->metrics.output_backpressure_stalls;
            return SPEC_STEP_WAITING;
        }
        runtime->emit_index = 1u;
        ++runtime->metrics.emitted_tokens;
        ++runtime->metrics.fallback_tokens;
        runtime->state = SPEC_STATE_COMPLETE;
        ++runtime->metrics.transactions_completed;
        return SPEC_STEP_COMPLETE;
    }

    case SPEC_STATE_COMPLETE:
        return SPEC_STEP_COMPLETE;
    case SPEC_STATE_IDLE:
        return SPEC_STEP_WAITING;
    case SPEC_STATE_ERROR:
    default:
        return SPEC_STEP_ERROR;
    }
}

void spec_runtime_reset_metrics(spec_runtime_t *runtime)
{
    if (runtime != NULL) {
        memset(&runtime->metrics, 0, sizeof(runtime->metrics));
    }
}

const spec_runtime_metrics_t *spec_runtime_get_metrics(
    const spec_runtime_t *runtime)
{
    return runtime == NULL ? NULL : &runtime->metrics;
}

int spec_runtime_is_terminal(const spec_runtime_t *runtime)
{
    return runtime != NULL &&
           (runtime->state == SPEC_STATE_COMPLETE ||
            runtime->state == SPEC_STATE_ERROR);
}

const char *spec_runtime_state_name(spec_runtime_state_t state)
{
    static const char *const names[] = {
        "IDLE", "DRAFT", "TARGET_VERIFY", "READ_TARGET_RESULTS",
        "ACCEPT_OR_REJECT", "COMMIT_POINTER", "EMIT_TOKENS",
        "ACK_RESULTS", "EMIT_FALLBACK", "COMPLETE", "ERROR"
    };
    if ((unsigned)state >= sizeof(names) / sizeof(names[0])) {
        return "UNKNOWN";
    }
    return names[state];
}
