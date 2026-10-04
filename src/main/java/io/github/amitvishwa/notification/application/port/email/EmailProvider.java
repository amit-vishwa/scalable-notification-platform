package io.github.amitvishwa.notification.application.port.email;

public interface EmailProvider {

    EmailProviderResult send(EmailMessage message);
}