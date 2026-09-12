import unittest

try:
    from .greedy_speculative_reference import greedy_accept, speculative_generate, target_only
except ImportError:
    from greedy_speculative_reference import greedy_accept, speculative_generate, target_only


class GreedySpeculativeReferenceTests(unittest.TestCase):
    def test_every_mismatch_and_all_match(self):
        for k in range(1, 5):
            candidates = list(range(10, 10 + k))
            for mismatch in range(k):
                targets = candidates[:] + [99]
                targets[mismatch] = 200 + mismatch
                accepted, emitted, bonus = greedy_accept(candidates, targets)
                self.assertEqual(accepted, mismatch)
                self.assertEqual(emitted, candidates[:mismatch] + [200 + mismatch])
                self.assertFalse(bonus)
            accepted, emitted, bonus = greedy_accept(candidates, candidates + [99])
            self.assertEqual((accepted, emitted, bonus), (k, candidates + [99], True))

    def test_sequence_equals_target_only(self):
        def target_next(prefix):
            return (sum(prefix[-3:]) * 17 + len(prefix) * 13 + 7) % 251

        # Deliberately imperfect draft creates changing mismatch positions.
        def draft_next(prefix):
            value = target_next(prefix)
            return value if len(prefix) % 3 else (value + 1) % 251

        for k in range(1, 5):
            expected = target_only(target_next, [1, 2, 3], 100)
            actual = speculative_generate(target_next, draft_next, [1, 2, 3], 100, k)
            self.assertEqual(actual, expected)


if __name__ == "__main__":
    unittest.main()
