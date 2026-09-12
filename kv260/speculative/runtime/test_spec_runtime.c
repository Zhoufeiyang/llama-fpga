#include "spec_runtime.h"

#include <stdio.h>
#include <string.h>

typedef struct {
    uint32_t regs[0x160u / 4u];
    uint32_t writes[128][2];
    unsigned write_count;
    uint16_t emitted[16];
    unsigned emitted_count;
    unsigned stall_once;
    uint16_t desired_targets[SPEC_TARGET_MAX];
    unsigned complete_on_launch;
} fake_platform_t;

static uint32_t fake_read(void *context, uint32_t offset)
{
    fake_platform_t *fake = (fake_platform_t *)context;
    return fake->regs[offset / 4u];
}

static void fake_write(void *context, uint32_t offset, uint32_t value)
{
    fake_platform_t *fake = (fake_platform_t *)context;
    fake->regs[offset / 4u] = value;
    fake->writes[fake->write_count][0] = offset;
    fake->writes[fake->write_count][1] = value;
    ++fake->write_count;
    if (offset == SPEC_REG_START && value != 0u) {
        fake->regs[SPEC_REG_TARGET0 / 4u] =
            fake->regs[SPEC_REG_INITIAL_TARGET / 4u];
        fake->regs[SPEC_REG_RESULT_COUNT / 4u] = 1u;
    }
}

static int deterministic_draft(void *context, const uint16_t *prefix,
                               size_t prefix_length, uint16_t *next_token)
{
    (void)context;
    *next_token = (uint16_t)(prefix[prefix_length - 1u] + 1u);
    return 0;
}

static int capture_emit(void *context, uint16_t token)
{
    fake_platform_t *fake = (fake_platform_t *)context;
    if (fake->stall_once != 0u) {
        fake->stall_once = 0u;
        return 0;
    }
    fake->emitted[fake->emitted_count++] = token;
    return 1;
}

static int fake_launch_verify(void *context, const uint16_t *candidates,
                              uint8_t k, uint16_t committed_length)
{
    fake_platform_t *fake = (fake_platform_t *)context;
    unsigned i;
    (void)candidates;
    (void)committed_length;
    if (fake->complete_on_launch == 0u) {
        return 0;
    }
    for (i = 1u; i <= k; ++i) {
        fake->regs[(SPEC_REG_TARGET0 + 4u * i) / 4u] =
            fake->desired_targets[i];
    }
    fake->regs[SPEC_REG_RESULT_COUNT / 4u] = (uint32_t)k + 1u;
    return 0;
}

static int run_until_terminal(spec_runtime_t *runtime, unsigned max_steps)
{
    unsigned step;
    for (step = 0u; step < max_steps && !spec_runtime_is_terminal(runtime);
         ++step) {
        (void)spec_runtime_step(runtime);
    }
    return spec_runtime_is_terminal(runtime) ? 0 : -1;
}

static int check_case(uint8_t k, uint8_t mismatch)
{
    fake_platform_t fake;
    spec_runtime_t runtime;
    spec_runtime_config_t config;
    unsigned i;
    unsigned expected_accept = mismatch;

    memset(&fake, 0, sizeof(fake));
    memset(&config, 0, sizeof(config));
    config.read32 = fake_read;
    config.write32 = fake_write;
    config.mmio_context = &fake;
    config.draft = deterministic_draft;
    config.launch_verify = fake_launch_verify;
    config.verify_context = &fake;
    config.emit = capture_emit;
    config.emit_context = &fake;
    config.timeout_polls = 8u;
    if (spec_runtime_init(&runtime, &config) != 0 ||
        spec_runtime_begin(&runtime, 100u, 9u,
                           mismatch == 0u ? 90u : 10u, k) != 0) {
        return 1;
    }

    fake.complete_on_launch = 1u;
    for (i = 0u; i < k; ++i) {
        fake.desired_targets[i] = 10u + i;
    }
    if (mismatch < k) {
        fake.desired_targets[mismatch] = 90u + mismatch;
    } else {
        expected_accept = k;
    }
    fake.desired_targets[k] = 200u + k;
    fake.regs[SPEC_REG_STATUS / 4u] = SPEC_STATUS_ACTIVE;
    fake.stall_once = 1u;

    if (run_until_terminal(&runtime, 64u) != 0 ||
        runtime.state != SPEC_STATE_COMPLETE ||
        runtime.accepted_count != expected_accept ||
        runtime.committed_length != 100u + expected_accept ||
        fake.emitted_count != expected_accept + 1u ||
        fake.regs[SPEC_REG_COMMITTED / 4u] != 100u ||
        fake.regs[SPEC_REG_BATCH_K / 4u] != k ||
        fake.regs[SPEC_REG_INITIAL_TARGET / 4u] !=
            (mismatch == 0u ? 90u : 10u) ||
        fake.regs[SPEC_REG_START / 4u] != 1u ||
        fake.regs[SPEC_REG_COMMIT / 4u] != (0x100u | expected_accept) ||
        fake.regs[SPEC_REG_RESULT_ACK / 4u] != 1u) {
        return 1;
    }
    if (runtime.metrics.transactions_started != 1u ||
        runtime.metrics.transactions_completed != 1u ||
        runtime.metrics.transactions_failed != 0u ||
        runtime.metrics.draft_tokens != k ||
        runtime.metrics.target_result_tokens != (uint64_t)k + 1u ||
        runtime.metrics.accepted_draft_tokens != expected_accept ||
        runtime.metrics.emitted_tokens != expected_accept + 1u ||
        runtime.metrics.all_match_transactions != (mismatch == k ? 1u : 0u) ||
        runtime.metrics.mismatch_transactions != (mismatch < k ? 1u : 0u) ||
        runtime.metrics.output_backpressure_stalls != 1u) {
        return 1;
    }
    for (i = 0u; i < expected_accept; ++i) {
        if (fake.emitted[i] != 10u + i) {
            return 1;
        }
    }
    for (i = 0u; i < k; ++i) {
        if (fake.regs[(SPEC_REG_CANDIDATE0 + 4u * i) / 4u] != 10u + i) {
            return 1;
        }
    }
    if (fake.emitted[expected_accept] !=
        fake.regs[(SPEC_REG_TARGET0 + 4u * expected_accept) / 4u]) {
        return 1;
    }
    return 0;
}

