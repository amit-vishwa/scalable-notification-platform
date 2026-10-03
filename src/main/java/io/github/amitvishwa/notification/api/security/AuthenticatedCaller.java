package io.github.amitvishwa.notification.api.security;

public record AuthenticatedCaller(
        String sourceApplication,
        String correlationId
) {

    public static final String REQUEST_ATTRIBUTE =
            "io.github.amitvishwa.notification.api.security.AuthenticatedCaller";
}