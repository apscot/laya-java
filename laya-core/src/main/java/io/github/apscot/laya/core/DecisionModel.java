package io.github.apscot.laya.core;

import java.util.Map;
import java.util.stream.IntStream;

/** ModernBERT encoder + Laya decision head, scorer and action head (inference only, float32 math). */
final class DecisionModel {
    private static final class Linear {
        final Gemm.Weights w;
        final float[] b;
        final int in, out;

        Linear(SafeTensors st, String name, boolean bias, Gemm g) {
            SafeTensors.Info i = st.info(name + ".weight");
            out = i.shape[0];
            in = i.shape[1];
            w = g.prepare(st.readFloat(name + ".weight"), out, in);
            b = bias ? st.readFloat(name + ".bias") : null;
        }

        float[] apply(Gemm g, float[] x, int n) {
            float[] y = new float[n * out];
            g.linear(x, n, w, b, y);
            return y;
        }
    }

    private static final class EncLayer {
        float[] attnNorm; // null for layer 0 (identity)
        Linear wqkv, wo, wi, mlpWo;
        float[] mlpNorm;
        boolean global;
    }

    private static final class HeadLayer {
        Linear inProj, outProj, linear1, linear2;
        float[] n1w, n1b, n2w, n2b;
    }

    final EncoderConfig cfg;
    private final Gemm gemm;
    private final int dim;
    private final short[] tokEmb; // FP16 bits, row = vocab id
    private final float[] embNorm, finalNorm;
    private final EncLayer[] layers;
    private final HeadLayer[] head;
    private final float[] typeEmb;
    private final float[] scNormW, scNormB;
    private final Linear sc1, sc2, act1, act2;

    DecisionModel(EncoderConfig cfg, int headLayers, SafeTensors st, Gemm gemm) {
        this.cfg = cfg;
        this.gemm = gemm;
        this.dim = cfg.hidden;
        tokEmb = st.info("encoder.embeddings.tok_embeddings.weight").dtype.equals("F16")
                ? st.readHalf("encoder.embeddings.tok_embeddings.weight") : null;
        if (tokEmb == null) throw new IllegalArgumentException("Only FP16 token embeddings are supported for now");
        embNorm = st.readFloat("encoder.embeddings.norm.weight");
        finalNorm = st.readFloat("encoder.final_norm.weight");
        layers = new EncLayer[cfg.layers];
        for (int i = 0; i < cfg.layers; i++) {
            String p = "encoder.layers." + i + ".";
            EncLayer l = new EncLayer();
            l.global = cfg.layerTypes.get(i).equals("full_attention");
            if (i > 0) l.attnNorm = st.readFloat(p + "attn_norm.weight");
            l.wqkv = new Linear(st, p + "attn.Wqkv", false, gemm);
            l.wo = new Linear(st, p + "attn.Wo", false, gemm);
            l.mlpNorm = st.readFloat(p + "mlp_norm.weight");
            l.wi = new Linear(st, p + "mlp.Wi", false, gemm);
            l.mlpWo = new Linear(st, p + "mlp.Wo", false, gemm);
            layers[i] = l;
        }
        head = new HeadLayer[headLayers];
        for (int i = 0; i < headLayers; i++) {
            String p = "head.layers." + i + ".";
            HeadLayer h = new HeadLayer();
            h.inProj = new Linear(st, p + "self_attn.in_proj", true, gemm);
            h.outProj = new Linear(st, p + "self_attn.out_proj", true, gemm);
            h.linear1 = new Linear(st, p + "linear1", true, gemm);
            h.linear2 = new Linear(st, p + "linear2", true, gemm);
            h.n1w = st.readFloat(p + "norm1.weight");
            h.n1b = st.readFloat(p + "norm1.bias");
            h.n2w = st.readFloat(p + "norm2.weight");
            h.n2b = st.readFloat(p + "norm2.bias");
            head[i] = h;
        }
        typeEmb = st.readFloat("type_emb.weight");
        scNormW = st.readFloat("scorer.layers.0.weight");
        scNormB = st.readFloat("scorer.layers.0.bias");
        sc1 = new Linear(st, "scorer.layers.1", true, gemm);
        sc2 = new Linear(st, "scorer.layers.3", true, gemm);
        act1 = new Linear(st, "act_head.layers.0", true, gemm);
        act2 = new Linear(st, "act_head.layers.2", true, gemm);
    }

    /** Result of one forward pass. */
    static final class Output {
        final float[] logits;   // one per marker (option)
        final float[] actLogits;

        Output(float[] logits, float[] actLogits) {
            this.logits = logits;
            this.actLogits = actLogits;
        }
    }

