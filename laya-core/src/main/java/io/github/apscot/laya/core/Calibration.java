package io.github.apscot.laya.core;

/** Temperature buckets and confidence measures (port of laya_mlx/common.py). */
final class Calibration {
    static final double TEMP_MIN = 0.5, TEMP_MAX = 5.0;

    private Calibration() {}

    static String bucket(int qtype, int k) {
        String size = k <= 2 ? "2" : k <= 5 ? "3-5" : k <= 10 ? "6-10" : "11+";
        return new String[] {"choice", "score", "noul"}[qtype] + ":" + size;
    }

    static double clamp(Object t) {
        if (!(t instanceof Number)) return 1.0;
        double v = ((Number) t).doubleValue();
        if (Double.isNaN(v) || Double.isInfinite(v)) return 1.0;
        return Math.min(TEMP_MAX, Math.max(TEMP_MIN, v));
    }

    /** 1 - H(p)/log(k). */
    static double entropyConfidence(double[] p, int k) {
        if (k < 2) return 1.0;
        double ent = 0;
        for (int i = 0; i < k; i++) ent -= p[i] * Math.log(Math.min(1.0, Math.max(1e-12, p[i])));
        return Math.min(1.0, Math.max(0.0, 1.0 - ent / Math.log(k)));
    }

    static double answerConfidence(double[] p, int k) {
        if (k < 1) return 1.0;
        double m = 0;
        for (int i = 0; i < k; i++) m = Math.max(m, p[i]);
        return Math.min(1.0, Math.max(0.0, m));
    }

    static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
