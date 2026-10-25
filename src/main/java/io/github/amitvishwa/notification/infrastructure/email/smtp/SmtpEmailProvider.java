package io.github.amitvishwa.notification.infrastructure.email.smtp;

import io.github.amitvishwa.notification.application.port.email.EmailMessage;
import io.github.amitvishwa.notification.application.port.email.EmailProvider;
import io.github.amitvishwa.notification.application.port.email.EmailProviderResult;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class SmtpEmailProvider implements EmailProvider {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final SmtpFailureClassifier failureClassifier =
            new SmtpFailureClassifier();

    public SmtpEmailProvider(
            JavaMailSender mailSender,
            String fromAddress
    ) {
        this.mailSender = Objects.requireNonNull(
                mailSender,
                "mailSender must not be null"
        );

        try {
            this.fromAddress = singleMailbox(fromAddress).getAddress();
        } catch (AddressException exception) {
            throw new IllegalArgumentException(
                    "SMTP sender must be one valid mailbox"
            );
        }
    }

    @Override
    public EmailProviderResult send(EmailMessage message) {
        Objects.requireNonNull(message, "message must not be null");

        MimeMessage mimeMessage;

        try {
            if (hasHeaderBreaks(message.subject())) {
                return invalidMessage();
            }

            InternetAddress recipient =
                    singleMailbox(message.recipientEmail());

            mimeMessage = mailSender.createMimeMessage();

            MimeMessageHelper helper = new MimeMessageHelper(
                    mimeMessage,
                    false,
                    StandardCharsets.UTF_8.name()
            );

            helper.setValidateAddresses(true);
            helper.setFrom(fromAddress);
            helper.setTo(recipient);
            helper.setSubject(message.subject());
            helper.setText(message.body(), false);
        } catch (MessagingException | IllegalArgumentException exception) {
            return invalidMessage();
        }

        try {
            mailSender.send(mimeMessage);

            // MIME Message-ID is not a provider-assigned queue identifier.
            return EmailProviderResult.accepted(null);
        } catch (MailException exception) {
            return failureClassifier.classify(exception, mimeMessage);
        }
    }

    private static InternetAddress singleMailbox(String value)
            throws AddressException {

        if (value == null || value.isBlank() || hasHeaderBreaks(value)) {
            throw new AddressException("Invalid SMTP mailbox");
        }

        InternetAddress[] addresses = InternetAddress.parse(value, true);

        if (addresses.length != 1
                || addresses[0].isGroup()
                || addresses[0].getPersonal() != null
                || !value.equals(addresses[0].getAddress())) {
            throw new AddressException(
                    "Exactly one plain SMTP mailbox is required"
            );
        }

        addresses[0].validate();
        return addresses[0];
    }

    private static boolean hasHeaderBreaks(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static EmailProviderResult invalidMessage() {
        return EmailProviderResult.permanentFailure(
                "SMTP_MESSAGE_INVALID"
        );
    }
}