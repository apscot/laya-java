package io.github.apscot.laya.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hugging Face {@code tokenizer.json} byte-level BPE (NFC normalizer, GPT-2 style split, added tokens),
 * as used by ModernBERT / mmBERT. Special tokens are not added automatically.
 */
public final class Tokenizer {
    private static final Pattern SPLIT = Pattern.compile(
            "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+",
            Pattern.UNICODE_CHARACTER_CLASS);

    private static final class Added {
        final String content;
        final int id;
        final boolean lstrip;
        final boolean rstrip;

        Added(String content, int id, boolean lstrip, boolean rstrip) {
            this.content = content;
            this.id = id;
            this.lstrip = lstrip;
            this.rstrip = rstrip;
        }
    }

    private final Map<String, Integer> vocab = new HashMap<>();
    private final Map<Long, int[]> merges = new HashMap<>(); // pair -> {rank, mergedId}
    private final List<Added> added = new ArrayList<>();
    private final Map<Character, Added> addedByFirst = new HashMap<>();
    private final Map<String, int[]> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private final char[] byteToChar = new char[256];
    private final Map<Character, Integer> charToByte = new HashMap<>();

    private boolean metaspace;
    private boolean prependAlways;
    private int unkId = -1;
    private final int[] byteFallback = new int[256];

    public final int clsId;
    public final int sepId;
    public final int padId;
    public final int maskId;
    public final String maskToken;

