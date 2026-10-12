package io.github.apscot.laya.core;

import java.util.stream.IntStream;

/** Portable multi-threaded scalar kernel (fallback when jdk.incubator.vector is unavailable). */
final class ScalarGemm implements Gemm {
    @Override
    public void linear(float[] x, int n, Weights wt, float[] b, float[] y) {
        final float[] w = wt.data;
        final int in = wt.in, out = wt.out;
        int blocks = (out + 15) / 16;
        IntStream.range(0, blocks).parallel().forEach(blk -> {
            int o0 = blk * 16, o1 = Math.min(out, o0 + 16);
            for (int o = o0; o < o1; o++) {
                int wo = o * in;
                float bias = b == null ? 0f : b[o];
                for (int t = 0; t < n; t++) {
                    int xt = t * in;
                    float s0 = 0, s1 = 0, s2 = 0, s3 = 0;
                    int i = 0;
                    for (; i + 3 < in; i += 4) {
                        s0 += x[xt + i] * w[wo + i];
                        s1 += x[xt + i + 1] * w[wo + i + 1];
                        s2 += x[xt + i + 2] * w[wo + i + 2];
                        s3 += x[xt + i + 3] * w[wo + i + 3];
                    }
                    for (; i < in; i++) s0 += x[xt + i] * w[wo + i];
                    y[t * out + o] = (s0 + s1) + (s2 + s3) + bias;
                }
            }
        });
    }

    @Override
    public String name() { return "scalar"; }
}
