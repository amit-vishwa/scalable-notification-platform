package io.github.amitvishwa.notification.config.email;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "notification.email.smtp")
public record SmtpProperties(
        @NotBlank
        String host,

        @DefaultValue("587")
        @Min(1)
        @Max(65535)
        int port,

        @NotBlank
        @Email
        String fromAddress,

        @DefaultValue("STARTTLS")
        @NotNull
        SmtpSecurityMode security,

        @DefaultValue("false")
        boolean authentication,

        String username,
        String password,

        @DefaultValue("PT5S")
        @NotNull
        @DurationMin(seconds = 1)
        @DurationMax(seconds = 30)
        Duration connectionTimeout,

        @DefaultValue("PT5S")
        @NotNull
        @DurationMin(seconds = 1)
        @DurationMax(seconds = 30)
        Duration readTimeout,

        @DefaultValue("PT5S")
        @NotNull
        @DurationMin(seconds = 1)
        @DurationMax(seconds = 30)
        Duration writeTimeout
) {

    @AssertTrue(
            message = "SMTP authentication requires username and password; "
                    + "credentials must be absent when authentication is disabled"
    )
    public boolean isCredentialsConsistent() {
        if (authentication) {
            return hasText(username) && hasText(password);
        }

        return !hasText(username) && !hasText(password);
    }

    @AssertTrue(
            message = "SMTP authentication requires encrypted transport"
    )
    public boolean isAuthenticationEncrypted() {
        return !authentication || security != SmtpSecurityMode.PLAIN;
    }

    @AssertTrue(
            message = "Plaintext SMTP requires an explicit loopback host"
    )
    public boolean isPlaintextHostLocal() {
        return security != SmtpSecurityMode.PLAIN
                || "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public String toString() {
        return "SmtpProperties[security=" + security
                + ", authentication=" + authentication
                + ", credentials=<redacted>]";
    }
}