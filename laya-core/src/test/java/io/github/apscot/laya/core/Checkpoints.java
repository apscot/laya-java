package io.github.apscot.laya.core;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Locates checkpoints and Python-generated fixtures for parity tests. */
final class Checkpoints {
    private Checkpoints() {}

    static Path dir(String tag) {
        String key = tag.isEmpty() ? "laya.model.dir" : "laya.model.dir." + tag;
        String d = System.getProperty(key, "");
        assumeTrue(!d.isEmpty() && Files.isDirectory(Path.of(d)), "set " + key + " (env LAYA_MODEL_DIR" + (tag.isEmpty() ? "" : "_" + tag.toUpperCase()) + ")");
        return Path.of(d);
    }

    static Map<String, Object> reference(String tag) throws Exception {
        String f = "/fixtures/reference" + (tag.isEmpty() ? "" : "-" + tag) + ".json";
        try (var in = Checkpoints.class.getResourceAsStream(f)) {
            return Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static int[] ints(Object list) {
        return ((java.util.List<?>) list).stream().mapToInt(x -> ((Number) x).intValue()).toArray();
    }
}
