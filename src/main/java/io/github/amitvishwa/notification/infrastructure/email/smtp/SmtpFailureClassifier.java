package io.github.amitvishwa.notification.infrastructure.email.smtp;

import io.github.amitvishwa.notification.application.port.email.EmailProviderResult;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Classifies failures from sending exactly one MIME message.
 */
final class SmtpFailureClassifier {

    private static final int MAX_CHAIN_LENGTH = 32;

    EmailProviderResult classify(
            MailException failure,
            MimeMessage attemptedMessage
    ) {
        Objects.requireNonNull(failure, "failure must not be null");
        Objects.requireNonNull(
                attemptedMessage,
                "attemptedMessage must not be null"
        );

        if (failure.getSuppressed().length > 0) {
            return unknown();
        }

        // Authentication failure requires operator/configuration correction.
        if (failure instanceof MailAuthenticationException) {
            return EmailProviderResult.permanentFailure(
                    "SMTP_AUTHENTICATION_FAILED"
            );
        }

        if (!(failure instanceof MailSendException sendFailure)
                || sendFailure.getFailedMessages().size() != 1) {
            return unknown();
        }

        var entry = sendFailure.getFailedMessages()
                .entrySet()
                .iterator()
                .next();

        if (entry.getKey() != attemptedMessage
                || entry.getValue() == null) {
            return unknown();
        }

        Exception messageFailure = entry.getValue();
        Throwable outerCause = sendFailure.getCause();

        // A distinct outer cause can represent cleanup failure after sending.
        if (outerCause != null && outerCause != messageFailure) {
            return unknown();
        }

        if (messageFailure instanceof MailConnectException) {
            if (messageFailure.getSuppressed().length > 0) {
                return unknown();
            }

            return EmailProviderResult.temporaryFailure(
                    "SMTP_CONNECTION_FAILED"
            );
        }

        return classifyChain(messageFailure);
    }

    private EmailProviderResult classifyChain(Exception failure) {
        Set<Throwable> visited =
                Collections.newSetFromMap(new IdentityHashMap<>());

        Throwable current = failure;
        int rejectionClass = 0;

        while (current != null) {
            if (visited.size() >= MAX_CHAIN_LENGTH
                    || !visited.add(current)
                    || current.getSuppressed().length > 0
                    || hasAcceptedRecipients(current)) {
                return unknown();
            }

            int replyCode = replyCode(current);

            if (replyCode != 0) {
                int currentClass = replyCode / 100;

                if (currentClass != 4 && currentClass != 5) {
                    return unknown();
                }

                if (rejectionClass != 0
                        && rejectionClass != currentClass) {
                    return unknown();
                }

                rejectionClass = currentClass;
            } else if (current.getClass() != MessagingException.class
                    && current.getClass() != SendFailedException.class) {
                // Includes generic I/O failures and timeouts during sending.
                return unknown();
            }

            Throwable cause = current.getCause();

            if (current instanceof MessagingException messagingException) {
                Exception next = messagingException.getNextException();

                if (next != null && cause != null && next != cause) {
                    return unknown();
                }

                current = next != null ? next : cause;
            } else {
                current = cause;
            }
        }

        return switch (rejectionClass) {
            case 4 -> EmailProviderResult.temporaryFailure(
                    "SMTP_TEMPORARY_REJECTION"
            );
            case 5 -> EmailProviderResult.permanentFailure(
                    "SMTP_PERMANENT_REJECTION"
            );
            default -> unknown();
        };
    }

    private static int replyCode(Throwable failure) {
        if (failure instanceof SMTPSendFailedException sendFailure) {
            return sendFailure.getReturnCode();
        }

        if (failure instanceof SMTPAddressFailedException addressFailure) {
            return addressFailure.getReturnCode();
        }

        return 0;
    }

    private static boolean hasAcceptedRecipients(Throwable failure) {
        if (!(failure instanceof SendFailedException sendFailure)) {
            return false;
        }

        Address[] accepted = sendFailure.getValidSentAddresses();
        return accepted != null && accepted.length > 0;
    }

    private static EmailProviderResult unknown() {
        return EmailProviderResult.unknown(
                "SMTP_DELIVERY_OUTCOME_UNKNOWN"
        );
    }
}