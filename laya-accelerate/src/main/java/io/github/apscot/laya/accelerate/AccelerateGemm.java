package io.github.apscot.laya.accelerate;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import io.github.apscot.laya.core.Gemm;

/** cblas_sgemm from the system Accelerate framework, called through JNA (no custom native code). */
final class AccelerateGemm implements Gemm {
    public interface Blas extends Library {
        void cblas_sgemm(int order, int transA, int transB, int m, int n, int k, float alpha,
                float[] a, int lda, Pointer b, int ldb, float beta, float[] c, int ldc);
    }

    private static final int ROW_MAJOR = 101, NO_TRANS = 111, TRANS = 112;
    private final Blas blas;

    AccelerateGemm() {
        blas = Native.load("/System/Library/Frameworks/Accelerate.framework/Accelerate", Blas.class);
        // smoke test so an unusable library fails at selection time, not mid-inference
        float[] a = {1, 2}, c = new float[1];
        Memory m = new Memory(8);
        m.write(0, new float[] {3, 4}, 0, 2);
        blas.cblas_sgemm(ROW_MAJOR, NO_TRANS, TRANS, 1, 1, 2, 1f, a, 2, m, 2, 0f, c, 1);
        if (c[0] != 11f) throw new IllegalStateException("Accelerate self-test failed");
    }

    @Override
    public Weights prepare(float[] w, int out, int in) {
        // weights live only in native memory (halves resident size); heap copy is dropped
        Weights wt = new Weights(null, out, in);
        Memory m = new Memory((long) w.length * 4);
        m.write(0, w, 0, w.length);
        wt.handle = m;
        return wt;
    }

    @Override
    public void linear(float[] x, int n, Weights w, float[] b, float[] y) {
        blas.cblas_sgemm(ROW_MAJOR, NO_TRANS, TRANS, n, w.out, w.in, 1f, x, w.in, (Pointer) w.handle, w.in, 0f, y, w.out);
        if (b != null) {
            int out = w.out;
            for (int t = 0; t < n; t++) for (int o = 0; o < out; o++) y[t * out + o] += b[o];
        }
    }

    @Override
    public String name() {
        return "accelerate";
    }
}
