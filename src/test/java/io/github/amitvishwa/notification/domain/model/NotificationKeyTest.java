package io.github.amitvishwa.notification.domain.model;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ActiveProfiles("test")
class NotificationKeyTest {

    @Test
    void shouldUseBothValuesForEquality() {
        NotificationKey first = new NotificationKey(
                "order-service",
                "order-123"
        );
        NotificationKey second = new NotificationKey(
                "order-service",
                "order-123"
        );
        NotificationKey differentApplication = new NotificationKey(
                "billing-service",
                "order-123"
        );

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, differentApplication);
    }

    @Test
    void shouldRejectBlankValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NotificationKey(" ", "request-1")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new NotificationKey("order-service", " ")
        );
    }

    @Test
    void shouldRejectValuesLongerThanDatabaseColumns() {
        String tooLong = "a".repeat(101);

        assertThrows(
                IllegalArgumentException.class,
                () -> new NotificationKey(tooLong, "request-1")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new NotificationKey("order-service", tooLong)
        );
    }
}