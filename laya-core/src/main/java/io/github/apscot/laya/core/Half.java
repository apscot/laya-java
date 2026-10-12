package io.github.apscot.laya.core;

/** IEEE binary16 conversion (Float.float16ToFloat only exists from Java 20). */
final class Half {
    private Half() {}

    static float toFloat(short h) {
        int b = h & 0xFFFF;
        int sign = (b & 0x8000) << 16;
        int exp = (b >>> 10) & 0x1F;
        int man = b & 0x3FF;
        if (exp == 0) {
            if (man == 0) return Float.intBitsToFloat(sign);
            // subnormal: normalise
            int e = 127 - 15 + 1;
            while ((man & 0x400) == 0) { man <<= 1; e--; }
            man &= 0x3FF;
            return Float.intBitsToFloat(sign | (e << 23) | (man << 13));
        }
        if (exp == 0x1F) return Float.intBitsToFloat(sign | 0x7F800000 | (man << 13));
        return Float.intBitsToFloat(sign | ((exp + 127 - 15) << 23) | (man << 13));
    }
}
