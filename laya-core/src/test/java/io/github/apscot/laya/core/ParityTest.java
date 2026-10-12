package io.github.apscot.laya.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Compares the Java port with golden outputs produced by the Python reference. */
class ParityTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "multilingual", "typed"})
    @SuppressWarnings("unchecked")
    void tokenizerMatchesPython(String tag) throws Exception {
        Path dir = Checkpoints.dir(tag);
        Tokenizer tok = new Tokenizer(dir.resolve("tokenizer"));
        for (Object o : (List<Object>) Checkpoints.reference(tag).get("tokenizer")) {
            Map<String, Object> c = (Map<String, Object>) o;
            assertArrayEquals(Checkpoints.ints(c.get("ids")), tok.encode((String) c.get("text")), tag + " text: " + c.get("text"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "multilingual", "typed"})
    @SuppressWarnings("unchecked")
    void logitsMatchPython(String tag) throws Exception {
        Path dir = Checkpoints.dir(tag);
        Map<String, Object> ref = Checkpoints.reference(tag);
        EncoderConfig cfg = new EncoderConfig(Json.parseObject(Files.readString(dir.resolve("encoder/config.json"))));
        Map<String, Object> agent = Json.parseObject(Files.readString(dir.resolve("rl_agent_config.json")));
        Gemm g = Gemm.best();
        try (SafeTensors st = new SafeTensors(dir.resolve("model.safetensors"))) {
            DecisionModel m = new DecisionModel(cfg, ((Number) agent.get("head_layers")).intValue(), st, g);
            Map<String, Object> probe = (Map<String, Object>) ref.get("probe");
            float[] h = m.encode(Checkpoints.ints(probe.get("ids")));
            List<Object> first = (List<Object>) probe.get("enc_first");
            for (int t = 0; t < 3; t++)
                for (int i = 0; i < 8; i++)
                    assertEquals(((Number) ((List<Object>) first.get(t)).get(i)).doubleValue(), h[t * cfg.hidden + i], 2e-4, tag + " enc[" + t + "][" + i + "]");
            for (Object co : (List<Object>) ref.get("cases")) {
                Map<String, Object> c = (Map<String, Object>) co;
                List<Object> prepared = (List<Object>) c.get("prepared"), logits = (List<Object>) c.get("logits");
                for (int q = 0; q < prepared.size(); q++) {
                    Map<String, Object> p = (Map<String, Object>) prepared.get(q);
                    DecisionModel.Output o = m.forward(Checkpoints.ints(p.get("ids")), Checkpoints.ints(p.get("markers")), ((Number) p.get("qtype")).intValue());
                    List<Object> exp = (List<Object>) logits.get(q);
                    for (int j = 0; j < exp.size(); j++)
                        assertEquals(((Number) exp.get(j)).doubleValue(), o.logits[j], 2e-3, tag + " " + c.get("name") + " q" + q + " logit " + j);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "multilingual", "typed"})
    @SuppressWarnings("unchecked")
    void predictMatchesPython(String tag) throws Exception {
        Path dir = Checkpoints.dir(tag);
        Map<String, Object> ref = Checkpoints.reference(tag);
        try (Agent agent = Agent.load(dir.toString())) {
            for (Object co : (List<Object>) ref.get("cases")) {
                Map<String, Object> c = (Map<String, Object>) co;
                Map<String, Question> qs = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : ((Map<String, Object>) c.get("questions")).entrySet())
                    qs.put(e.getKey(), Question.fromMap((Map<String, Object>) e.getValue()));
                Map<String, Object> res = agent.predict(c.get("state"), qs);
                Map<String, Object> exp = (Map<String, Object>) ((Map<String, Object>) c.get("result")).get("answers");
                Map<String, Object> got = (Map<String, Object>) res.get("answers");
                for (String qid : exp.keySet()) {
                    Map<String, Object> e = (Map<String, Object>) exp.get(qid), g = (Map<String, Object>) got.get(qid);
                    String where = tag + " " + c.get("name") + "/" + qid;
                    assertEquals(e.get("type"), g.get("type"), where);
                    for (String key : new String[] {"confidence", "answer_confidence", "noul", "score"})
                        if (e.containsKey(key)) assertEquals(((Number) e.get(key)).doubleValue(), ((Number) g.get(key)).doubleValue(), 5e-3, where + " " + key);
                    if (e.containsKey("choice")) assertEquals(e.get("choice"), g.get("choice"), where);
                    if (e.containsKey("probabilities")) {
                        Map<String, Object> ep = (Map<String, Object>) e.get("probabilities"), gp = (Map<String, Object>) g.get("probabilities");
                        assertEquals(List.copyOf(ep.keySet()), List.copyOf(gp.keySet()), where + " keys");
                        for (String k : ep.keySet()) assertEquals(((Number) ep.get(k)).doubleValue(), ((Number) gp.get(k)).doubleValue(), 5e-3, where + " p[" + k + "]");
                    }
                }
                Map<String, Object> eu = (Map<String, Object>) ((Map<String, Object>) c.get("result")).get("usage");
                Map<String, Object> gu = (Map<String, Object>) res.get("usage");
                assertEquals(((Number) eu.get("input_tokens")).longValue(), ((Number) gu.get("input_tokens")).longValue(), where(tag, c) + " input_tokens");
                assertEquals(eu.get("truncated"), gu.get("truncated"), where(tag, c) + " truncated");
            }
        }
    }

    private static String where(String tag, Map<String, Object> c) {
        return tag + " " + c.get("name");
    }
}
