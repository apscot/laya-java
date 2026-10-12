package io.github.apscot.laya.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Not a pass/fail test: prints warm latency for a short question. */
class LatencyBench {
    @SuppressWarnings("unchecked")
    @Test
    void warmLatency() throws Exception {
        Path dir = Checkpoints.dir("");
        Map<String, Object> ref = Checkpoints.reference("");
        EncoderConfig cfg = new EncoderConfig(Json.parseObject(Files.readString(dir.resolve("encoder/config.json"))));
        try (SafeTensors st = new SafeTensors(dir.resolve("model.safetensors"))) {
            DecisionModel m = new DecisionModel(cfg, 2, st, Gemm.best());
            Map<String, Object> c = (Map<String, Object>) ((List<Object>) ref.get("cases")).get(1);
            Map<String, Object> p = (Map<String, Object>) ((List<Object>) c.get("prepared")).get(0);
            int[] ids = ((List<Object>) p.get("ids")).stream().mapToInt(x -> ((Number) x).intValue()).toArray();
            int[] mk = ((List<Object>) p.get("markers")).stream().mapToInt(x -> ((Number) x).intValue()).toArray();
            for (int i = 0; i < 15; i++) m.forward(ids, mk, 0);
            List<Double> ms = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                long t = System.nanoTime();
                m.forward(ids, mk, 0);
                ms.add((System.nanoTime() - t) / 1e6);
            }
            Collections.sort(ms);
            System.out.printf("BENCH tokens=%d p50=%.1f ms p95=%.1f ms cores=%d%n", ids.length, ms.get(15), ms.get(28),
                    Runtime.getRuntime().availableProcessors());
        }
    }
}
