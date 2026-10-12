package io.github.apscot.laya.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** An API-level error with an HTTP status; the body shape depends on the protocol. */
public final class LayaApiException extends RuntimeException {
    public enum Style { SYSTEM_ONE, OPENAI }

    private final int status;
    private final Style style;

    public LayaApiException(int status, Style style, String message) {
        super(message);
        this.status = status;
        this.style = style;
    }

    public int status() {
        return status;
    }

    /** {@code {"detail": "..."}} for /v1/systemone (as upstream laya-serve), OpenAI error object otherwise. */
    public Map<String, Object> body() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (style == Style.SYSTEM_ONE) {
            m.put("detail", getMessage());
        } else {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("message", getMessage());
            e.put("type", status == 401 ? "authentication_error" : status >= 500 ? "server_error" : "invalid_request_error");
            e.put("param", null);
            e.put("code", null);
            m.put("error", e);
        }
        return m;
    }
}
