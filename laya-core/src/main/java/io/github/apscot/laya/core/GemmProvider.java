package io.github.apscot.laya.core;

/** ServiceLoader hook for optional accelerated matrix-multiply backends (e.g. laya-accelerate). */
public interface GemmProvider {
    /** @return a working backend, or null / throw if unavailable on this machine. */
    Gemm create();
}
