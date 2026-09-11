import unittest
import numpy as np
try:
    from .attention_reference import causal_attention_reference, tiled_causal_attention_reference, dequant_int8
except ImportError:
    from attention_reference import causal_attention_reference, tiled_causal_attention_reference, dequant_int8


class CausalAttentionReferenceTest(unittest.TestCase):
    def setUp(self):
        rng=np.random.default_rng(260)
        self.q=rng.normal(0,.2,(4,2,8)).astype(np.float16)
        self.ck=rng.normal(0,.2,(130,2,8)).astype(np.float16)
        self.cv=rng.normal(0,.2,(130,2,8)).astype(np.float16)
        self.tk=rng.normal(0,.2,(4,2,8)).astype(np.float16)
        self.tv=rng.normal(0,.2,(4,2,8)).astype(np.float16)

    def test_tiled_matches_dense_k1_to_k4(self):
        for k in range(1,5):
            dense=causal_attention_reference(self.q[:k],self.ck,self.cv,self.tk[:k],self.tv[:k])
            tiled=tiled_causal_attention_reference(self.q[:k],self.ck,self.cv,self.tk[:k],self.tv[:k],64)
            np.testing.assert_array_equal(tiled,dense)

    def test_future_candidate_is_invisible(self):
        baseline=causal_attention_reference(self.q,self.ck,self.cv,self.tk,self.tv)
        tk=self.tk.copy();tv=self.tv.copy();tk[3]=100;tv[3]=-100
        changed=causal_attention_reference(self.q,self.ck,self.cv,tk,tv)
        np.testing.assert_array_equal(changed[:3],baseline[:3])

    def test_int8_dequantization(self):
        q=np.array([0,127,255],dtype=np.uint8)
        np.testing.assert_array_equal(dequant_int8(q,np.float16(.5),np.uint8(127)),np.array([-63.5,0,64],np.float32))

    def test_tentative_only(self):
        empty=np.empty((0,2,8),np.float16)
        dense=causal_attention_reference(self.q,empty,empty,self.tk,self.tv)
        tiled=tiled_causal_attention_reference(self.q,empty,empty,self.tk,self.tv)
        np.testing.assert_array_equal(tiled,dense)


if __name__=='__main__': unittest.main()
