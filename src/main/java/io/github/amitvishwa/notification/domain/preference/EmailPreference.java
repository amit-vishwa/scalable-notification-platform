package io.github.amitvishwa.notification.domain.preference;

import io.github.amitvishwa.notification.domain.model.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "email_preference",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_email_preference_owner_recipient",
                columnNames = {
                        "source_application",
                        "recipient_email"
                }
        )
)
public class EmailPreference extends AuditableEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(
            name = "preference_id",
            nullable = false,
            updatable = false,
            columnDefinition = "BINARY(16)"
    )
    private UUID preferenceId;

    @Column(
            name = "source_application",
            nullable = false,
            updatable = false,
            length = 100
    )
    private String sourceApplication;

    @Column(
            name = "recipient_email",
            nullable = false,
            updatable = false,
            length = 254
    )
    private String recipientEmail;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled;

    @Column(name = "locale_tag", nullable = false, length = 63)
    private String localeTag;

    @Column(name = "quiet_start", columnDefinition = "TIME(6)")
    private LocalTime quietStart;

    @Column(name = "quiet_end", columnDefinition = "TIME(6)")
    private LocalTime quietEnd;

    @Column(name = "time_zone", length = 64)
    private String timeZone;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected EmailPreference() {
        // Required by JPA.
    }

    private EmailPreference(
            UUID preferenceId,
            PreferenceKey key,
            EmailPreferenceSettings settings,
            String process,
            Instant now
    ) {
        Objects.requireNonNull(
                preferenceId,
                "preferenceId must not be null"
        );
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(settings, "settings must not be null");

        initializeAudit(process, now);

        this.preferenceId = preferenceId;
        this.sourceApplication = key.sourceApplication();
        this.recipientEmail = key.recipientEmail();

        applySettings(settings);
    }

    public static EmailPreference create(
            UUID preferenceId,
            PreferenceKey key,
            EmailPreferenceSettings settings,
            String process,
            Instant now
    ) {
        return new EmailPreference(
                preferenceId,
                key,
                settings,
                process,
                now
        );
    }

    public void updateSettings(
            EmailPreferenceSettings settings,
            String process,
            Instant now
    ) {
        Objects.requireNonNull(settings, "settings must not be null");

        updateAudit(process, now);
        applySettings(settings);
    }

    private void applySettings(EmailPreferenceSettings settings) {
        QuietHours hours = settings.quietHours();

        emailEnabled = settings.emailEnabled();
        localeTag = settings.localeTag();

        quietStart = hours == null ? null : hours.start();
        quietEnd = hours == null ? null : hours.end();
        timeZone = hours == null ? null : hours.zone().getId();
    }

    public EmailPreferenceSettings getSettings() {
        boolean quietHoursAbsent =
                quietStart == null
                        && quietEnd == null
                        && timeZone == null;

        QuietHours hours = quietHoursAbsent
                ? null
                : new QuietHours(
                        Objects.requireNonNull(
                                quietStart,
                                "Persisted quiet_start is missing"
                        ),
                        Objects.requireNonNull(
                                quietEnd,
                                "Persisted quiet_end is missing"
                        ),
                        ZoneId.of(Objects.requireNonNull(
                                timeZone,
                                "Persisted time_zone is missing"
                        ))
                );

        return new EmailPreferenceSettings(
                emailEnabled,
                localeTag,
                hours
        );
    }

    public UUID getPreferenceId() {
        return preferenceId;
    }

    public PreferenceKey getPreferenceKey() {
        return new PreferenceKey(sourceApplication, recipientEmail);
    }

    public String getSourceApplication() {
        return sourceApplication;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return "EmailPreference[preferenceId=" + preferenceId
                + ", recipientEmail=<redacted>]";
    }
}