    /** Encoder output (after final norm) for ids. Exposed for parity tests. */
    float[] encode(int[] ids) {
        int n = ids.length;
        float[] x = new float[n * dim];
        for (int t = 0; t < n; t++) {
            int row = ids[t] * dim;
            for (int i = 0; i < dim; i++) x[t * dim + i] = Half.toFloat(tokEmb[row + i]);
        }
        Ops.layerNorm(x, n, dim, embNorm, null, cfg.normEps, x);
        int heads = cfg.heads, hd = cfg.headDim();
        float[][] gRope = Ops.ropeTables(n, hd, cfg.globalBase);
        float[][] lRope = Ops.ropeTables(n, hd, cfg.localBase);
        int window = cfg.localAttention / 2;
        float[] normed = new float[n * dim];
        float[] attOut = new float[n * dim];
        for (EncLayer l : layers) {
            float[] a;
            if (l.attnNorm == null) a = x;
            else { Ops.layerNorm(x, n, dim, l.attnNorm, null, cfg.normEps, normed); a = normed; }
            float[] qkv = l.wqkv.apply(gemm, a, n);
            float[][] rope = l.global ? gRope : lRope;
            Ops.rope(qkv, n, 3 * dim, 0, heads, hd, rope[0], rope[1]);
            Ops.rope(qkv, n, 3 * dim, dim, heads, hd, rope[0], rope[1]);
            Ops.attention(qkv, n, heads, hd, l.global ? -1 : window, attOut);
            float[] proj = l.wo.apply(gemm, attOut, n);
            for (int i = 0; i < x.length; i++) x[i] += proj[i];

            Ops.layerNorm(x, n, dim, l.mlpNorm, null, cfg.normEps, normed);
            float[] wi = l.wi.apply(gemm, normed, n);
            int im = cfg.intermediate;
            float[] g = new float[n * im];
            IntStream.range(0, n).parallel().forEach(t -> {
                for (int i = 0; i < im; i++) g[t * im + i] = Ops.gelu(wi[t * 2 * im + i]) * wi[t * 2 * im + im + i];
            });
            float[] mo = l.mlpWo.apply(gemm, g, n);
            for (int i = 0; i < x.length; i++) x[i] += mo[i];
        }
        Ops.layerNorm(x, n, dim, finalNorm, null, cfg.normEps, x);
        return x;
    }

    Output forward(int[] ids, int[] markers, int qtype) {
        int n = ids.length;
        float[] h = encode(ids);
        for (int t = 0; t < n; t++) for (int i = 0; i < dim; i++) h[t * dim + i] += typeEmb[qtype * dim + i];
        int heads = Math.max(1, dim / 64), hd = dim / heads;
        float[] normed = new float[n * dim], attOut = new float[n * dim];
        for (HeadLayer l : head) {
            Ops.layerNorm(h, n, dim, l.n1w, l.n1b, 1e-5f, normed);
            float[] qkv = l.inProj.apply(gemm, normed, n);
            Ops.attention(qkv, n, heads, hd, -1, attOut);
            float[] proj = l.outProj.apply(gemm, attOut, n);
            for (int i = 0; i < h.length; i++) h[i] += proj[i];
            Ops.layerNorm(h, n, dim, l.n2w, l.n2b, 1e-5f, normed);
            float[] f = l.linear1.apply(gemm, normed, n);
            for (int i = 0; i < f.length; i++) if (f[i] < 0) f[i] = 0; // ReLU (PyTorch TransformerEncoderLayer default)
            float[] f2 = l.linear2.apply(gemm, f, n);
            for (int i = 0; i < h.length; i++) h[i] += f2[i];
        }
        int k = markers.length;
        float[] m = new float[k * dim];
        for (int j = 0; j < k; j++) System.arraycopy(h, markers[j] * dim, m, j * dim, dim);
        float[] mn = new float[k * dim];
        Ops.layerNorm(m, k, dim, scNormW, scNormB, 1e-5f, mn);
        float[] s1 = sc1.apply(gemm, mn, k);
        for (int i = 0; i < s1.length; i++) s1[i] = Ops.gelu(s1[i]);
        float[] logits = sc2.apply(gemm, s1, k); // [k, 1]

        // action head features over (at least two) marker slots; padded slots are masked to -1e4
        int slots = Math.max(2, k);
        float[] z = new float[slots];
        for (int j = 0; j < slots; j++) z[j] = j < k ? logits[j] : -1e4f;
        float[] p = z.clone();
        Ops.softmaxInPlace(p, slots);
        float entropy = 0;
        for (float v : p) entropy -= v * (float) Math.log(Math.max(v, 1e-9f));
        entropy /= (float) Math.log(slots == k ? k : Math.max(k, 2));
        float top1 = -1, top2 = -1;
        for (float v : p) {
            if (v > top1) { top2 = top1; top1 = v; } else if (v > top2) top2 = v;
        }
        float[] pooled = new float[dim + 4];
        System.arraycopy(h, 0, pooled, 0, dim);
        pooled[dim] = top1;
        pooled[dim + 1] = top1 - top2;
        pooled[dim + 2] = entropy;
        pooled[dim + 3] = Math.max(k, 2) / 255.0f;
        float[] a1 = act1.apply(gemm, pooled, 1);
        for (int i = 0; i < a1.length; i++) a1[i] = Ops.gelu(a1[i]);
        float[] act = act2.apply(gemm, a1, 1);
        return new Output(logits, act);
    }

    /** Map upstream PyTorch parameter names to the layout above (no-op for MLX exports). */
    static String sanitize(String name) {
        name = name.replace(".in_proj_weight", ".in_proj.weight").replace(".in_proj_bias", ".in_proj.bias");
        for (String prefix : new String[] {"scorer", "act_head"})
            if (name.startsWith(prefix + ".") && !name.startsWith(prefix + ".layers."))
                name = prefix + ".layers." + name.substring(prefix.length() + 1);
        return name;
    }
}
