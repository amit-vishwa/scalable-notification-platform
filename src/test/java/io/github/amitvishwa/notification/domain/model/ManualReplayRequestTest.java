package io.github.amitvishwa.notification.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ManualReplayRequestTest {

    @Test
    void shouldPreserveTheExactSourceApplication() {
        ManualReplayRequest request = request(12, true, true);

        assertEquals("Order-Service", request.sourceApplication());
        assertEquals(12, request.expectedVersion());
        assertEquals("INC-2026-001", request.investigationReference());
    }

    @Test
    void shouldRequireBothExplicitConfirmations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> request(12, false, true)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> request(12, true, false)
        );
    }

    @Test
    void shouldRejectInvalidVersions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> request(-1, true, true)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> request(Long.MAX_VALUE, true, true)
        );
    }

    @Test
    void shouldRejectUnsafeInvestigationReferences() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManualReplayRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Order-Service",
                        12,
                        "operator",
                        "INC-001\nuntrusted-text",
                        true,
                        true
                )
        );
    }

    private static ManualReplayRequest request(
            long expectedVersion,
            boolean acknowledgeRisk,
            boolean senderStopped
    ) {
        return new ManualReplayRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Order-Service",
                expectedVersion,
                "operator",
                "INC-2026-001",
                acknowledgeRisk,
                senderStopped
        );
    }
}