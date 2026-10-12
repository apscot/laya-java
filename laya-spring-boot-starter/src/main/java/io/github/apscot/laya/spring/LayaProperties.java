package io.github.apscot.laya.spring;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code laya.*} configuration. */
@ConfigurationProperties(prefix = "laya")
public class LayaProperties {
    /** Model name to Hugging Face repo id or local directory. Defaults to the three published checkpoints. */
    private Map<String, String> models = new LinkedHashMap<>();
    /** Model used when a request names an unknown model. */
    private String defaultModel = "english";
    /** Optional bearer token required on /v1/* requests. */
    private String apiKey;
    /** Load the default model at startup instead of on first request. */
    private boolean preload = false;
    private final Endpoint endpoint = new Endpoint();

    public static class Endpoint {
        /** Expose /v1/systemone, /v1/decisions and /v1/models. */
        private boolean enabled = true;
        /** Path prefix, without trailing slash. */
        private String basePath = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBasePath() { return basePath; }
        public void setBasePath(String basePath) { this.basePath = basePath; }
    }

    public Map<String, String> getModels() { return models; }
    public void setModels(Map<String, String> models) { this.models = models; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public boolean isPreload() { return preload; }
    public void setPreload(boolean preload) { this.preload = preload; }
    public Endpoint getEndpoint() { return endpoint; }
}
