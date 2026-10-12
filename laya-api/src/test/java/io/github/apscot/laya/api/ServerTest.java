package io.github.apscot.laya.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.apscot.laya.core.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ServerTest {
    static LayaHttpServer server;
    static String base;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        String d = System.getenv("LAYA_MODEL_DIR");
        assumeTrue(d != null && !d.isEmpty() && Files.isDirectory(Path.of(d)), "set LAYA_MODEL_DIR");
        ModelRegistry reg = new ModelRegistry(Map.of("english", d), "english");
        server = new LayaHttpServer(reg, "127.0.0.1", 0, "k");
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll
    static void stop() {
        if (server != null) server.close();
    }

    static HttpResponse<String> post(String path, String body, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) b.header("Authorization", "Bearer " + key);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void systemOne() throws Exception {
        String req = "{\"state\":\"Help! My payouts have been failing for 3 days.\",\"model\":\"jev-latest\",\"questions\":{"
                + "\"is_urgent\":{\"type\":\"noul\",\"instructions\":\"Does this convey urgency?\"},"
                + "\"dept\":{\"type\":\"choice\",\"instructions\":\"Which team?\",\"criteria\":{\"billing\":\"payments\",\"technical\":\"bugs\",\"sales\":\"pricing\"}},"
                + "\"frustration\":{\"type\":\"score\",\"instructions\":\"How frustrated?\",\"criteria\":[\"Calm\",\"Frustrated\",\"Very angry\"]}}}";
        assertEquals(401, post("/v1/systemone", req, "wrong").statusCode());
        HttpResponse<String> r = post("/v1/systemone", req, "k");
        assertEquals(200, r.statusCode(), r.body());
        Map<String, Object> out = Json.parseObject(r.body());
        Map<String, Object> answers = (Map<String, Object>) out.get("answers");
        assertEquals("noul", ((Map<String, Object>) answers.get("is_urgent")).get("type"));
        assertTrue(((Number) ((Map<String, Object>) answers.get("is_urgent")).get("noul")).doubleValue() > 0.5);
        assertEquals(0L, ((Map<String, Object>) out.get("usage")).get("output_tokens"));
        HttpResponse<String> bad = post("/v1/systemone", "{\"state\":\"x\",\"questions\":{\"q\":{\"type\":\"noul\"}}}", "k");
        assertEquals(422, bad.statusCode());
        assertTrue(bad.body().contains("detail"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void decisions() throws Exception {
        String req = "{\"model\":\"gpt-6-luna\",\"input\":\"Help! My payouts have been failing for 3 days.\",\"questions\":["
                + "{\"type\":\"predicate\",\"name\":\"urgent\",\"instructions\":\"Does this convey urgency?\"},"
                + "{\"type\":\"choice\",\"name\":\"dept\",\"instructions\":\"Which team?\",\"choices\":[{\"value\":\"billing\",\"description\":\"payments\"},{\"value\":\"sales\",\"description\":\"pricing\"}]},"
                + "{\"type\":\"score\",\"name\":\"sev\",\"instructions\":\"How severe?\",\"levels\":[{\"label\":\"low\",\"description\":\"minor\"},{\"label\":\"high\",\"description\":\"critical\"}]}]}";
        HttpResponse<String> r = post("/v1/decisions", req, "k");
        assertEquals(200, r.statusCode(), r.body());
        Map<String, Object> out = Json.parseObject(r.body());
        List<Object> answers = (List<Object>) out.get("answers");
        assertEquals(3, answers.size());
        assertEquals("predicate", ((Map<String, Object>) answers.get(0)).get("type"));
        assertTrue(((Map<String, Object>) answers.get(1)).get("probabilities") instanceof List);
        HttpResponse<String> bad = post("/v1/decisions", "{\"input\":\"x\",\"questions\":[]}", "k");
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("invalid_request_error"));
    }
}
