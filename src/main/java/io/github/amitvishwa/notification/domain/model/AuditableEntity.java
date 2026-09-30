package io.github.amitvishwa.notification.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import java.time.Instant;
import java.util.Objects;

@MappedSuperclass
public abstract class AuditableEntity {

    @Column(name = "created_by_process", nullable = false, length = 100)
    private String createdByProcess;

    @Column(name = "updated_by_process", nullable = false, length = 100)
    private String updatedByProcess;

    @Column(
            name = "created_at_time",
            nullable = false,
            columnDefinition = "DATETIME(6)"
    )
    private Instant createdAtTime;

    @Column(
            name = "updated_at_time",
            nullable = false,
            columnDefinition = "DATETIME(6)"
    )
    private Instant updatedAtTime;

    protected AuditableEntity() {
        // Required by JPA.
    }

    protected final void initializeAudit(
            String process,
            Instant now
    ) {
        validateProcess(process);
        Objects.requireNonNull(now, "now must not be null");

        this.createdByProcess = process;
        this.updatedByProcess = process;
        this.createdAtTime = now;
        this.updatedAtTime = now;
    }

    protected final void updateAudit(
            String process,
            Instant now
    ) {
        validateProcess(process);
        Objects.requireNonNull(now, "now must not be null");

        if (createdAtTime == null) {
            throw new IllegalStateException(
                    "Audit information has not been initialized"
            );
        }

        this.updatedByProcess = process;
        this.updatedAtTime = now;
    }

    private static void validateProcess(String process) {
        Objects.requireNonNull(process, "process must not be null");

        if (process.isBlank()) {
            throw new IllegalArgumentException(
                    "process must not be blank"
            );
        }

        if (process.length() > 100) {
            throw new IllegalArgumentException(
                    "process must not exceed 100 characters"
            );
        }
    }

    public String getCreatedByProcess() {
        return createdByProcess;
    }

    public String getUpdatedByProcess() {
        return updatedByProcess;
    }

    public Instant getCreatedAtTime() {
        return createdAtTime;
    }

    public Instant getUpdatedAtTime() {
        return updatedAtTime;
    }
}