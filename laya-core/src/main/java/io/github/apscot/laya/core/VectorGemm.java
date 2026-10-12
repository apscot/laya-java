package io.github.apscot.laya.core;

import java.util.stream.IntStream;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/** jdk.incubator.vector kernel: 4 tokens per pass so each weight vector load is reused. */
final class VectorGemm implements Gemm {
    private static final VectorSpecies<Float> S = FloatVector.SPECIES_PREFERRED;

    @Override
    public void linear(float[] x, int n, Weights wt, float[] b, float[] y) {
        final float[] w = wt.data;
        final int in = wt.in, out = wt.out;
        final int L = S.length();
        final int bound = S.loopBound(in);
        int blocks = (out + 7) / 8;
        IntStream.range(0, blocks).parallel().forEach(blk -> {
            int o0 = blk * 8, o1 = Math.min(out, o0 + 8);
            for (int o = o0; o < o1; o++) {
                int wo = o * in;
                float bias = b == null ? 0f : b[o];
                int t = 0;
                for (; t + 3 < n; t += 4) {
                    int x0 = t * in, x1 = x0 + in, x2 = x1 + in, x3 = x2 + in;
                    FloatVector a0 = FloatVector.zero(S), a1 = a0, a2 = a0, a3 = a0;
                    int i = 0;
                    for (; i < bound; i += L) {
                        FloatVector wv = FloatVector.fromArray(S, w, wo + i);
                        a0 = FloatVector.fromArray(S, x, x0 + i).fma(wv, a0);
                        a1 = FloatVector.fromArray(S, x, x1 + i).fma(wv, a1);
                        a2 = FloatVector.fromArray(S, x, x2 + i).fma(wv, a2);
                        a3 = FloatVector.fromArray(S, x, x3 + i).fma(wv, a3);
                    }
                    float r0 = a0.reduceLanes(VectorOperators.ADD), r1 = a1.reduceLanes(VectorOperators.ADD),
                            r2 = a2.reduceLanes(VectorOperators.ADD), r3 = a3.reduceLanes(VectorOperators.ADD);
                    for (; i < in; i++) {
                        float wv = w[wo + i];
                        r0 += x[x0 + i] * wv; r1 += x[x1 + i] * wv; r2 += x[x2 + i] * wv; r3 += x[x3 + i] * wv;
                    }
                    y[t * out + o] = r0 + bias;
                    y[(t + 1) * out + o] = r1 + bias;
                    y[(t + 2) * out + o] = r2 + bias;
                    y[(t + 3) * out + o] = r3 + bias;
                }
                for (; t < n; t++) {
                    int xt = t * in;
                    FloatVector a = FloatVector.zero(S);
                    int i = 0;
                    for (; i < bound; i += L) a = FloatVector.fromArray(S, x, xt + i).fma(FloatVector.fromArray(S, w, wo + i), a);
                    float r = a.reduceLanes(VectorOperators.ADD);
                    for (; i < in; i++) r += x[xt + i] * w[wo + i];
                    y[t * out + o] = r + bias;
                }
            }
        });
    }

    @Override
    public String name() { return "vector" + S.vectorBitSize(); }
}
