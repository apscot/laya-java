package io.github.apscot.laya.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** ModernBERT encoder hyper-parameters from {@code encoder/config.json}. */
final class EncoderConfig {
    final int vocabSize, hidden, intermediate, layers, heads, localAttention;
    final float normEps;
    final List<String> layerTypes;
    final double globalBase, localBase;

    @SuppressWarnings("unchecked")
    EncoderConfig(Map<String, Object> c) {
        String type = (String) c.getOrDefault("model_type", "modernbert");
        if (!"modernbert".equals(type)) throw new IllegalArgumentException("Unsupported encoder: " + type + "; expected modernbert");
        if (!"gelu".equals(c.getOrDefault("hidden_activation", "gelu"))) throw new IllegalArgumentException("Unsupported encoder activation");
        if (Boolean.TRUE.equals(c.get("attention_bias")) || Boolean.TRUE.equals(c.get("mlp_bias")) || Boolean.TRUE.equals(c.get("norm_bias")))
            throw new IllegalArgumentException("Biased ModernBERT variants are not supported");
        vocabSize = num(c, "vocab_size", 0);
        hidden = num(c, "hidden_size", 0);
        intermediate = num(c, "intermediate_size", 0);
        layers = num(c, "num_hidden_layers", 0);
        heads = num(c, "num_attention_heads", 0);
        localAttention = num(c, "local_attention", 128);
        Object eps = c.containsKey("norm_eps") ? c.get("norm_eps") : c.getOrDefault("layer_norm_eps", 1e-5);
        normEps = ((Number) eps).floatValue();
        int every = num(c, "global_attn_every_n_layers", 3);
        List<String> lt = new ArrayList<>();
        Object given = c.get("layer_types");
        if (given instanceof List) for (Object o : (List<Object>) given) lt.add((String) o);
        else for (int i = 0; i < layers; i++) lt.add(i % every == 0 ? "full_attention" : "sliding_attention");
        if (lt.size() != layers) throw new IllegalArgumentException("Invalid ModernBERT layer_types");
        layerTypes = lt;
        double g = ((Number) c.getOrDefault("global_rope_theta", 160000.0)).doubleValue();
        double l = ((Number) c.getOrDefault("local_rope_theta", 10000.0)).doubleValue();
        Object rp = c.get("rope_parameters");
        if (rp instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) rp;
            for (String k : new String[] {"full_attention", "sliding_attention"}) {
                Object p = m.get(k);
                if (p instanceof Map) {
                    Map<String, Object> pm = (Map<String, Object>) p;
                    if (!"default".equals(pm.getOrDefault("rope_type", "default")))
                        throw new IllegalArgumentException("Only default (unscaled) ModernBERT RoPE is supported");
                    if (pm.containsKey("rope_theta")) {
                        double th = ((Number) pm.get("rope_theta")).doubleValue();
                        if (k.equals("full_attention")) g = th; else l = th;
                    }
                }
            }
        }
        globalBase = g;
        localBase = l;
        if (hidden % heads != 0 || (hidden / heads) % 2 != 0)
            throw new IllegalArgumentException("ModernBERT requires an even, integral attention head dimension");
    }

    int headDim() { return hidden / heads; }

    private static int num(Map<String, Object> c, String k, int def) {
        Object o = c.get(k);
        return o == null ? def : ((Number) o).intValue();
    }
}
