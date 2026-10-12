package io.github.apscot.laya.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loaded Laya checkpoint. {@link #predict} scores typed questions about a state and returns the same
 * result structure as {@code laya_mlx.Agent.predict} / the Jev {@code /v1/systemone} response.
 */
public final class Agent implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Agent.class.getName());

    private final SafeTensors weights;
    private final DecisionModel model;
    private final Tokenizer tok;
    private final int maxLen, headMaxLen;
    private final double[] temperature = new double[3];
    private final Map<String, Double> temperatureByOptions = new LinkedHashMap<>();
    private final String modelId;
    public final Path modelDir;

    private Agent(String modelId, Path dir, Gemm gemm) throws IOException {
        this.modelId = modelId;
        this.modelDir = dir;
        Map<String, Object> cfg = Json.parseObject(Files.readString(dir.resolve("rl_agent_config.json")));
        Map<String, Object> enc = Json.parseObject(Files.readString(dir.resolve("encoder/config.json")));
        if (!cfg.containsKey("encoder") || !cfg.containsKey("head_layers"))
            throw new IllegalArgumentException("Laya config must specify encoder and head_layers");
        Object layout = cfg.getOrDefault("option_layout", "sequential");
        if ("parallel".equals(layout)) throw new IllegalArgumentException("option_layout 'parallel' is not supported yet");
        if (!"sequential".equals(layout)) throw new IllegalArgumentException("Unsupported option_layout: " + layout);
        EncoderConfig ec = new EncoderConfig(enc);
        maxLen = ((Number) cfg.getOrDefault("max_len", 512)).intValue();
        headMaxLen = ((Number) cfg.getOrDefault("head_max_len", 192)).intValue();
        if (!(4 < headMaxLen && headMaxLen < maxLen && maxLen <= num(enc, "max_position_embeddings", 8192)))
            throw new IllegalArgumentException("Expected 4 < head_max_len < max_len <= max_position_embeddings");
        @SuppressWarnings("unchecked") List<Object> t = (List<Object>) cfg.getOrDefault("temperature", List.of(1.0, 1.0, 1.0));
        if (t.size() != 3) throw new IllegalArgumentException("Calibration temperatures must be finite and positive");
        StringBuilder rejected = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            double raw = ((Number) t.get(i)).doubleValue();
            if (!(raw > 0) || Double.isInfinite(raw)) throw new IllegalArgumentException("Calibration temperatures must be finite and positive");
            temperature[i] = Calibration.clamp(raw);
            if (temperature[i] != raw) rejected.append(" temperature[").append(i).append("]=").append(raw);
        }
        @SuppressWarnings("unchecked") Map<String, Object> tb = (Map<String, Object>) cfg.getOrDefault("temperature_by_options", Map.of());
        for (Map.Entry<String, Object> e : tb.entrySet()) {
            double raw = ((Number) e.getValue()).doubleValue();
            if (!(raw > 0) || Double.isInfinite(raw)) throw new IllegalArgumentException("Calibration temperatures must be finite and positive");
            double c = Calibration.clamp(raw);
            temperatureByOptions.put(e.getKey(), c);
            if (c != raw) rejected.append(' ').append(e.getKey()).append('=').append(raw);
        }
        if (rejected.length() > 0)
            LOG.warning("laya: checkpoint ships temperatures outside [" + Calibration.TEMP_MIN + ", " + Calibration.TEMP_MAX
                    + "]; clamping" + rejected + ". Treat confidence from the affected buckets as uncalibrated.");
        tok = new Tokenizer(dir.resolve("tokenizer"));
        weights = new SafeTensors(dir.resolve("model.safetensors"));
        model = new DecisionModel(ec, ((Number) cfg.get("head_layers")).intValue(), weights, gemm);
    }

    private static int num(Map<String, Object> m, String k, int d) {
        Object o = m.get(k);
        return o == null ? d : ((Number) o).intValue();
    }

    /** Load from a directory or a Hugging Face repo id (downloaded once into {@code ~/.cache/laya}). */
    public static Agent load(String modelIdOrPath) throws IOException {
        return load(modelIdOrPath, null, null, Gemm.best());
    }

    public static Agent load(String modelIdOrPath, String revision, String subfolder, Gemm gemm) throws IOException {
        Path dir = ModelStore.resolve(modelIdOrPath, revision, subfolder);
        return new Agent(modelIdOrPath, dir, gemm);
    }

    public String modelId() {
        return modelId;
    }

    public int maxLength() {
        return maxLen;
    }

    /**
     * @param state text, a JSON-like Map/List (serialized like Python's json.dumps) or a conversation list
     * @param questions question id -> question, evaluated independently against the same state
     * @return {@code {model, answers{id -> answer}, usage}} as nested maps (JSON-ready)
     */
    public Map<String, Object> predict(Object state, Map<String, Question> questions) {
        if (state == null) throw new IllegalArgumentException("state must not be None");
        if (questions == null) throw new IllegalArgumentException("questions must be a dictionary keyed by question id");
        Map<String, Object> answers = new LinkedHashMap<>();
        int inputTokens = 0, stateTokens = 0, maxDropped = 0;
        List<String> truncatedQs = new ArrayList<>();
        Map<String, Object> collapsed = new LinkedHashMap<>();
        if (!questions.isEmpty()) {
            int[] stateIds = tok.encode(Prompt.serializeState(state).replace(tok.maskToken, " "));
            boolean left = state instanceof List;
            for (Map.Entry<String, Question> e : questions.entrySet()) {
                String qid = e.getKey();
                if (qid == null || qid.trim().isEmpty()) throw new IllegalArgumentException("Question id must be a nonempty string or integer");
                Question q = e.getValue();
                Prompt.Sequence s;
                try {
                    s = Prompt.build(tok, stateIds, q, maxLen, headMaxLen, left);
                    if (s.markers.length != s.options)
                        throw new IllegalArgumentException("too many options for the token budget");
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("Question '" + qid + "': " + ex.getMessage(), ex);
                }
                inputTokens += s.ids.length;
                stateTokens = s.stateTokens;
                maxDropped = Math.max(maxDropped, s.stateDropped);
                if (s.truncated) truncatedQs.add(qid);
                if (s.optionsDistinct < s.options) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("total", (long) s.options);
                    c.put("distinct", (long) s.optionsDistinct);
                    c.put("tokens_per_option", s.tokensPerOption == null ? null : (long) s.tokensPerOption);
                    collapsed.put(qid, c);
                }
                answers.put(qid, answer(q, s));
            }
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", (long) inputTokens);
        usage.put("output_tokens", 0L);
        usage.put("state_tokens", (long) stateTokens);
        usage.put("state_tokens_dropped", (long) maxDropped);
        usage.put("truncated", maxDropped > 0);
        usage.put("truncated_questions", truncatedQs);
        if (!collapsed.isEmpty()) usage.put("options", collapsed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", "laya-rl-agent");
        result.put("answers", answers);
        result.put("usage", usage);
        return result;
    }

    private Map<String, Object> answer(Question q, Prompt.Sequence s) {
        int k = s.markers.length, qt = q.type.index;
        DecisionModel.Output out = model.forward(s.ids, s.markers, qt);
        for (float v : out.logits) if (Float.isNaN(v) || Float.isInfinite(v)) throw new ArithmeticException("Non-finite model outputs");
        double scale = temperatureByOptions.getOrDefault(Calibration.bucket(qt, k), temperature[qt]);
        double[] p = new double[k];
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < k; i++) { p[i] = out.logits[i] / scale; max = Math.max(max, p[i]); }
        double sum = 0;
        for (int i = 0; i < k; i++) { p[i] = Math.exp(p[i] - max); sum += p[i]; }
        for (int i = 0; i < k; i++) p[i] /= sum;

        double[] a = {out.actLogits[0], out.actLogits[1]};
        double am = Math.max(a[0], a[1]);
        double e0 = Math.exp(a[0] - am), e1 = Math.exp(a[1] - am);

        Map<String, Object> ans = new LinkedHashMap<>();
        ans.put("type", q.type.wire);
        ans.put("confidence", Calibration.round4(Calibration.entropyConfidence(p, k)));
        ans.put("answer_confidence", Calibration.round4(Calibration.answerConfidence(p, k)));
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("act_probability", Calibration.round4(e0 / (e0 + e1)));
        ans.put("action", action);
        switch (q.type) {
            case CHOICE: {
                List<String> labels = new ArrayList<>(q.criteriaMap.keySet());
                int best = 0;
                for (int i = 1; i < k; i++) if (p[i] > p[best]) best = i;
                Map<String, Object> probs = new LinkedHashMap<>();
                for (int i = 0; i < k; i++) probs.put(labels.get(i), Calibration.round4(p[i]));
                ans.put("choice", labels.get(best));
                ans.put("probabilities", probs);
                break;
            }
            case SCORE: {
                double score = 0;
                Map<String, Object> legend = new LinkedHashMap<>(), probs = new LinkedHashMap<>();
                for (int i = 0; i < k; i++) {
                    score += i * p[i];
                    legend.put(String.valueOf(i), q.levels.get(i));
                    probs.put(String.valueOf(i), Calibration.round4(p[i]));
                }
                ans.put("score", Calibration.round4(score));
                ans.put("legend", legend);
                ans.put("probabilities", probs);
                break;
            }
            default:
                ans.put("noul", Calibration.round4(p[1]));
                ans.put("confidence", Calibration.round4(Math.max(p[1], 1.0 - p[1])));
        }
        return ans;
    }

    @Override
    public void close() throws IOException {
        weights.close();
    }
}
