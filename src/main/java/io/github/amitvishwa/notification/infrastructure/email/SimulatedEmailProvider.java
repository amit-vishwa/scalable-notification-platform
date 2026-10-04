package io.github.amitvishwa.notification.infrastructure.email;

import io.github.amitvishwa.notification.application.port.email.EmailMessage;
import io.github.amitvishwa.notification.application.port.email.EmailProvider;
import io.github.amitvishwa.notification.application.port.email.EmailProviderResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Simulates provider acceptance without sending an email.
 */
@Component
@Profile("(local | test) & !prod & !bootstrap")
@ConditionalOnProperty(
        prefix = "notification.worker",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class SimulatedEmailProvider implements EmailProvider {

    @Override
    public EmailProviderResult send(EmailMessage message) {
        Objects.requireNonNull(message, "message must not be null");

        return EmailProviderResult.accepted(
                "simulated-" + message.notificationId()
        );
    }
}