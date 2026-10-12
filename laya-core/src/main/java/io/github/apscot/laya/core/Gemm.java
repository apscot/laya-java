package io.github.apscot.laya.core;

import java.util.ServiceLoader;

/** Matrix-multiply backend: y[t][o] = sum_i x[t][i] * w[o][i] + b[o]. */
public interface Gemm {
    /** A weight matrix (out x in, row-major) possibly pre-uploaded by a backend. */
    final class Weights {
        public final float[] data;
        public final int out, in;
        public Object handle;

        public Weights(float[] data, int out, int in) {
            this.data = data;
            this.out = out;
            this.in = in;
        }
    }

    /** Called once per weight matrix at load time; backends may attach {@link Weights#handle}. */
    default Weights prepare(float[] w, int out, int in) {
        return new Weights(w, out, in);
    }

    /**
     * @param x n x in (row-major)
     * @param b out or null
     * @param y n x out (row-major, overwritten)
     */
    void linear(float[] x, int n, Weights w, float[] b, float[] y);

    String name();

    /** Best backend available: an optional accelerated provider, else Vector API, else scalar. */
    static Gemm best() {
        String force = System.getProperty("laya.backend", System.getenv("LAYA_BACKEND"));
        if (!"portable".equals(force)) {
            try {
                for (GemmProvider p : ServiceLoader.load(GemmProvider.class)) {
                    try {
                        Gemm g = p.create();
                        if (g != null) return g;
                    } catch (Throwable ignored) {
                        // provider unusable on this machine; try the next one
                    }
                }
            } catch (Throwable ignored) {
                // ServiceLoader failure: fall back
            }
        }
        try {
            return (Gemm) Class.forName("io.github.apscot.laya.core.VectorGemm").getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return new ScalarGemm();
        }
    }
}
