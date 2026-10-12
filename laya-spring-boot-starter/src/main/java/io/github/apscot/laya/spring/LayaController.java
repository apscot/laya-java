package io.github.apscot.laya.spring;

import io.github.apscot.laya.api.DecisionsApi;
import io.github.apscot.laya.api.LayaApiException;
import io.github.apscot.laya.api.ModelRegistry;
import io.github.apscot.laya.api.SystemOneApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Jev (/v1/systemone) and OpenAI Decisions (/v1/decisions) endpoints backed by a local Laya model. */
@RestController
public class LayaController {
    private final SystemOneApi systemOne;
    private final DecisionsApi decisions;
    private final ModelRegistry registry;

    public LayaController(ModelRegistry registry) {
        this.registry = registry;
        this.systemOne = new SystemOneApi(registry);
        this.decisions = new DecisionsApi(registry);
    }

    @PostMapping("${laya.endpoint.base-path:}/v1/systemone")
    public Map<String, Object> systemOne(@RequestBody Map<String, Object> body) {
        return systemOne.handle(body);
    }

    @PostMapping("${laya.endpoint.base-path:}/v1/decisions")
    public Map<String, Object> decisions(@RequestBody Map<String, Object> body) {
        return decisions.handle(body);
    }

    @GetMapping("${laya.endpoint.base-path:}/v1/models")
    public Map<String, Object> models() {
        List<Object> data = new ArrayList<>();
        for (String n : registry.sources().keySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n);
            m.put("object", "model");
            m.put("owned_by", "laya");
            data.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("object", "list");
        out.put("data", data);
        return out;
    }

    @ExceptionHandler(LayaApiException.class)
    public ResponseEntity<Map<String, Object>> handle(LayaApiException e) {
        return ResponseEntity.status(e.status()).body(e.body());
    }
}
