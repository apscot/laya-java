package io.github.apscot.laya.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Laya prompt/marker construction (port of laya_mlx/common.py). */
final class Prompt {
    private Prompt() {}

    /** Token ids for one question, with marker positions and diagnostics. */
    static final class Sequence {
        int[] ids;
        int[] markers;
        int options, optionsDistinct;
        Integer tokensPerOption;
        int stateTokens, stateUsed, stateDropped;
        boolean truncated;
    }

    static String serializeState(Object state) {
        return state instanceof String ? (String) state : Json.dumps(state);
    }

    static String renderCriterion(Object v) {
        return v instanceof String ? (String) v : Json.dumps(v);
    }

    static String[] resolveNoulLabels(Map<String, ?> labels) {
        String bad = "noul labels must map exactly 'false' and 'true' to distinct non-empty strings";
        if (labels == null) return new String[] {"false", "true"};
        if (labels.size() != 2 || !labels.containsKey("false") || !labels.containsKey("true")) throw new IllegalArgumentException(bad);
        Object f = labels.get("false"), t = labels.get("true");
        if (!(f instanceof String) || !(t instanceof String)) throw new IllegalArgumentException(bad);
        String fs = ((String) f).strip(), ts = ((String) t).strip();
        if (fs.isEmpty() || ts.isEmpty() || fs.equals(ts)) throw new IllegalArgumentException(bad);
        return new String[] {fs, ts};
    }

    static List<String> renderOptions(Question q) {
        List<String> out = new ArrayList<>();
        switch (q.type) {
            case CHOICE:
                for (Map.Entry<String, Object> e : q.criteriaMap.entrySet()) {
                    Object v = e.getValue();
                    out.add(v == null || "".equals(v) ? e.getKey() : e.getKey() + ": " + renderCriterion(v));
                }
                break;
            case SCORE:
                for (int i = 0; i < q.levels.size(); i++) out.add("level " + i + ": " + renderCriterion(q.levels.get(i)));
                break;
            default: {
                String[] lab = resolveNoulLabels(q.labels);
                Object fc = q.criteriaMap == null ? null : q.criteriaMap.get("false");
                Object tc = q.criteriaMap == null ? null : q.criteriaMap.get("true");
                out.add(lab[0] + ": " + (fc != null && !"".equals(fc) ? renderCriterion(fc) : "no, the statement does not hold"));
                out.add(lab[1] + ": " + (tc != null && !"".equals(tc) ? renderCriterion(tc) : "yes, the statement holds"));
            }
        }
        return out;
    }

    static Sequence build(Tokenizer tok, int[] stateIds, Question q, int maxLen, int headMaxLen, boolean truncateLeft) {
        List<String> opts = renderOptions(q);
        String mask = tok.maskToken;
        int[] head = tok.encode(q.type.wire + " question: " + q.instructions.replace(mask, " "));
        List<int[]> opt = new ArrayList<>();
        for (String o : opts) {
            int[] t = tok.encode(" " + o.replace(mask, " "));
            int len = Math.min(48, t.length);
            int[] r = new int[1 + len];
            r[0] = tok.maskId;
            System.arraycopy(t, 0, r, 1, len);
            opt.add(r);
        }
        int total = 0;
        for (int[] o : opt) total += o.length;
        int budget = headMaxLen - total;
        Integer per = null;
        if (budget < 16) {
            int p = Math.max(4, (headMaxLen - 16) / Math.max(1, opt.size()));
            per = p;
            for (int i = 0; i < opt.size(); i++) if (opt.get(i).length > p) opt.set(i, Arrays.copyOf(opt.get(i), p));
            total = 0;
            for (int[] o : opt) total += o.length;
            budget = headMaxLen - total;
        }
        head = Arrays.copyOf(head, Math.min(head.length, Math.max(8, budget)));
        Tokenizer.IntList ids = new Tokenizer.IntList();
        ids.add(tok.clsId);
        for (int h : head) ids.add(h);
        ids.add(tok.sepId);
        int[] markers = new int[opt.size()];
        for (int i = 0; i < opt.size(); i++) {
            markers[i] = ids.size();
            for (int t : opt.get(i)) ids.add(t);
        }
        ids.add(tok.sepId);
        int[] prefix = ids.toArray();

        int room = Math.max(0, maxLen - prefix.length - 1);
        int from = truncateLeft ? Math.max(0, stateIds.length - room) : 0;
        int to = truncateLeft ? stateIds.length : Math.min(stateIds.length, room);
        int kept = to - from;
        int[] full = new int[prefix.length + kept + 1];
        System.arraycopy(prefix, 0, full, 0, prefix.length);
        System.arraycopy(stateIds, from, full, prefix.length, kept);
        full[prefix.length + kept] = tok.sepId;

        Sequence s = new Sequence();
        s.ids = full.length > maxLen ? Arrays.copyOf(full, maxLen) : full;
        s.markers = Arrays.stream(markers).filter(m -> m < maxLen).toArray();
        s.options = opt.size();
        Set<String> distinct = new HashSet<>();
        for (int[] o : opt) distinct.add(Arrays.toString(o));
        s.optionsDistinct = distinct.size();
        s.tokensPerOption = per;
        s.stateTokens = stateIds.length;
        s.stateUsed = kept;
        s.stateDropped = stateIds.length - kept;
        s.truncated = kept < stateIds.length;
        return s;
    }
}
