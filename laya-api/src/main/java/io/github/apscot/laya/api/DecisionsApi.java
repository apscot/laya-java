package io.github.apscot.laya.api;

import io.github.apscot.laya.core.Question;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI Decisions protocol: {@code POST /v1/decisions} with {@code predicate}, {@code choice} and {@code score}
 * questions, answered by a local Laya checkpoint. Text input only.
 */
public final class DecisionsApi {
    private static final LayaApiException.Style S = LayaApiException.Style.OPENAI;
    private final ModelRegistry registry;

    public DecisionsApi(ModelRegistry registry) {
        this.registry = registry;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> handle(Map<String, Object> request) {
        if (request == null) throw bad("request body must be a JSON object");
        Object input = request.get("input");
        if (input == null) throw bad("'input' is required");
        Object qs = request.get("questions");
        if (!(qs instanceof List) || ((List<?>) qs).isEmpty()) throw bad("'questions' must be a non-empty array");
        String state = inputText(input);
        if (state.length() > SystemOneApi.MAX_state_CHARS) throw bad("'input' is too long");
        Map<String, Question> questions = new LinkedHashMap<>();
        Map<String, Map<String, Object>> defs = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : (List<Object>) qs) {
            if (!(o instanceof Map)) throw bad("each question must be an object");
            Map<String, Object> q = (Map<String, Object>) o;
            Object name = q.get("name");
            if (!(name instanceof String) || ((String) name).isBlank()) throw bad("each question needs a non-empty 'name'");
            if (!seen.add((String) name)) throw bad("question names must be unique: " + name);
            try {
                questions.put((String) name, toQuestion(q));
            } catch (IllegalArgumentException ex) {
                throw bad("question '" + name + "': " + ex.getMessage());
            }
            defs.put((String) name, q);
        }
        if (questions.size() > SystemOneApi.MAX_QUESTIONS) throw bad("at most " + SystemOneApi.MAX_QUESTIONS + " questions per request");
        Object model = request.get("model");
        String name = registry.route(model instanceof String ? (String) model : null, state);
        Map<String, Object> raw;
        try {
            raw = registry.predict(name, state, questions);
        } catch (IllegalArgumentException ex) {
            throw bad(ex.getMessage());
        }
        Map<String, Object> answersIn = (Map<String, Object>) raw.get("answers");
        List<Object> answers = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : defs.entrySet()) {
            Map<String, Object> a = (Map<String, Object>) answersIn.get(e.getKey());
            answers.add(convert(e.getKey(), e.getValue(), a));
        }
        Map<String, Object> u = (Map<String, Object>) raw.get("usage");
        long in = ((Number) u.get("input_tokens")).longValue();
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", in);
        usage.put("output_tokens", 0L);
        usage.put("total_tokens", in);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("object", "decision");
        out.put("model", model instanceof String ? model : name);
        out.put("answers", answers);
        out.put("usage", usage);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String inputText(Object input) {
        if (input instanceof String) return (String) input;
        if (!(input instanceof List)) throw bad("'input' must be a string or an array of messages");
        StringBuilder sb = new StringBuilder();
        for (Object m : (List<Object>) input) {
            if (!(m instanceof Map)) throw bad("each input message must be an object");
            Object content = ((Map<String, Object>) m).get("content");
            if (content instanceof String) { append(sb, (String) content); continue; }
            if (!(content instanceof List)) throw bad("message 'content' must be a string or an array of parts");
            for (Object p : (List<Object>) content) {
                Map<String, Object> part = (Map<String, Object>) p;
                String type = String.valueOf(part.get("type"));
                if (type.equals("input_text") || type.equals("text")) append(sb, String.valueOf(part.get("text")));
                else if (type.equals("input_image")) throw bad("image input is not supported by this local text model");
                else throw bad("unsupported input part type '" + type + "'");
            }
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append('\n');
        sb.append(s);
    }

    @SuppressWarnings("unchecked")
    private static Question toQuestion(Map<String, Object> q) {
        String type = String.valueOf(q.get("type"));
        Object ins = q.get("instructions");
        if (!(ins instanceof String) || ((String) ins).isBlank()) throw new IllegalArgumentException("'instructions' is required");
        switch (type) {
            case "predicate":
                return Question.noul((String) ins, null);
            case "choice": {
                Object ch = q.get("choices");
                if (!(ch instanceof List) || ((List<?>) ch).isEmpty()) throw new IllegalArgumentException("'choices' must be a non-empty array");
                Map<String, Object> m = new LinkedHashMap<>();
                for (Object c : (List<Object>) ch) {
                    Map<String, Object> cm = (Map<String, Object>) c;
                    Object v = cm.get("value");
                    if (!(v instanceof String)) throw new IllegalArgumentException("each choice needs a string 'value'");
                    if (m.containsKey(v)) throw new IllegalArgumentException("choice values must be unique");
                    m.put((String) v, cm.get("description"));
                }
                return Question.choice((String) ins, m);
            }
            case "score": {
                Object lv = q.get("levels");
                if (!(lv instanceof List) || ((List<?>) lv).size() < 2) throw new IllegalArgumentException("'levels' must have at least 2 entries");
                List<Object> levels = new ArrayList<>();
                for (Object l : (List<Object>) lv) {
                    Map<String, Object> lm = (Map<String, Object>) l;
                    Object label = lm.get("label"), d = lm.get("description");
                    levels.add(d == null || "".equals(d) ? String.valueOf(label) : label + ": " + d);
                }
                return Question.score((String) ins, levels);
            }
            default:
                throw new IllegalArgumentException("unknown question type '" + type + "'; expected predicate, choice or score");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> convert(String name, Map<String, Object> def, Map<String, Object> a) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", name);
        String type = (String) def.get("type");
        o.put("type", type);
        switch (type) {
            case "predicate":
                o.put("probability", a.get("noul"));
                break;
            case "choice": {
                o.put("choice", a.get("choice"));
                List<Object> ps = new ArrayList<>();
                for (Map.Entry<String, Object> e : ((Map<String, Object>) a.get("probabilities")).entrySet()) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("value", e.getKey());
                    p.put("probability", e.getValue());
                    ps.add(p);
                }
                o.put("probabilities", ps);
                o.put("confidence", a.get("answer_confidence"));
                break;
            }
            default: {
                o.put("score", a.get("score"));
                List<Object> levels = (List<Object>) def.get("levels");
                List<Object> ps = new ArrayList<>();
                int i = 0;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) a.get("probabilities")).entrySet()) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("value", (long) i);
                    p.put("label", ((Map<String, Object>) levels.get(i)).get("label"));
                    p.put("probability", e.getValue());
                    ps.add(p);
                    i++;
                }
                o.put("probabilities", ps);
                o.put("confidence", a.get("answer_confidence"));
            }
        }
        return o;
    }

    private static LayaApiException bad(String m) {
        return new LayaApiException(400, S, m);
    }
}
