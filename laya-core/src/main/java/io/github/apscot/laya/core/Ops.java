package io.github.apscot.laya.core;

import java.util.stream.IntStream;

/** Small float32 tensor ops used by the Laya forward pass. */
final class Ops {
    private Ops() {}

    /** GELU (erf form) like mlx.nn.gelu; erf via Abramowitz-Stegun 7.1.26 refined (abs error ~1e-7). */
    static float gelu(float x) {
        float z = x * 0.70710678f;
        float az = Math.abs(z);
        if (az > 6f) return x > 0 ? x : 0f;
        float t = 1f / (1f + 0.3275911f * az);
        float poly = ((((1.061405429f * t - 1.453152027f) * t + 1.421413741f) * t - 0.284496736f) * t + 0.254829592f) * t;
        float erf = 1f - poly * (float) Math.exp(-az * az);
        if (z < 0) erf = -erf;
        return 0.5f * x * (1f + erf);
    }

    static double erf(double x) {
        double ax = Math.abs(x);
        double x2 = ax * ax, term = ax, sum = ax;
        if (ax >= 3) return x < 0 ? -1 : 1;
        for (int n = 1; n < 80; n++) {
            term *= -x2 / n;
            double d = term / (2 * n + 1);
            sum += d;
            if (Math.abs(d) < 1e-17) break;
        }
        double r = 1.1283791670955126 * sum;
        return x < 0 ? -r : r;
    }

    /** In-place LayerNorm over the last dim; bias may be null. */
    static void layerNorm(float[] x, int n, int d, float[] w, float[] b, float eps, float[] out) {
        IntStream.range(0, n).parallel().forEach(t -> {
            int o = t * d;
            double mean = 0;
            for (int i = 0; i < d; i++) mean += x[o + i];
            mean /= d;
            double var = 0;
            for (int i = 0; i < d; i++) { double c = x[o + i] - mean; var += c * c; }
            var /= d;
            float inv = (float) (1.0 / Math.sqrt(var + eps));
            float m = (float) mean;
            for (int i = 0; i < d; i++) {
                float v = (x[o + i] - m) * inv * w[i];
                out[o + i] = b == null ? v : v + b[i];
            }
        });
    }

    static void softmaxInPlace(float[] a, int len) {
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < len; i++) if (a[i] > max) max = a[i];
        float sum = 0;
        for (int i = 0; i < len; i++) { a[i] = (float) Math.exp(a[i] - max); sum += a[i]; }
        for (int i = 0; i < len; i++) a[i] /= sum;
    }

    /** Non-interleaved RoPE on a [n, heads*headDim]-strided block starting at column {@code col0}. */
    static void rope(float[] qkv, int n, int stride, int col0, int heads, int headDim, float[] cos, float[] sin) {
        int half = headDim / 2;
        IntStream.range(0, n).parallel().forEach(t -> {
            for (int h = 0; h < heads; h++) {
                int base = t * stride + col0 + h * headDim;
                for (int i = 0; i < half; i++) {
                    float c = cos[t * half + i], s = sin[t * half + i];
                    float a = qkv[base + i], b = qkv[base + half + i];
                    qkv[base + i] = a * c - b * s;
                    qkv[base + half + i] = b * c + a * s;
                }
            }
        });
    }

    static float[][] ropeTables(int n, int headDim, double base) {
        int half = headDim / 2;
        float[] cos = new float[n * half], sin = new float[n * half];
        for (int t = 0; t < n; t++) {
            for (int i = 0; i < half; i++) {
                double inv = 1.0 / Math.pow(base, (double) i / half);
                double ang = (float) t * inv;
                cos[t * half + i] = (float) Math.cos(ang);
                sin[t * half + i] = (float) Math.sin(ang);
            }
        }
        return new float[][] {cos, sin};
    }

    /**
     * Multi-head self attention on fused qkv ([n, 3*dim], q|k|v each head-major). window &lt; 0 = full,
     * otherwise key j is visible when |i-j| &lt;= window. Output [n, dim].
     */
    static void attention(float[] qkv, int n, int heads, int headDim, int window, float[] out) {
        int dim = heads * headDim, stride = 3 * dim;
        float scale = (float) (1.0 / Math.sqrt(headDim));
        IntStream.range(0, heads).parallel().forEach(h -> {
            float[] sc = new float[n];
            for (int i = 0; i < n; i++) {
                int lo = window < 0 ? 0 : Math.max(0, i - window);
                int hi = window < 0 ? n - 1 : Math.min(n - 1, i + window);
                int qo = i * stride + h * headDim;
                for (int j = lo; j <= hi; j++) {
                    int ko = j * stride + dim + h * headDim;
                    float s = 0;
                    for (int d = 0; d < headDim; d++) s += qkv[qo + d] * qkv[ko + d];
                    sc[j - lo] = s * scale;
                }
                int len = hi - lo + 1;
                softmaxInPlace(sc, len);
                int oo = i * dim + h * headDim;
                for (int d = 0; d < headDim; d++) out[oo + d] = 0;
                for (int j = lo; j <= hi; j++) {
                    float p = sc[j - lo];
                    int vo = j * stride + 2 * dim + h * headDim;
                    for (int d = 0; d < headDim; d++) out[oo + d] += p * qkv[vo + d];
                }
            }
        });
    }
}
