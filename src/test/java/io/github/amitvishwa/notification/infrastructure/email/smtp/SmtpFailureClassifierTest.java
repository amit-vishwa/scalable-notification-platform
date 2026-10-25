package io.github.amitvishwa.notification.infrastructure.email.smtp;

import io.github.amitvishwa.notification.application.port.email.EmailProviderResult;
import io.github.amitvishwa.notification.domain.model.DeliveryAttemptOutcome;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.eclipse.angus.mail.util.SocketConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SmtpFailureClassifierTest {

    private static final String PRIVATE_DETAILS =
            "private-recipient@example.invalid private-provider-secret";

    private final SmtpFailureClassifier classifier =
            new SmtpFailureClassifier();

    private final MimeMessage message = newMessage();

    @ParameterizedTest
    @CsvSource({
            "451, TEMPORARY_FAILURE, SMTP_TEMPORARY_REJECTION",
            "550, PERMANENT_FAILURE, SMTP_PERMANENT_REJECTION"
    })
    void classifiesExplicitRejections(
            int replyCode,
            DeliveryAttemptOutcome outcome,
            String failureCode
    ) {
        assertResult(envelope(rejection(replyCode)), outcome, failureCode);
    }

    @ParameterizedTest
    @CsvSource({
            "450, TEMPORARY_FAILURE, SMTP_TEMPORARY_REJECTION",
            "550, PERMANENT_FAILURE, SMTP_PERMANENT_REJECTION"
    })
    void followsRecipientRejectionInsideGenericSendFailure(
            int replyCode,
            DeliveryAttemptOutcome outcome,
            String failureCode
    ) throws Exception {
        InternetAddress recipient =
                new InternetAddress("recipient@example.invalid");

        SMTPAddressFailedException addressFailure =
                new SMTPAddressFailedException(
                        recipient,
                        "RCPT TO",
                        replyCode,
                        PRIVATE_DETAILS
                );

        SendFailedException failure = new SendFailedException(
                PRIVATE_DETAILS,
                addressFailure,
                null,
                null,
                new Address[]{recipient}
        );

        assertResult(envelope(failure), outcome, failureCode);
    }

    @Test
    void retriesKnownConnectionFailureWithMatchingOuterCause() {
        MailConnectException connection = connectionFailure();

        MailSendException failure = new MailSendException(
                PRIVATE_DETAILS,
                connection,
                Map.of(message, connection)
        );

        assertResult(
                failure,
                DeliveryAttemptOutcome.TEMPORARY_FAILURE,
                "SMTP_CONNECTION_FAILED"
        );
    }

    @Test
    void doesNotTreatNestedConnectionExceptionAsPreSendProof() {
        assertUnknown(envelope(
                new MessagingException(PRIVATE_DETAILS, connectionFailure())
        ));
    }

    @Test
    void treatsGenericTransportFailuresAsUnknown() {
        assertUnknown(envelope(new SocketTimeoutException(PRIVATE_DETAILS)));
        assertUnknown(envelope(new IOException(PRIVATE_DETAILS)));
        assertUnknown(envelope(new ConnectException(PRIVATE_DETAILS)));
    }

    @Test
    void treatsAcceptedRecipientsAsUnknownDespiteTemporaryReply()
            throws Exception {
        Address[] accepted = {
                new InternetAddress("recipient@example.invalid")
        };

        SMTPSendFailedException failure = new SMTPSendFailedException(
                "DATA",
                451,
                PRIVATE_DETAILS,
                null,
                accepted,
                null,
                null
        );

        assertUnknown(envelope(failure));
    }

    @Test
    void treatsConflictingRejectionsAsUnknown() {
        SMTPSendFailedException failure = rejection(451);
        assertThat(failure.setNextException(rejection(550))).isTrue();

        assertUnknown(envelope(failure));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 250, 354, 600})
    void treatsUnsupportedReplyCodesAsUnknown(int replyCode) {
        assertUnknown(envelope(rejection(replyCode)));
    }

    @Test
    void treatsDistinctCleanupFailureAsUnknown() {
        MailSendException failure = new MailSendException(
                PRIVATE_DETAILS,
                new MessagingException("QUIT failed"),
                Map.of(message, rejection(451))
        );

        assertUnknown(failure);
    }

    @Test
    void rejectsEmptyMismatchedAndMultipleMessageEnvelopes() {
        assertUnknown(new MailSendException(PRIVATE_DETAILS));
        assertUnknown(new MailParseException(PRIVATE_DETAILS));

        assertUnknown(new MailSendException(
                Map.of(newMessage(), rejection(451))
        ));

        assertUnknown(new MailSendException(
                Map.of(
                        message, rejection(451),
                        newMessage(), rejection(451)
                )
        ));
    }

    @Test
    void treatsSuppressedFailuresAsUnknown() {
        MailSendException outer = envelope(rejection(451));
        outer.addSuppressed(new IOException(PRIVATE_DETAILS));
        assertUnknown(outer);

        SMTPSendFailedException inner = rejection(451);
        inner.addSuppressed(new IOException(PRIVATE_DETAILS));
        assertUnknown(envelope(inner));

        MailConnectException connection = connectionFailure();
        connection.addSuppressed(new IOException(PRIVATE_DETAILS));
        assertUnknown(envelope(connection));
    }

    @Test
    void rejectsCyclicChainsWithoutHanging() {
        SMTPSendFailedException cyclic = rejection(451);
        assertThat(cyclic.setNextException(cyclic)).isTrue();

        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> assertUnknown(envelope(cyclic))
        );
    }

    @ParameterizedTest
    @CsvSource({
            "31, TEMPORARY_FAILURE, SMTP_TEMPORARY_REJECTION",
            "32, UNKNOWN, SMTP_DELIVERY_OUTCOME_UNKNOWN"
    })
    void boundsExceptionTraversal(
            int wrapperCount,
            DeliveryAttemptOutcome outcome,
            String failureCode
    ) {
        Exception failure = rejection(451);

        for (int index = 0; index < wrapperCount; index++) {
            failure = new MessagingException(PRIVATE_DETAILS, failure);
        }

        assertResult(envelope(failure), outcome, failureCode);
    }

    @Test
    void rejectsOpaqueLeavesEvenAfterExplicitRejection() {
        SMTPSendFailedException failure = rejection(451);

        assertThat(failure.setNextException(
                new IOException(PRIVATE_DETAILS)
        )).isTrue();

        assertUnknown(envelope(failure));

        assertUnknown(envelope(new MessagingException(
                PRIVATE_DETAILS,
                new IllegalStateException(PRIVATE_DETAILS)
        )));
    }

    @Test
    void treatsAuthenticationFailureAsPermanentOperatorCorrection() {
        assertResult(
                new MailAuthenticationException(PRIVATE_DETAILS),
                DeliveryAttemptOutcome.PERMANENT_FAILURE,
                "SMTP_AUTHENTICATION_FAILED"
        );
    }

    @Test
    void returnsSafeCodesWithoutPrivateProviderDetails() {
        EmailProviderResult result =
                classifier.classify(envelope(rejection(451)), message);

        assertThat(result.failureCode())
                .isEqualTo("SMTP_TEMPORARY_REJECTION");
        assertThat(result.providerMessageId()).isNull();
        assertThat(result.toString()).doesNotContain(
                "private-recipient@example.invalid",
                "private-provider-secret"
        );
    }

    private void assertUnknown(MailException failure) {
        assertResult(
                failure,
                DeliveryAttemptOutcome.UNKNOWN,
                "SMTP_DELIVERY_OUTCOME_UNKNOWN"
        );
    }

    private void assertResult(
            MailException failure,
            DeliveryAttemptOutcome outcome,
            String failureCode
    ) {
        EmailProviderResult result = classifier.classify(failure, message);

        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.failureCode()).isEqualTo(failureCode);
        assertThat(result.providerMessageId()).isNull();
    }

    private MailSendException envelope(Exception failure) {
        return new MailSendException(Map.of(message, failure));
    }

    private static SMTPSendFailedException rejection(int replyCode) {
        return new SMTPSendFailedException(
                "DATA", replyCode, PRIVATE_DETAILS, null, null, null, null
        );
    }

    private static MailConnectException connectionFailure() {
        return new MailConnectException(new SocketConnectException(
                PRIVATE_DETAILS,
                new ConnectException(PRIVATE_DETAILS),
                "127.0.0.1",
                1025,
                5000
        ));
    }

    private static MimeMessage newMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }
}