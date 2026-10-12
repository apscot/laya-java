package io.github.apscot.laya.api;

import io.github.apscot.laya.core.Question;
import java.util.LinkedHashMap;
import java.util.Map;

/** Jev / System One protocol: {@code POST /v1/systemone} (limits match upstream laya-serve). */
public final class SystemOneApi {
    public static final int MAX_QUESTIONS = 64, MAX_state_CHARS = 50_000;
    private static final LayaApiException.Style S = LayaApiException.Style.SYSTEM_ONE;

    private final ModelRegistry registry;

    public SystemOneApi(ModelRegistry registry) {
        this.registry = registry;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> handle(Map<String, Object> request) {
        if (request == null) throw new LayaApiException(422, S, "request body must be a JSON object");
        Object state = request.get("state");
        if (state == null) throw new LayaApiException(422, S, "state: field required");
        Object qs = request.get("questions");
        if (!(qs instanceof Map)) throw new LayaApiException(422, S, "questions: field required (object keyed by question id)");
        Map<String, Object> raw = (Map<String, Object>) qs;
        if (raw.size() > MAX_QUESTIONS) throw new LayaApiException(422, S, "questions: at most " + MAX_QUESTIONS + " questions per request");
        String text = state instanceof String ? (String) state : io.github.apscot.laya.core.Json.dumps(state);
        if (text.length() > MAX_state_CHARS) throw new LayaApiException(422, S, "state: at most " + MAX_state_CHARS + " characters");
        Map<String, Question> questions = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (!(e.getValue() instanceof Map)) throw new LayaApiException(422, S, "questions." + e.getKey() + ": must be an object");
            try {
                questions.put(e.getKey(), Question.fromMap((Map<String, Object>) e.getValue()));
            } catch (IllegalArgumentException ex) {
                throw new LayaApiException(422, S, "questions." + e.getKey() + ": " + ex.getMessage());
            }
        }
        Object model = request.get("model");
        String name = registry.route(model instanceof String ? (String) model : null, state);
        Map<String, Object> result;
        try {
            result = registry.predict(name, state, questions);
        } catch (IllegalArgumentException ex) {
            throw new LayaApiException(422, S, ex.getMessage());
        }
        return result;
    }
}
