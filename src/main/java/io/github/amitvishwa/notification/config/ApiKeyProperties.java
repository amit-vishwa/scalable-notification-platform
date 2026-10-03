package io.github.amitvishwa.notification.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashSet;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "notification.security")
public record ApiKeyProperties(
        @NotEmpty
        Map<
                @NotBlank @Size(max = 100) String,
                @NotBlank @Size(min = 32, max = 256) String
        > apiKeys
) {

    public ApiKeyProperties {
        apiKeys = apiKeys == null ? Map.of() : Map.copyOf(apiKeys);

        if (new HashSet<>(apiKeys.values()).size() != apiKeys.size()) {
            throw new IllegalArgumentException(
                    "Each source application must have a distinct API key"
            );
        }
    }

    @Override
    public String toString() {
        return "ApiKeyProperties[credentials=REDACTED]";
    }
}