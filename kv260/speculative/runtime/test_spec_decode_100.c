#include "draft_model.h"
#include "spec_runtime.h"

#include <stdio.h>
#include <string.h>

#define TEST_TOKENS 100u

typedef struct {
    uint32_t regs[0x194u / 4u];
    uint16_t emitted[TEST_TOKENS + SPEC_TARGET_MAX];
    unsigned emitted_count;
    int inject_timeout;
} fake_target_t;

typedef struct {
    draft_ngram_t *model;
    uint16_t previous;
} contextual_draft_t;

static uint16_t oracle_next(uint16_t token)
{
    return (uint16_t)(((uint32_t)token * 109u + 37u) % 32000u);
}

static uint32_t fake_read(void *context, uint32_t offset)
{
    fake_target_t *target = (fake_target_t *)context;
    return target->regs[offset / 4u];
}

static void fake_write(void *context, uint32_t offset, uint32_t value)
{
    fake_target_t *target = (fake_target_t *)context;
    target->regs[offset / 4u] = value;
    if (offset == SPEC_REG_START && value != 0u) {
        target->regs[SPEC_REG_TARGET0 / 4u] =
            target->regs[SPEC_REG_INITIAL_TARGET / 4u];
        target->regs[SPEC_REG_RESULT_COUNT / 4u] = 1u;
    }
}

static int contextual_ngram(void *context, const uint16_t *prefix,
                            size_t prefix_length, uint16_t *next_token)
{
    contextual_draft_t *draft = (contextual_draft_t *)context;
    uint16_t full_prefix[SPEC_TARGET_MAX + 1u];
    size_t i;
    if (prefix_length > SPEC_TARGET_MAX) {
        return -1;
    }
    full_prefix[0] = draft->previous;
    for (i = 0u; i < prefix_length; ++i) {
        full_prefix[i + 1u] = prefix[i];
    }
    return draft_ngram_predict(draft->model, full_prefix, prefix_length + 1u,
                               next_token);
}

static int fake_launch(void *context, const uint16_t *candidates, uint8_t k,
                       uint16_t committed_length)
{
    fake_target_t *target = (fake_target_t *)context;
    unsigned i;
    (void)committed_length;
    if (target->inject_timeout != 0) {
        return 0;
    }
    for (i = 1u; i <= k; ++i) {
        target->regs[(SPEC_REG_TARGET0 + 4u * i) / 4u] =
            oracle_next(candidates[i - 1u]);
    }
    target->regs[SPEC_REG_RESULT_COUNT / 4u] = (uint32_t)k + 1u;
    return 0;
}

static int capture_emit(void *context, uint16_t token)
{
    fake_target_t *target = (fake_target_t *)context;
    if (target->emitted_count >=
        sizeof(target->emitted) / sizeof(target->emitted[0])) {
        return -1;
    }
    target->emitted[target->emitted_count++] = token;
    return 1;
}

static int run_transaction(spec_runtime_t *runtime)
{
    unsigned steps;
    for (steps = 0u; steps < 128u && !spec_runtime_is_terminal(runtime);
         ++steps) {
        (void)spec_runtime_step(runtime);
    }
    return runtime->state == SPEC_STATE_COMPLETE ? 0 : -1;
}

static int check_k(uint8_t k)
{
    draft_ngram_entry_t storage[1024];
    draft_ngram_t model;
    contextual_draft_t draft;
    fake_target_t target;
    spec_runtime_config_t config;
    spec_runtime_t runtime;
    uint16_t training[256];
    uint16_t expected[TEST_TOKENS];
    uint16_t previous = 1u;
    uint16_t seed;
    uint16_t committed = 1u;
    unsigned i;
    unsigned expected_count = 0u;
    unsigned fallback_count = 0u;

    memset(&target, 0, sizeof(target));
    memset(&config, 0, sizeof(config));
    training[0] = previous;
    training[1] = 7u;
    for (i = 2u; i < sizeof(training) / sizeof(training[0]); ++i) {
        training[i] = oracle_next(training[i - 1u]);
    }
    if (draft_ngram_init(&model, storage, 1024u, 2u) != 0 ||
        draft_ngram_observe(&model, training,
                            sizeof(training) / sizeof(training[0])) != 0) {
        return 1;
    }
    draft.model = &model;
    draft.previous = previous;
    seed = training[1];
    config.read32 = fake_read;
    config.write32 = fake_write;
    config.mmio_context = &target;
    config.draft = contextual_ngram;
    config.draft_context = &draft;
    config.launch_verify = fake_launch;
    config.verify_context = &target;
    config.emit = capture_emit;
    config.emit_context = &target;
    config.timeout_polls = 2u;
    config.enable_target_fallback = 1u;
    if (spec_runtime_init(&runtime, &config) != 0) {
        return 1;
    }

    while (expected_count < TEST_TOKENS) {
        unsigned before = target.emitted_count;
        unsigned produced;
        uint16_t initial_target = oracle_next(seed);
        target.inject_timeout =
            (k == 2u && expected_count == 99u) ? 1 : 0;
        if (target.inject_timeout != 0) {
            ++fallback_count;
        }
        target.regs[SPEC_REG_STATUS / 4u] = SPEC_STATUS_ACTIVE;
        target.regs[SPEC_REG_RESULT_COUNT / 4u] = 0u;
        draft.previous = previous;
        if (spec_runtime_begin(&runtime, committed, seed, initial_target, k) != 0 ||
            run_transaction(&runtime) != 0) {
            return 1;
        }
        produced = target.emitted_count - before;
        if (produced == 0u || expected_count + produced > TEST_TOKENS) {
            return 1;
        }
        for (i = 0u; i < produced; ++i) {
            expected[expected_count] = oracle_next(seed);
            previous = seed;
            seed = expected[expected_count++];
        }
        committed = (uint16_t)(committed + produced);
    }
    for (i = 0u; i < TEST_TOKENS; ++i) {
        if (target.emitted[i] != expected[i]) {
            return 1;
        }
    }
    if (runtime.metrics.emitted_tokens != TEST_TOKENS ||
        runtime.metrics.fallback_tokens != fallback_count ||
        runtime.metrics.fallback_invocations != fallback_count ||
        target.emitted_count != TEST_TOKENS) {
        return 1;
    }
    printf("P7E_K%u_END_TO_END_GO TOKENS=100 NGRAM_CALLBACK=1 FALLBACKS=%u SEQUENCE_EQUIVALENT=1\n",
           (unsigned)k, fallback_count);
    return 0;
}

int main(void)
{
    uint8_t k;
    int errors = 0;
    for (k = 1u; k <= SPEC_KMAX; ++k) {
        errors += check_k(k);
    }
    if (errors != 0) {
        fprintf(stderr, "P7-E end-to-end test failed with %d errors\n", errors);
        return 1;
    }
    puts("P7E_RUNTIME_100_GO K1_TO_K4=1 TARGET_ONLY_EQUIVALENCE=1 TIMEOUT_FALLBACK=1");
    return 0;
}
