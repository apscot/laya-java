package io.github.apscot.laya.api;

import io.github.apscot.laya.core.Agent;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Named checkpoints, loaded lazily and shared across requests. */
public final class ModelRegistry implements AutoCloseable {
    public static final String ENGLISH = "english", MULTILINGUAL = "multilingual", TYPED = "typed-decisions";

    private final Map<String, String> sources = new LinkedHashMap<>();
    private final Map<String, Agent> loaded = new ConcurrentHashMap<>();
    private final String defaultName;

    public ModelRegistry(Map<String, String> sources, String defaultName) {
        this.sources.putAll(sources);
        if (!this.sources.containsKey(defaultName)) throw new IllegalArgumentException("default model '" + defaultName + "' is not registered");
        this.defaultName = defaultName;
    }

    public static ModelRegistry defaults() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ENGLISH, "aac6fef/laya-mlx");
        m.put(MULTILINGUAL, "aac6fef/laya-multilingual-mlx");
        m.put(TYPED, "aac6fef/laya-typed-decisions-mlx");
        return new ModelRegistry(m, ENGLISH);
    }

    public Map<String, String> sources() {
        return Map.copyOf(sources);
    }

    public String defaultName() {
        return defaultName;
    }

    /** Resolve a requested model name to a registered one; unknown names use the default (like upstream laya-serve). */
    public String resolveName(String requested) {
        if (requested != null) {
            String r = requested.toLowerCase();
            for (String n : sources.keySet()) if (n.equalsIgnoreCase(r)) return n;
        }
        return defaultName;
    }

    /**
     * Pick a checkpoint for a request. A registered name wins; otherwise, when both the English and multilingual
     * checkpoints are registered, route by language; otherwise use the default.
     */
    public String route(String requested, Object state) {
        if (requested != null) for (String n : sources.keySet()) if (n.equalsIgnoreCase(requested)) return n;
        if (sources.containsKey(ENGLISH) && sources.containsKey(MULTILINGUAL) && defaultName.equals(ENGLISH)) {
            String text = state instanceof String ? (String) state : io.github.apscot.laya.core.Json.dumps(state);
            return LanguageRouter.isEnglish(text) ? ENGLISH : MULTILINGUAL;
        }
        return defaultName;
    }

    public Agent agent(String name) {
        return loaded.computeIfAbsent(name, n -> {
            try {
                return Agent.load(sources.get(n));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    private final java.util.concurrent.locks.ReentrantLock inference = new java.util.concurrent.locks.ReentrantLock();

    /** Run a prediction; forward passes are serialized because each one already uses all cores. */
    public Map<String, Object> predict(String name, Object state, Map<String, io.github.apscot.laya.core.Question> questions) {
        Agent a = agent(name);
        inference.lock();
        try {
            return a.predict(state, questions);
        } finally {
            inference.unlock();
        }
    }

    public boolean isLoaded(String name) {
        return loaded.containsKey(name);
    }

    public void preload() {
        agent(defaultName);
    }

    @Override
    public void close() {
        for (Agent a : loaded.values()) {
            try { a.close(); } catch (IOException ignored) { /* best effort */ }
        }
        loaded.clear();
    }
}
