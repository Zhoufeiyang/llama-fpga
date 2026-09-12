"""P6 greedy speculative acceptance and sequence-level oracle."""


def greedy_accept(candidates, target_predictions):
    """Return (accepted_count, emitted_tokens, bonus).

    target_predictions must contain g[0..K], where g[i] is the target greedy
    prediction after the first i candidate tokens.
    """
    k = len(candidates)
    if not 1 <= k <= 4 or len(target_predictions) != k + 1:
        raise ValueError("expected K=1..4 candidates and K+1 target predictions")
    for i, candidate in enumerate(candidates):
        if candidate != target_predictions[i]:
            return i, list(candidates[:i]) + [target_predictions[i]], False
    return k, list(candidates) + [target_predictions[k]], True


def target_only(target_next, prefix, count):
    output = []
    state = list(prefix)
    while len(output) < count:
        token = target_next(tuple(state))
        output.append(token)
        state.append(token)
    return output


def speculative_generate(target_next, draft_next, prefix, count, k=4):
    output = []
    state = list(prefix)
    while len(output) < count:
        draft = []
        draft_state = list(state)
        for _ in range(k):
            token = draft_next(tuple(draft_state))
            draft.append(token)
            draft_state.append(token)
        targets = [target_next(tuple(state + draft[:i])) for i in range(k + 1)]
        _, emitted, _ = greedy_accept(draft, targets)
        take = min(len(emitted), count - len(output))
        output.extend(emitted[:take])
        state.extend(emitted[:take])
    return output
