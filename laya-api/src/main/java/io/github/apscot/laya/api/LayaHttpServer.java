package io.github.apscot.laya.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.apscot.laya.core.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Dependency-free HTTP server (JDK {@code com.sun.net.httpserver}) exposing {@code /v1/systemone},
 * {@code /v1/decisions}, {@code /v1/models} and {@code /health}. Also the engine behind the Gradle plugin.
 * Environment (same names as upstream laya-serve): LAYA_PORT, LAYA_API_KEY, LAYA_MODELS, LAYA_PRELOAD.
 */
public final class LayaHttpServer implements AutoCloseable {
    public static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private final HttpServer server;
    private final ModelRegistry registry;
    private final String apiKey;
    private final SystemOneApi systemOne;
    private final DecisionsApi decisions;

    public LayaHttpServer(ModelRegistry registry, String host, int port, String apiKey) throws IOException {
        this.registry = registry;
        this.apiKey = apiKey == null || apiKey.isEmpty() ? null : apiKey;
        this.systemOne = new SystemOneApi(registry);
        this.decisions = new DecisionsApi(registry);
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.createContext("/", this::route);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void start() {
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
        registry.close();
    }

    private void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        boolean openai = path.equals("/v1/decisions") || path.equals("/v1/models");
        LayaApiException.Style style = openai ? LayaApiException.Style.OPENAI : LayaApiException.Style.SYSTEM_ONE;
        try {
            if (path.equals("/health") && method.equals("GET")) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("status", "ok");
                h.put("models", registry.sources().keySet().stream().filter(registry::isLoaded).toList());
                send(ex, 200, h);
                return;
            }
            authenticate(ex, style);
            if (path.equals("/v1/models") && method.equals("GET")) {
                List<Object> data = new java.util.ArrayList<>();
                for (String n : registry.sources().keySet()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", n);
                    m.put("object", "model");
                    m.put("owned_by", "laya");
                    data.add(m);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("object", "list");
                out.put("data", data);
                send(ex, 200, out);
            } else if (path.equals("/v1/systemone") && method.equals("POST")) {
                send(ex, 200, systemOne.handle(body(ex, style)));
            } else if (path.equals("/v1/decisions") && method.equals("POST")) {
                send(ex, 200, decisions.handle(body(ex, style)));
            } else {
                throw new LayaApiException(path.startsWith("/v1/") && !method.equals("POST") && !method.equals("GET") ? 405 : 404, style, "Not Found");
            }
        } catch (LayaApiException e) {
            send(ex, e.status(), e.body());
        } catch (Throwable t) {
            send(ex, 500, new LayaApiException(500, style, "internal error: " + t.getMessage()).body());
        } finally {
            ex.close();
        }
    }

    private void authenticate(HttpExchange ex, LayaApiException.Style style) {
        if (apiKey == null) return;
        String h = ex.getRequestHeaders().getFirst("Authorization");
        String expected = "Bearer " + apiKey;
        if (h == null || !MessageDigest.isEqual(h.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8)))
            throw new LayaApiException(401, style, "Invalid or missing API key");
    }

    private static Map<String, Object> body(HttpExchange ex, LayaApiException.Style style) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                bos.write(buf, 0, r);
                if (bos.size() > MAX_BODY_BYTES) throw new LayaApiException(413, style, "request body too large (max 2 MB)");
            }
        }
        try {
            Object o = Json.parse(bos.toString(StandardCharsets.UTF_8));
            if (!(o instanceof Map)) throw new LayaApiException(style == LayaApiException.Style.OPENAI ? 400 : 422, style, "request body must be a JSON object");
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            return m;
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new LayaApiException(style == LayaApiException.Style.OPENAI ? 400 : 422, style, "invalid JSON body");
        }
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] b = Json.dumps(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }

    /** Standalone entry point configured by environment variables. */
    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        ModelRegistry reg = ModelRegistry.defaults();
        String models = env.get("LAYA_MODELS");
        if (models != null && !models.isBlank()) {
            Map<String, String> chosen = new LinkedHashMap<>();
            for (String n : models.split(",")) {
                n = n.trim();
                String src = reg.sources().get(n);
                chosen.put(n, src != null ? src : n);
            }
            reg = new ModelRegistry(chosen, chosen.keySet().iterator().next());
        }
        int port = Integer.parseInt(env.getOrDefault("LAYA_PORT", "8000"));
        LayaHttpServer s = new LayaHttpServer(reg, env.getOrDefault("LAYA_HOST", "127.0.0.1"), port, env.get("LAYA_API_KEY"));
        if (!"0".equals(env.getOrDefault("LAYA_PRELOAD", "0"))) reg.preload();
        s.start();
        System.out.println("laya-java listening on :" + s.port());
        Runtime.getRuntime().addShutdownHook(new Thread(s::close));
    }
}
