package io.github.apscot.laya.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@EnabledIfEnvironmentVariable(named = "LAYA_MODEL_DIR", matches = ".+")
@SpringBootTest(classes = StarterTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"laya.models.english=${LAYA_MODEL_DIR}", "laya.api-key=secret"})
class StarterTest {
    @EnableAutoConfiguration
    static class App {}

    @Autowired TestRestTemplate rest;
    @LocalServerPort int port;

    @SuppressWarnings("unchecked")
    ResponseEntity<Map> post(String path, String json, String key) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) h.setBearerAuth(key);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST, new HttpEntity<>(json, h), Map.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void endpoints() {
        String sys = "{\"state\":\"I was billed twice, refund me\",\"model\":\"jev-latest\",\"questions\":{\"refund\":{\"type\":\"noul\",\"instructions\":\"Does the customer want money back?\"}}}";
        assertEquals(401, post("/v1/systemone", sys, null).getStatusCode().value());
        ResponseEntity<Map> r = post("/v1/systemone", sys, "secret");
        assertEquals(200, r.getStatusCode().value());
        assertTrue(((Map<String, Object>) ((Map<String, Object>) r.getBody().get("answers")).get("refund")).containsKey("noul"));
        String dec = "{\"input\":\"I was billed twice\",\"questions\":[{\"type\":\"predicate\",\"name\":\"refund\",\"instructions\":\"Wants money back?\"}]}";
        ResponseEntity<Map> d = post("/v1/decisions", dec, "secret");
        assertEquals(200, d.getStatusCode().value());
        assertEquals(400, post("/v1/decisions", "{\"input\":\"x\",\"questions\":[]}", "secret").getStatusCode().value());
    }
}
