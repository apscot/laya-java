package io.github.apscot.laya.gradle;

import org.gradle.api.provider.Property;

/** {@code laya { ... }} DSL. */
public abstract class LayaExtension {
    /** Hugging Face repo id or local directory of the checkpoint to serve. Default {@code aac6fef/laya-mlx}. */
    public abstract Property<String> getModel();

    /** Port; 0 picks a free port. Default 8002. */
    public abstract Property<Integer> getPort();

    /** Optional bearer token the server requires. */
    public abstract Property<String> getApiKey();

    /** Run the server around the {@code test} task and pass {@code laya.base-url}/{@code laya.api-key} to it. */
    public abstract Property<Boolean> getTestIntegration();

    /** Max heap for the server JVM. Default 3g. */
    public abstract Property<String> getMaxHeap();

    /** Laya runtime version (laya-api + laya-accelerate). Defaults to the plugin's version. */
    public abstract Property<String> getRuntimeVersion();
}
