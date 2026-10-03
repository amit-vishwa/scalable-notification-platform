package io.github.amitvishwa.notification.api.security;

import io.github.amitvishwa.notification.config.ApiKeyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final List<Credential> credentials;

    public ApiKeyAuthenticationFilter(ApiKeyProperties properties) {
        this.credentials = properties.apiKeys()
                .entrySet()
                .stream()
                .map(entry -> new Credential(
                        entry.getKey(),
                        digest(entry.getValue())
                ))
                .toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();

        return !path.equals("/api/v1/notifications")
                && !path.startsWith("/api/v1/notifications/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String correlationId = UUID.randomUUID().toString();

        response.setHeader(CORRELATION_HEADER, correlationId);

        String apiKey = readSingleApiKey(request);
        String sourceApplication = authenticate(apiKey);

        if (sourceApplication == null) {
            writeUnauthorized(response, correlationId);
            return;
        }

        request.setAttribute(
                AuthenticatedCaller.REQUEST_ATTRIBUTE,
                new AuthenticatedCaller(
                        sourceApplication,
                        correlationId
                )
        );

        filterChain.doFilter(request, response);
    }

    private String readSingleApiKey(HttpServletRequest request) {
        Enumeration<String> headers =
                request.getHeaders(API_KEY_HEADER);

        if (headers == null || !headers.hasMoreElements()) {
            return null;
        }

        String value = headers.nextElement();

        if (headers.hasMoreElements()
                || value == null
                || value.isBlank()
                || value.length() > 256) {
            return null;
        }

        return value;
    }

    private String authenticate(String apiKey) {
        if (apiKey == null) {
            return null;
        }

        byte[] candidate = digest(apiKey);
        String matchedApplication = null;

        for (Credential credential : credentials) {
            if (MessageDigest.isEqual(
                    credential.keyDigest(),
                    candidate
            )) {
                matchedApplication = credential.sourceApplication();
            }
        }

        return matchedApplication;
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Required SHA-256 algorithm is unavailable",
                    exception
            );
        }
    }

    private void writeUnauthorized(
            HttpServletResponse response,
            String correlationId
    ) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");

        response.getWriter().write("""
                {
                  "type": "about:blank",
                  "title": "Unauthorized",
                  "status": 401,
                  "detail": "A valid API key is required.",
                  "errorCode": "INVALID_API_KEY",
                  "correlationId": "%s"
                }
                """.formatted(correlationId));
    }

    private record Credential(
            String sourceApplication,
            byte[] keyDigest
    ) {
    }
}