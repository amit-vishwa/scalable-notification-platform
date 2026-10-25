package io.github.amitvishwa.notification.infrastructure.email.smtp;

import io.github.amitvishwa.notification.application.port.email.EmailMessage;
import io.github.amitvishwa.notification.application.port.email.EmailProviderResult;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SmtpEmailProviderTest {

    private static final String FROM = "notifications@example.invalid";
    private static final String TO = "recipient@example.invalid";
    private static final String SUBJECT = "Welcome — नमस्ते";
    private static final String BODY = "Hello, 世界.\nPlain text only.";
    private static final String PRIVATE_MARKER = "PRIVATE_SMTP_DETAIL";

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final SmtpEmailProvider provider =
            new SmtpEmailProvider(sender, FROM);

    @Test
    void sendsOneUtf8PlaintextMessageWithoutInventingProviderId()
            throws Exception {
        MimeMessage mime = stubMessage();

        EmailProviderResult result = provider.send(message(TO, SUBJECT));

        assertThat(result).isEqualTo(EmailProviderResult.accepted(null));
        verify(sender).send(mime);

        // Finalize MIME headers without opening an SMTP connection.
        mime.saveChanges();

        assertThat(mime.getFrom())
                .containsExactly(new InternetAddress(FROM));
        assertThat(mime.getRecipients(Message.RecipientType.TO))
                .containsExactly(new InternetAddress(TO));
        assertThat(mime.getAllRecipients()).hasSize(1);
        assertThat(mime.getSubject()).isEqualTo(SUBJECT);
        assertThat(mime.isMimeType("text/plain")).isTrue();
        assertThat(new ContentType(mime.getContentType())
                .getParameter("charset"))
                .isEqualToIgnoringCase("UTF-8");
        assertThat(mime.getContent()).isEqualTo(BODY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-an-email",
            "first@example.invalid,second@example.invalid",
            "Friends:recipient@example.invalid;",
            "Person <recipient@example.invalid>",
            "recipient@example.invalid\r\nBcc:other@example.invalid"
    })
    void rejectsInvalidOrMultipleRecipientsBeforeUsingSender(
            String recipient
    ) {
        EmailProviderResult result =
                provider.send(message(recipient, SUBJECT));

        assertThat(result).isEqualTo(
                EmailProviderResult.permanentFailure("SMTP_MESSAGE_INVALID")
        );
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Subject\r\nBcc:other@example.invalid",
            "Subject\nInjected: value",
            "Subject\rInjected: value"
    })
    void rejectsSubjectHeaderInjectionBeforeUsingSender(String subject) {
        EmailProviderResult result = provider.send(message(TO, subject));

        assertThat(result).isEqualTo(
                EmailProviderResult.permanentFailure("SMTP_MESSAGE_INVALID")
        );
        verifyNoInteractions(sender);
    }

    @Test
    void rejectsInvalidSenderWithoutExposingItsValueOrCause() {
        assertThatThrownBy(() -> new SmtpEmailProvider(
                sender,
                PRIVATE_MARKER + "\r\nBcc:other@example.invalid"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SMTP sender must be one valid mailbox")
                .hasNoCause();

        verifyNoInteractions(sender);
    }

    @Test
    void returnsSafeAuthenticationFailure() {
        MimeMessage mime = stubMessage();

        doThrow(new MailAuthenticationException(PRIVATE_MARKER))
                .when(sender).send(mime);

        EmailProviderResult result = provider.send(message(TO, SUBJECT));

        assertThat(result).isEqualTo(
                EmailProviderResult.permanentFailure(
                        "SMTP_AUTHENTICATION_FAILED"
                )
        );
        assertThat(result.toString()).doesNotContain(PRIVATE_MARKER);
        verify(sender).send(mime);
    }

    @Test
    void keepsAmbiguousTimeoutUnknownWithoutExposingDetails() {
        MimeMessage mime = stubMessage();

        MailSendException failure = new MailSendException(
                Map.of(mime, new SocketTimeoutException(PRIVATE_MARKER))
        );
        doThrow(failure).when(sender).send(mime);

        EmailProviderResult result = provider.send(message(TO, SUBJECT));

        assertThat(result).isEqualTo(
                EmailProviderResult.unknown(
                        "SMTP_DELIVERY_OUTCOME_UNKNOWN"
                )
        );
        assertThat(result.toString()).doesNotContain(PRIVATE_MARKER);
        verify(sender).send(mime);
    }

    private MimeMessage stubMessage() {
        MimeMessage mime = new MimeMessage(
                Session.getInstance(new Properties())
        );
        when(sender.createMimeMessage()).thenReturn(mime);
        return mime;
    }

    private static EmailMessage message(String recipient, String subject) {
        return new EmailMessage(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                recipient,
                subject,
                BODY
        );
    }
}