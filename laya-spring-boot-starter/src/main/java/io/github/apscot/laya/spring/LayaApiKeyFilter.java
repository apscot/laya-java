package io.github.apscot.laya.spring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/** Requires {@code Authorization: Bearer <laya.api-key>} on /v1/* when an API key is configured. */
class LayaApiKeyFilter extends OncePerRequestFilter {
    private final String apiKey;

    LayaApiKeyFilter(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().contains("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        String expected = "Bearer " + apiKey;
        if (h != null && MessageDigest.isEqual(h.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(req, res);
            return;
        }
        boolean openai = req.getRequestURI().endsWith("/v1/decisions") || req.getRequestURI().endsWith("/v1/models");
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write(openai
                ? "{\"error\": {\"message\": \"Invalid or missing API key\", \"type\": \"authentication_error\", \"param\": null, \"code\": null}}"
                : "{\"detail\": \"Invalid or missing API key\"}");
    }
}
