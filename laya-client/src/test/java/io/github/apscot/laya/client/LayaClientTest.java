package io.github.apscot.laya.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.apscot.laya.core.Json;
import io.github.apscot.laya.core.Question;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LayaClientTest {
    @SuppressWarnings("unchecked")
    @Test
    void sendsJevShapedRequest() throws Exception {
        String[] seen = new String[2];
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/systemone", ex -> {
            seen[0] = ex.getRequestHeaders().getFirst("Authorization");
            seen[1] = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] r = "{\"model\":\"m\",\"answers\":{\"a\":{\"type\":\"noul\",\"noul\":0.9}},\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, r.length);
            ex.getResponseBody().write(r);
            ex.close();
        });
        s.createContext("/v1/decisions", ex -> {
            byte[] r = "{\"detail\":\"nope\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(422, r.length);
            ex.getResponseBody().write(r);
            ex.close();
        });
        s.start();
        try {
            LayaClient c = new LayaClient("http://127.0.0.1:" + s.getAddress().getPort(), "key");
            Map<String, Question> qs = new LinkedHashMap<>();
            qs.put("a", Question.noul("Is it urgent?", null));
            qs.put("b", Question.score("How bad?", List.of("low", "high")));
            Map<String, Object> r = c.systemOne("help", qs);
            assertEquals(0.9, LayaClient.noul(r, "a"), 1e-9);
            assertEquals("Bearer key", seen[0]);
            Map<String, Object> sent = Json.parseObject(seen[1]);
            assertEquals("help", sent.get("state"));
            assertEquals(List.of("low", "high"), ((Map<String, Object>) ((Map<String, Object>) sent.get("questions")).get("b")).get("criteria"));
            LayaClient.ApiException e = assertThrows(LayaClient.ApiException.class, () -> c.decisions(Map.of()));
            assertEquals(422, e.status);
            assertTrue(e.body.contains("nope"));
        } finally {
            s.stop(0);
        }
    }
}