    public Tokenizer(Path dir) throws IOException {
        Map<String, Object> tj = Json.parseObject(Files.readString(dir.resolve("tokenizer.json"), StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) tj.get("model");
        if (!"BPE".equals(model.get("type"))) throw new IOException("Unsupported tokenizer model " + model.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) model.get("vocab");
        for (Map.Entry<String, Object> e : v.entrySet()) vocab.put(e.getKey(), ((Number) e.getValue()).intValue());
        @SuppressWarnings("unchecked")
        List<Object> ms = (List<Object>) model.get("merges");
        int rank = 0;
        for (Object m : ms) {
            String a, b;
            if (m instanceof List) {
                @SuppressWarnings("unchecked") List<Object> p = (List<Object>) m;
                a = (String) p.get(0);
                b = (String) p.get(1);
            } else {
                String[] p = ((String) m).split(" ", 2);
                a = p[0];
                b = p[1];
            }
            Integer ia = vocab.get(a), ib = vocab.get(b), im = vocab.get(a + b);
            if (ia == null || ib == null || im == null) { rank++; continue; }
            merges.putIfAbsent(pair(ia, ib), new int[] {rank, im});
            rank++;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> pre = (Map<String, Object>) tj.get("pre_tokenizer");
        if (pre != null && "Metaspace".equals(pre.get("type"))) {
            metaspace = true;
            prependAlways = "always".equals(pre.getOrDefault("prepend_scheme", "always"));
            Object unk = model.get("unk_token");
            if (unk != null && vocab.containsKey((String) unk)) unkId = vocab.get((String) unk);
            for (int b = 0; b < 256; b++) {
                Integer id = vocab.get(String.format("<0x%02X>", b));
                byteFallback[b] = id == null ? unkId : id;
            }
        }
        @SuppressWarnings("unchecked")
        List<Object> at = (List<Object>) tj.get("added_tokens");
        for (Object o : at) {
            @SuppressWarnings("unchecked") Map<String, Object> t = (Map<String, Object>) o;
            added.add(new Added((String) t.get("content"), ((Number) t.get("id")).intValue(),
                    Boolean.TRUE.equals(t.get("lstrip")), Boolean.TRUE.equals(t.get("rstrip"))));
        }
        // longest first so the first match at a position is the leftmost-longest
        added.sort((x, y) -> y.content.length() - x.content.length());

        int n = 0;
        boolean[] keep = new boolean[256];
        for (int b = 33; b <= 126; b++) keep[b] = true;
        for (int b = 161; b <= 172; b++) keep[b] = true;
        for (int b = 174; b <= 255; b++) keep[b] = true;
        for (int b = 0; b < 256; b++) {
            char c = keep[b] ? (char) b : (char) (256 + n++);
            byteToChar[b] = c;
            charToByte.put(c, b);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = Json.parseObject(Files.readString(dir.resolve("tokenizer_config.json"), StandardCharsets.UTF_8));
        maskToken = tokenName(cfg.get("mask_token"));
        clsId = idOf(tokenName(cfg.get("cls_token")));
        sepId = idOf(tokenName(cfg.get("sep_token")));
        padId = idOf(tokenName(cfg.get("pad_token")));
        maskId = idOf(maskToken);
    }

    private static String tokenName(Object o) {
        if (o instanceof Map) return (String) ((Map<?, ?>) o).get("content");
        return (String) o;
    }

    private int idOf(String token) {
        for (Added a : added) if (a.content.equals(token)) return a.id;
        Integer id = vocab.get(token);
        if (id == null) throw new IllegalArgumentException("Tokenizer is missing token " + token);
        return id;
    }

    private static long pair(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    /** Encode without special tokens. */
    public int[] encode(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFC);
        IntList out = new IntList();
        int segStart = 0;
        int i = 0;
        int n = s.length();
        while (i < n) {
            Added hit = null;
            for (Added a : added) {
                if (s.startsWith(a.content, i)) { hit = a; break; }
            }
            if (hit == null) { i++; continue; }
            int from = i;
            int to = i + hit.content.length();
            int segEnd = from;
            if (hit.lstrip) while (segEnd > segStart && Character.isWhitespace(s.charAt(segEnd - 1))) segEnd--;
            if (hit.rstrip) while (to < n && Character.isWhitespace(s.charAt(to))) to++;
            if (segEnd > segStart) encodeSegment(s.substring(segStart, segEnd), out);
            out.add(hit.id);
            segStart = to;
            i = to;
        }
        if (segStart < n) encodeSegment(s.substring(segStart), out);
        return out.toArray();
    }

    private void encodeSegment(String seg, IntList out) {
        if (metaspace) { encodeMetaspace(seg, out); return; }
        Matcher m = SPLIT.matcher(seg);
        while (m.find()) {
            String piece = m.group();
            int[] ids = cache.get(piece);
            if (ids == null) {
                ids = bpe(piece);
                if (cache.size() < 100_000) cache.put(piece, ids);
            }
            for (int id : ids) out.add(id);
        }
    }

    private void encodeMetaspace(String seg, IntList out) {
        String t = seg.replace(' ', '\u2581');
        if (prependAlways && !t.startsWith("\u2581")) t = "\u2581" + t;
        // split before every metaspace so each piece starts with one
        int start = 0;
        for (int i = 1; i <= t.length(); i++) {
            if (i == t.length() || t.charAt(i) == '\u2581') {
                if (i > start) {
                    String piece = t.substring(start, i);
                    int[] ids = cache.get(piece);
                    if (ids == null) {
                        ids = bpeSymbols(piece);
                        if (cache.size() < 100_000) cache.put(piece, ids);
                    }
                    for (int id : ids) out.add(id);
                }
                start = i;
            }
        }
    }

    private int[] bpeSymbols(String piece) {
        int[] sym = new int[piece.length() * 4];
        int len = 0;
        for (int i = 0; i < piece.length(); ) {
            int cp = piece.codePointAt(i);
            i += Character.charCount(cp);
            Integer id = vocab.get(new String(Character.toChars(cp)));
            if (id != null) { sym[len++] = id; continue; }
            for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                if (len == sym.length) sym = java.util.Arrays.copyOf(sym, len * 2);
                sym[len++] = byteFallback[b & 0xFF];
            }
        }
        return mergeSymbols(sym, len);
    }

    private int[] bpe(String piece) {
        byte[] bytes = piece.getBytes(StandardCharsets.UTF_8);
        int[] sym = new int[bytes.length];
        int len = bytes.length;
        for (int k = 0; k < len; k++) {
            Integer id = vocab.get(String.valueOf(byteToChar[bytes[k] & 0xFF]));
            if (id == null) throw new IllegalStateException("byte missing from vocab");
            sym[k] = id;
        }
        return mergeSymbols(sym, len);
    }

    private int[] mergeSymbols(int[] sym, int len) {
        while (len > 1) {
            int best = Integer.MAX_VALUE, bestAt = -1, mergedId = -1;
            for (int k = 0; k + 1 < len; k++) {
                int[] r = merges.get(pair(sym[k], sym[k + 1]));
                if (r != null && r[0] < best) { best = r[0]; bestAt = k; mergedId = r[1]; }
            }
            if (bestAt < 0) break;
            int a = sym[bestAt], b = sym[bestAt + 1];
            int w = 0;
            for (int k = 0; k < len; k++) {
                if (k + 1 < len && sym[k] == a && sym[k + 1] == b) { sym[w++] = mergedId; k++; }
                else sym[w++] = sym[k];
            }
            len = w;
        }
        int[] res = new int[len];
        System.arraycopy(sym, 0, res, 0, len);
        return res;
    }

    /** Small growable int list. */
    static final class IntList {
        private int[] a = new int[64];
        private int n;

        void add(int v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        int size() { return n; }

        int[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
