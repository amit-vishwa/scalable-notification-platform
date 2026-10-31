package io.github.amitvishwa.notification.application.preference;

import io.github.amitvishwa.notification.application.exception.PreferenceException;
import io.github.amitvishwa.notification.domain.preference.EmailPreference;
import io.github.amitvishwa.notification.domain.preference.EmailPreferenceSettings;
import io.github.amitvishwa.notification.domain.preference.PreferenceKey;
import io.github.amitvishwa.notification.infrastructure.persistence.preference.EmailPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreferenceManagementServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-10-31T10:00:00Z");

    private static final Instant CREATED_AT = NOW.minusSeconds(60);

    private static final PreferenceKey KEY = new PreferenceKey(
            "employee-service",
            "Recipient@example.invalid"
    );

    private static final EmailPreferenceSettings ENABLED =
            new EmailPreferenceSettings(true, "en-IN", null);

    private static final EmailPreferenceSettings DISABLED =
            new EmailPreferenceSettings(false, "hi-IN", null);

    @Mock
    private EmailPreferenceRepository preferences;

    @Mock
    private PreferenceCreationService creationService;

    private PreferenceManagementService service;

    @BeforeEach
    void setUp() {
        service = new PreferenceManagementService(
                preferences,
                creationService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void createsWhenBusinessKeyDoesNotExist() {
        PreferenceResult created =
                PreferenceResult.from(persistedPreference(0));

        when(preferences.findByKey(
                KEY.sourceApplication(),
                KEY.recipientEmail()
        )).thenReturn(Optional.empty());

        when(creationService.create(KEY, ENABLED))
                .thenReturn(created);

        assertThat(service.create(KEY, ENABLED)).isSameAs(created);

        verify(creationService).create(KEY, ENABLED);
    }

    @Test
    void rejectsExistingBusinessKeyWithoutOverwritingSettings() {
        EmailPreference existing = persistedPreference(0);

        when(preferences.findByKey(
                KEY.sourceApplication(),
                KEY.recipientEmail()
        )).thenReturn(Optional.of(existing));

        PreferenceException failure = assertThrows(
                PreferenceException.class,
                () -> service.create(KEY, DISABLED)
        );

        assertThat(failure.getReason())
                .isEqualTo(PreferenceException.Reason.ALREADY_EXISTS);
        assertThat(existing.getSettings()).isEqualTo(ENABLED);

        verifyNoInteractions(creationService);
        verify(preferences, never()).flush();
    }

    @Test
    void classifiesConcurrentInsertionAsAlreadyExisting() {
        EmailPreference winner = persistedPreference(0);

        when(preferences.findByKey(
                KEY.sourceApplication(),
                KEY.recipientEmail()
        )).thenReturn(
                Optional.empty(),
                Optional.of(winner)
        );

        when(creationService.create(KEY, DISABLED))
                .thenThrow(new DataIntegrityViolationException(
                        "Simulated concurrent insertion"
                ));

        PreferenceException failure = assertThrows(
                PreferenceException.class,
                () -> service.create(KEY, DISABLED)
        );

        assertThat(failure.getReason())
                .isEqualTo(PreferenceException.Reason.ALREADY_EXISTS);
        assertThat(winner.getSettings()).isEqualTo(ENABLED);
        assertThat(winner.getVersion()).isZero();

        verify(preferences, times(2)).findByKey(
                KEY.sourceApplication(),
                KEY.recipientEmail()
        );
    }

    @Test
    void preservesUnrelatedIntegrityFailureWhenNoWinnerExists() {
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException(
                        "Simulated unrelated integrity failure"
                );

        when(preferences.findByKey(
                KEY.sourceApplication(),
                KEY.recipientEmail()
        )).thenReturn(Optional.empty());

        when(creationService.create(KEY, ENABLED))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.create(KEY, ENABLED))
                .isSameAs(failure);

        verify(preferences, times(2)).findByKey(
                KEY.sourceApplication(),
                KEY.recipientEmail()
        );
    }

    @Test
    void findsPreferenceUsingOwnerScopedId() {
        EmailPreference existing = persistedPreference(3);
        givenOwned(existing);

        PreferenceResult result = service.findById(
                existing.getPreferenceId(),
                KEY.sourceApplication()
        );

        assertThat(result.preferenceId())
                .isEqualTo(existing.getPreferenceId());
        assertThat(result.sourceApplication())
                .isEqualTo(KEY.sourceApplication());
        assertThat(result.recipientEmail())
                .isEqualTo(KEY.recipientEmail());
        assertThat(result.settings()).isEqualTo(ENABLED);
        assertThat(result.version()).isEqualTo(3L);

        verify(preferences, never()).findById(any(UUID.class));
    }

    @Test
    void returnsNotFoundForAnUnownedIdWithoutUnscopedFallback() {
        UUID id = UUID.randomUUID();

        when(preferences.findOwnedById(id, "billing-service"))
                .thenReturn(Optional.empty());

        PreferenceException failure = assertThrows(
                PreferenceException.class,
                () -> service.findById(id, "billing-service")
        );

        assertThat(failure.getReason())
                .isEqualTo(PreferenceException.Reason.NOT_FOUND);

        verify(preferences, never()).findById(any(UUID.class));
    }

    @Test
    void lookupUsesExactApplicationAndRecipient() {
        EmailPreference existing = persistedPreference(0);

        when(preferences.findByKey(
                KEY.sourceApplication(),
                KEY.recipientEmail()
        )).thenReturn(Optional.of(existing));

        PreferenceResult result = service.lookup(KEY);

        assertThat(result.preferenceId())
                .isEqualTo(existing.getPreferenceId());

        verify(preferences).findByKey(
                "employee-service",
                "Recipient@example.invalid"
        );
    }

    @Test
    void lookupReturnsNotFoundWhenBusinessKeyIsMissing() {
        when(preferences.findByKey(
                KEY.sourceApplication(),
                KEY.recipientEmail()
        )).thenReturn(Optional.empty());

        PreferenceException failure = assertThrows(
                PreferenceException.class,
                () -> service.lookup(KEY)
        );

        assertThat(failure.getReason())
                .isEqualTo(PreferenceException.Reason.NOT_FOUND);
    }

    @Test
    void updatesSettingsAndMapsTheVersionAfterFlush() {
        EmailPreference existing = persistedPreference(0);
        givenOwned(existing);

        doAnswer(invocation -> {
            // Unit fixture: simulate Hibernate's version increment on flush.
            ReflectionTestUtils.setField(existing, "version", 1L);
            return null;
        }).when(preferences).flush();

        PreferenceResult result = service.update(
                existing.getPreferenceId(),
                KEY.sourceApplication(),
                0,
                DISABLED
        );

        assertThat(result.settings()).isEqualTo(DISABLED);
        assertThat(result.version()).isEqualTo(1L);

        assertThat(existing.getPreferenceKey()).isEqualTo(KEY);
        assertThat(result.preferenceId())
                .isEqualTo(existing.getPreferenceId());
        assertThat(result.recipientEmail())
                .isEqualTo(KEY.recipientEmail());

        assertThat(result.createdByProcess()).isEqualTo("seed-process");
        assertThat(result.createdAtTime()).isEqualTo(CREATED_AT);
        assertThat(result.updatedByProcess())
                .isEqualTo("email-preference-api-update");
        assertThat(result.updatedAtTime()).isEqualTo(NOW);

        InOrder order = inOrder(preferences);

        order.verify(preferences).findOwnedById(
                existing.getPreferenceId(),
                KEY.sourceApplication()
        );
        order.verify(preferences).flush();
        order.verifyNoMoreInteractions();
    }

    @Test
    void rejectsStaleExpectedVersionWithoutMutatingSettings() {
        EmailPreference existing = persistedPreference(2);
        givenOwned(existing);

        PreferenceException failure = assertThrows(
                PreferenceException.class,
                () -> service.update(
                        existing.getPreferenceId(),
                        KEY.sourceApplication(),
                        1,
                        DISABLED
                )
        );

        assertThat(failure.getReason())
                .isEqualTo(PreferenceException.Reason.VERSION_CONFLICT);
        assertThat(existing.getSettings()).isEqualTo(ENABLED);
        assertThat(existing.getVersion()).isEqualTo(2L);
        assertThat(existing.getUpdatedAtTime()).isEqualTo(CREATED_AT);

        verify(preferences, never()).flush();
    }

    @Test
    void checksOwnershipBeforeComparingStoredVersion() {
        UUID id = UUID.randomUUID();

        when(preferences.findOwnedById(id, "billing-service"))
                .thenReturn(Optional.empty());

        PreferenceException failure = assertThrows(
                PreferenceException.class,
                () -> service.update(
                        id,
                        "billing-service",
                        999,
                        DISABLED
                )
        );

        assertThat(failure.getReason())
                .isEqualTo(PreferenceException.Reason.NOT_FOUND);

        verify(preferences, never()).findById(any(UUID.class));
        verify(preferences, never()).flush();
    }

    @Test
    void rejectsNegativeExpectedVersionWithoutMutatingSettings() {
        EmailPreference existing = persistedPreference(0);
        givenOwned(existing);

        assertThatThrownBy(() -> service.update(
                existing.getPreferenceId(),
                KEY.sourceApplication(),
                -1,
                DISABLED
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(existing.getSettings()).isEqualTo(ENABLED);
        verify(preferences, never()).flush();
    }

    @Test
    void propagatesOptimisticLockFailureForApiConflictHandling() {
        EmailPreference existing = persistedPreference(0);
        givenOwned(existing);

        OptimisticLockingFailureException failure =
                new OptimisticLockingFailureException(
                        "Simulated concurrent update"
                );

        doThrow(failure).when(preferences).flush();

        assertThatThrownBy(() -> service.update(
                existing.getPreferenceId(),
                KEY.sourceApplication(),
                0,
                DISABLED
        )).isSameAs(failure);
    }

    private void givenOwned(EmailPreference preference) {
        when(preferences.findOwnedById(
                preference.getPreferenceId(),
                KEY.sourceApplication()
        )).thenReturn(Optional.of(preference));
    }

    private static EmailPreference persistedPreference(long version) {
        EmailPreference preference = EmailPreference.create(
                UUID.randomUUID(),
                KEY,
                ENABLED,
                "seed-process",
                CREATED_AT
        );

        // Unit fixture only; production code never sets versions manually.
        ReflectionTestUtils.setField(preference, "version", version);

        return preference;
    }
}