package io.github.apscot.laya.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One typed question: {@code choice}, {@code score} or {@code noul} (yes/no probability). */
public final class Question {
    public enum Type {
        CHOICE("choice", 0), SCORE("score", 1), NOUL("noul", 2);

        public final String wire;
        public final int index;

        Type(String wire, int index) {
            this.wire = wire;
            this.index = index;
        }

        public static Type of(String s) {
            for (Type t : values()) if (t.wire.equals(s) || (t == NOUL && "predicate".equals(s))) return t;
            throw new IllegalArgumentException("Unknown question type '" + s + "'; expected choice, score, or noul");
        }
    }

    public final Type type;
    /** Instructions already rendered to text. */
    public final String instructions;
    /** choice: label -> description (nullable values, insertion ordered); noul: "false"/"true" -> description. */
    public final Map<String, Object> criteriaMap;
    /** score: ordered level descriptions. */
    public final List<Object> levels;
    /** noul only: display words for false/true. */
    public final Map<String, Object> labels;

    private Question(Type type, String instructions, Map<String, Object> criteriaMap, List<Object> levels, Map<String, Object> labels) {
        this.type = type;
        this.instructions = instructions;
        this.criteriaMap = criteriaMap;
        this.levels = levels;
        this.labels = labels;
    }

    public static Question choice(String instructions, Map<String, ?> criteria) {
        return new Question(Type.CHOICE, nonBlank(instructions), copy(criteria), null, null);
    }

    public static Question choice(String instructions, List<String> labels) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String l : labels) m.put(l, null);
        if (m.size() != labels.size()) throw new IllegalArgumentException("Choice labels must be unique");
        return new Question(Type.CHOICE, nonBlank(instructions), m, null, null);
    }

    public static Question score(String instructions, List<?> levels) {
        for (Object o : levels) if (o == null) throw new IllegalArgumentException("Score criteria must not contain a null level");
        return new Question(Type.SCORE, nonBlank(instructions), null, new ArrayList<Object>(levels), null);
    }

    public static Question noul(String instructions, Map<String, ?> criteria) {
        return noul(instructions, criteria, null);
    }

    public static Question noul(String instructions, Map<String, ?> criteria, Map<String, ?> labels) {
        Map<String, Object> c = null;
        if (criteria != null) {
            c = new LinkedHashMap<>();
            for (Map.Entry<String, ?> e : criteria.entrySet()) c.put(String.valueOf(e.getKey()).toLowerCase(), e.getValue());
            for (String k : c.keySet())
                if (!k.equals("false") && !k.equals("true"))
                    throw new IllegalArgumentException("Noul criteria must be keyed only 'false'/'true'; use labels to change display text");
        }
        Map<String, Object> l = labels == null ? null : copy(labels);
        if (l != null) Prompt.resolveNoulLabels(l);
        return new Question(Type.NOUL, nonBlank(instructions), c, null, l);
    }

    private static Map<String, Object> copy(Map<String, ?> m) {
        if (m == null || m.isEmpty()) throw new IllegalArgumentException("Choice criteria must be a nonempty dictionary or list");
        return new LinkedHashMap<String, Object>(m);
    }

    private static String nonBlank(String s) {
        if (s == null || s.trim().isEmpty()) throw new IllegalArgumentException("instructions must not be empty or blank");
        return s;
    }

    /** Parse the JSON-ish map form used by the Python API and the /v1/systemone request. */
    @SuppressWarnings("unchecked")
    public static Question fromMap(Map<String, Object> q) {
        Object t = q.get("type");
        if (!(t instanceof String)) throw new IllegalArgumentException("Unknown question type " + t + "; expected choice, score, or noul");
        Type type = Type.of((String) t);
        if (!q.containsKey("instructions")) throw new IllegalArgumentException("Question is missing instructions");
        Object ins = q.get("instructions");
        if (ins == null || (ins instanceof Map && ((Map<?, ?>) ins).isEmpty()) || (ins instanceof List && ((List<?>) ins).isEmpty()))
            throw new IllegalArgumentException("instructions must not be empty or None");
        String text = ins instanceof String ? (String) ins : Json.dumps(ins);
        Object crit = q.get("criteria");
        switch (type) {
            case CHOICE:
                if (crit instanceof List) {
                    List<String> labels = new ArrayList<>();
                    for (Object o : (List<Object>) crit) {
                        if (!(o instanceof String)) throw new IllegalArgumentException("Choice labels must be strings");
                        labels.add((String) o);
                    }
                    return choice(text, labels);
                }
                if (!(crit instanceof Map)) throw new IllegalArgumentException("Choice criteria must be a nonempty dictionary or list");
                return choice(text, (Map<String, Object>) crit);
            case SCORE:
                if (!(crit instanceof List) || ((List<?>) crit).isEmpty())
                    throw new IllegalArgumentException("Score criteria must be a nonempty list");
                return score(text, (List<Object>) crit);
            default:
                if (crit != null && !(crit instanceof Map))
                    throw new IllegalArgumentException("Noul criteria must be a dictionary with false/true descriptions");
                Object lab = q.get("labels");
                if (lab != null && !(lab instanceof Map)) throw new IllegalArgumentException("labels must be an object");
                return noul(text, (Map<String, Object>) crit, (Map<String, Object>) lab);
        }
    }
}
