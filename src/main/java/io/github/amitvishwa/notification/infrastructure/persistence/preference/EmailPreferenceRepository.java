package io.github.amitvishwa.notification.infrastructure.persistence.preference;

import io.github.amitvishwa.notification.domain.preference.EmailPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EmailPreferenceRepository
        extends JpaRepository<EmailPreference, UUID> {

    @Query("""
            SELECT preference
            FROM EmailPreference preference
            WHERE preference.sourceApplication = :sourceApplication
              AND preference.recipientEmail = :recipientEmail
            """)
    Optional<EmailPreference> findByKey(
            @Param("sourceApplication") String sourceApplication,
            @Param("recipientEmail") String recipientEmail
    );

    @Query("""
            SELECT preference
            FROM EmailPreference preference
            WHERE preference.preferenceId = :preferenceId
              AND preference.sourceApplication = :sourceApplication
            """)
    Optional<EmailPreference> findOwnedById(
            @Param("preferenceId") UUID preferenceId,
            @Param("sourceApplication") String sourceApplication
    );
}