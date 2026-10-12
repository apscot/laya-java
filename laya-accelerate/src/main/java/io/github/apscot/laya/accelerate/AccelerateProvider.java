package io.github.apscot.laya.accelerate;

import io.github.apscot.laya.core.Gemm;
import io.github.apscot.laya.core.GemmProvider;

/** Registers the macOS Accelerate (cblas_sgemm) backend when running on macOS. */
public final class AccelerateProvider implements GemmProvider {
    @Override
    public Gemm create() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return null;
        return new AccelerateGemm();
    }
}
