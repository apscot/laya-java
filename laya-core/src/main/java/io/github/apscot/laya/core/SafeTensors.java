package io.github.apscot.laya.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only safetensors file. Each tensor is memory-mapped on demand (keeps every map under 2 GB). */
public final class SafeTensors implements AutoCloseable {
    public static final class Info {
        public final String dtype;
        public final int[] shape;
        final long begin;
        final long end;

        Info(String dtype, int[] shape, long begin, long end) {
            this.dtype = dtype;
            this.shape = shape;
            this.begin = begin;
            this.end = end;
        }

        public long size() {
            long n = 1;
            for (int d : shape) n *= d;
            return n;
        }
    }

    private final FileChannel ch;
    private final long dataStart;
    private final Map<String, Info> tensors = new LinkedHashMap<>();

    public SafeTensors(Path file) throws IOException {
        ch = FileChannel.open(file, StandardOpenOption.READ);
        ByteBuffer len = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        ch.read(len, 0);
        long n = len.getLong(0);
        if (n <= 0 || n > 100_000_000L) throw new IOException("Invalid safetensors header length " + n);
        ByteBuffer hb = ByteBuffer.allocate((int) n);
        long read = 0;
        while (read < n) {
            int r = ch.read(hb, 8 + read);
            if (r < 0) throw new IOException("Truncated safetensors header");
            read += r;
        }
        dataStart = 8 + n;
        Map<String, Object> header = Json.parseObject(new String(hb.array(), StandardCharsets.UTF_8));
        for (Map.Entry<String, Object> e : header.entrySet()) {
            if (e.getKey().equals("__metadata__")) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) e.getValue();
            @SuppressWarnings("unchecked")
            List<Object> shape = (List<Object>) t.get("shape");
            @SuppressWarnings("unchecked")
            List<Object> off = (List<Object>) t.get("data_offsets");
            int[] sh = new int[shape.size()];
            for (int k = 0; k < sh.length; k++) sh[k] = ((Number) shape.get(k)).intValue();
            tensors.put(e.getKey(), new Info((String) t.get("dtype"), sh,
                    ((Number) off.get(0)).longValue(), ((Number) off.get(1)).longValue()));
        }
    }

    public Map<String, Info> tensors() {
        return tensors;
    }

    public boolean has(String name) {
        return tensors.containsKey(name);
    }

    public Info info(String name) {
        Info i = tensors.get(name);
        if (i == null) throw new IllegalArgumentException("Missing tensor: " + name);
        return i;
    }

    private ByteBuffer map(Info i) throws IOException {
        return ch.map(FileChannel.MapMode.READ_ONLY, dataStart + i.begin, i.end - i.begin)
                .order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Raw FP16 bits (only valid for F16 tensors). */
    public short[] readHalf(String name) {
        Info i = info(name);
        if (!i.dtype.equals("F16")) throw new IllegalArgumentException(name + " is " + i.dtype + ", not F16");
        try {
            short[] out = new short[(int) i.size()];
            map(i).asShortBuffer().get(out);
            return out;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Any supported dtype (F16, BF16, F32) converted to float32. */
    public float[] readFloat(String name) {
        Info i = info(name);
        try {
            ByteBuffer b = map(i);
            int n = (int) i.size();
            float[] out = new float[n];
            switch (i.dtype) {
                case "F32": b.asFloatBuffer().get(out); break;
                case "F16": {
                    java.nio.ShortBuffer sb = b.asShortBuffer();
                    for (int k = 0; k < n; k++) out[k] = Half.toFloat(sb.get(k));
                    break;
                }
                case "BF16": {
                    java.nio.ShortBuffer sb = b.asShortBuffer();
                    for (int k = 0; k < n; k++) out[k] = Float.intBitsToFloat((sb.get(k) & 0xFFFF) << 16);
                    break;
                }
                default: throw new IllegalArgumentException("Unsupported dtype " + i.dtype + " for " + name);
            }
            return out;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }
}