static int check_invalid_descriptors(void)
{
    fake_platform_t fake;
    spec_runtime_t runtime;
    spec_runtime_config_t config;
    memset(&fake, 0, sizeof(fake));
    memset(&config, 0, sizeof(config));
    config.read32 = fake_read;
    config.write32 = fake_write;
    config.mmio_context = &fake;
    config.draft = deterministic_draft;
    config.launch_verify = fake_launch_verify;
    config.verify_context = &fake;
    config.emit = capture_emit;
    config.emit_context = &fake;
    config.timeout_polls = 4u;
    if (spec_runtime_init(&runtime, &config) != 0) {
        return 1;
    }
    return spec_runtime_begin(&runtime, 0u, 1u, 2u, 0u) == -1 &&
           spec_runtime_begin(&runtime, 0u, 1u, 2u, 5u) == -1 &&
           spec_runtime_begin(&runtime, 1021u, 1u, 2u, 4u) == -1 ? 0 : 1;
}

static int check_timeout(void)
{
    fake_platform_t fake;
    spec_runtime_t runtime;
    spec_runtime_config_t config;
    memset(&fake, 0, sizeof(fake));
    memset(&config, 0, sizeof(config));
    config.read32 = fake_read;
    config.write32 = fake_write;
    config.mmio_context = &fake;
    config.draft = deterministic_draft;
    config.launch_verify = fake_launch_verify;
    config.verify_context = &fake;
    config.emit = capture_emit;
    config.emit_context = &fake;
    config.timeout_polls = 3u;
    if (spec_runtime_init(&runtime, &config) != 0 ||
        spec_runtime_begin(&runtime, 30u, 4u, 5u, 2u) != 0) {
        return 1;
    }
    if (run_until_terminal(&runtime, 32u) != 0 ||
        runtime.error != SPEC_ERROR_TIMEOUT ||
        runtime.metrics.transactions_failed != 1u ||
        runtime.metrics.rollback_transactions != 1u ||
        fake.regs[SPEC_REG_ROLLBACK / 4u] != 1u ||
        fake.regs[SPEC_REG_RESULT_ACK / 4u] != 1u) {
        return 1;
    }
    return 0;
}

static int check_pl_fault(void)
{
    fake_platform_t fake;
    spec_runtime_t runtime;
    spec_runtime_config_t config;
    memset(&fake, 0, sizeof(fake));
    memset(&config, 0, sizeof(config));
    config.read32 = fake_read;
    config.write32 = fake_write;
    config.mmio_context = &fake;
    config.draft = deterministic_draft;
    config.launch_verify = fake_launch_verify;
    config.verify_context = &fake;
    config.emit = capture_emit;
    config.emit_context = &fake;
    config.timeout_polls = 4u;
    if (spec_runtime_init(&runtime, &config) != 0 ||
        spec_runtime_begin(&runtime, 20u, 4u, 5u, 1u) != 0) {
        return 1;
    }
    (void)spec_runtime_step(&runtime);
    (void)spec_runtime_step(&runtime);
    fake.regs[SPEC_REG_STATUS / 4u] = SPEC_STATUS_FAULT;
    (void)spec_runtime_step(&runtime);
    return runtime.error == SPEC_ERROR_PL_FAULT &&
           runtime.metrics.transactions_failed == 1u &&
           runtime.metrics.rollback_transactions == 1u &&
           fake.regs[SPEC_REG_ROLLBACK / 4u] == 1u ? 0 : 1;
}

int main(void)
{
    uint8_t k;
    uint8_t mismatch;
    int errors = 0;
    for (k = 1u; k <= SPEC_KMAX; ++k) {
        for (mismatch = 0u; mismatch <= k; ++mismatch) {
            errors += check_case(k, mismatch);
        }
        printf("P7A_K%u mismatches_0_to_%u_and_all_match=passed\n",
               (unsigned)k, (unsigned)(k - 1u));
    }
    errors += check_timeout();
    errors += check_pl_fault();
    errors += check_invalid_descriptors();
    if (errors != 0) {
        fprintf(stderr, "P7-A runtime failed with %d errors\n", errors);
        return 1;
    }
    puts("P7A_PS_RUNTIME_GO K1_TO_K4=1 BACKPRESSURE=1 TIMEOUT_ROLLBACK=1 PL_FAULT_ROLLBACK=1 METRICS=1");
    return 0;
}
