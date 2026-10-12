package io.github.apscot.laya.client;

import io.github.apscot.laya.core.Json;
import io.github.apscot.laya.core.Question;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal client for a Laya server ({@code /v1/systemone}, {@code /v1/decisions}). Uses only the JDK.
 * Works against laya-java, upstream {@code laya-serve} and any Jev-compatible endpoint.
 */
public final class LayaClient {
    /** Non-2xx response. */
    public static final class ApiException extends RuntimeException {
        public final int status;
        public final String body;

        ApiException(int status, String body) {
            super("HTTP " + status + ": " + body);
            this.status = status;
            this.body = body;
        }
    }

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Duration timeout;

    public LayaClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, Duration.ofSeconds(60));
    }

    public LayaClient(String baseUrl, String apiKey, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.timeout = timeout;
    }

    /** Jev-style call. Result is the JSON response as nested maps ({@code answers}, {@code usage}, ...). */
    public Map<String, Object> systemOne(Object state, Map<String, Question> questions) {
        return systemOne("laya", state, questions);
    }

    public Map<String, Object> systemOne(String model, Object state, Map<String, Question> questions) {
        Map<String, Object> qs = new LinkedHashMap<>();
        for (Map.Entry<String, Question> e : questions.entrySet()) qs.put(e.getKey(), toWire(e.getValue()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state);
        body.put("model", model);
        body.put("questions", qs);
        return post("/v1/systemone", body);
    }

    /** Raw OpenAI Decisions call: {@code request} is the JSON body as maps. */
    public Map<String, Object> decisions(Map<String, Object> request) {
        return post("/v1/decisions", request);
    }

    public Map<String, Object> models() {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/models")).GET());
    }

    private static Map<String, Object> toWire(Question q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", q.type.wire);
        m.put("instructions", q.instructions);
        if (q.type == Question.Type.SCORE) m.put("criteria", q.levels);
        else if (q.criteriaMap != null) m.put("criteria", q.criteriaMap);
        if (q.labels != null) m.put("labels", q.labels);
        return m;
    }

    private Map<String, Object> post(String path, Object body) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.dumps(body))));
    }

    private Map<String, Object> send(HttpRequest.Builder b) {
        b.timeout(timeout);
        if (apiKey != null && !apiKey.isEmpty()) b.header("Authorization", "Bearer " + apiKey);
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new ApiException(r.statusCode(), r.body());
            return Json.parseObject(r.body());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Convenience: probability from a noul answer. */
    @SuppressWarnings("unchecked")
    public static double noul(Map<String, Object> response, String id) {
        return ((Number) ((Map<String, Object>) ((Map<String, Object>) response.get("answers")).get(id)).get("noul")).doubleValue();
    }

    /** Convenience: chosen label from a choice answer. */
    @SuppressWarnings("unchecked")
    public static String choice(Map<String, Object> response, String id) {
        return (String) ((Map<String, Object>) ((Map<String, Object>) response.get("answers")).get(id)).get("choice");
    }

    /** Convenience: score from a score answer. */
    @SuppressWarnings("unchecked")
    public static double score(Map<String, Object> response, String id) {
        return ((Number) ((Map<String, Object>) ((Map<String, Object>) response.get("answers")).get(id)).get("score")).doubleValue();
    }

}
