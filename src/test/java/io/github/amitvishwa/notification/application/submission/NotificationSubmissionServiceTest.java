package io.github.amitvishwa.notification.application.submission;

import io.github.amitvishwa.notification.application.exception.IdempotencyConflictException;
import io.github.amitvishwa.notification.application.idempotency.RequestFingerprint;
import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.domain.model.NotificationKey;
import io.github.amitvishwa.notification.domain.model.NotificationStatus;
import io.github.amitvishwa.notification.infrastructure.persistence.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationSubmissionServiceTest {

    private static final NotificationKey KEY =
            new NotificationKey("employee-service", "employee-created-101");

    private static final String RECIPIENT = "recipient@example.com";
    private static final String SUBJECT = "Welcome";
    private static final String BODY = "Welcome to the application.";

    @Mock
    private NotificationRepository repository;

    @Mock
    private NotificationCreationService creationService;

    private NotificationSubmissionService service;

    @BeforeEach
    void setUp() {
        service = new NotificationSubmissionService(
                repository,
                creationService
        );
    }

    @Test
    void returnsConcurrentWinnerWhenPayloadMatches() {
        Notification winner = notification(BODY);
        simulateInsertFailure(Optional.of(winner));

        NotificationSubmissionResult result =
                service.submit(KEY, RECIPIENT, SUBJECT, BODY);

        assertThat(result.notificationId())
                .isEqualTo(winner.getNotificationId());
        assertThat(result.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(result.created()).isFalse();

        verify(repository, times(2))
                .findBySourceApplicationAndIdempotencyKey(
                        KEY.sourceApplication(),
                        KEY.idempotencyKey()
                );
    }

    @Test
    void rejectsConcurrentWinnerWhenPayloadDiffers() {
        Notification winner = notification("Different request content.");
        simulateInsertFailure(Optional.of(winner));

        assertThatThrownBy(
                () -> service.submit(KEY, RECIPIENT, SUBJECT, BODY)
        ).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void rethrowsOriginalDatabaseFailureWhenNoWinnerExists() {
        DataIntegrityViolationException failure =
                simulateInsertFailure(Optional.empty());

        assertThatThrownBy(
                () -> service.submit(KEY, RECIPIENT, SUBJECT, BODY)
        ).isSameAs(failure);
    }

    private DataIntegrityViolationException simulateInsertFailure(
            Optional<Notification> winner
    ) {
        when(repository.findBySourceApplicationAndIdempotencyKey(
                KEY.sourceApplication(),
                KEY.idempotencyKey()
        )).thenReturn(Optional.empty(), winner);

        DataIntegrityViolationException failure =
                new DataIntegrityViolationException(
                        "Simulated database constraint failure"
                );

        when(creationService.create(
                KEY,
                RequestFingerprint.calculate(RECIPIENT, SUBJECT, BODY),
                RECIPIENT,
                SUBJECT,
                BODY
        )).thenThrow(failure);

        return failure;
    }

    private Notification notification(String body) {
        return Notification.create(
                UUID.randomUUID(),
                KEY,
                RequestFingerprint.calculate(RECIPIENT, SUBJECT, body),
                RECIPIENT,
                SUBJECT,
                body,
                "unit-test",
                Instant.parse("2026-10-03T08:00:00Z")
        );
    }
}