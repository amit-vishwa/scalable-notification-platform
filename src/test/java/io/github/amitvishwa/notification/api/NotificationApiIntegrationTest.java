package io.github.amitvishwa.notification.api;

import io.github.amitvishwa.notification.domain.model.SuppressionReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.address=127.0.0.1",
                "notification.worker.enabled=false",
                "notification.worker.poll-enabled=false",
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/"
                        + "notification_platform_test?serverTimezone=UTC",
                "spring.datasource.username=notification_test",
                "spring.datasource.password=${NOTIFICATION_TEST_DB_PASSWORD}",
                "spring.flyway.enabled=true",
                "spring.flyway.url=jdbc:mysql://127.0.0.1:3306/"
                        + "notification_platform_test?serverTimezone=UTC",
                "spring.flyway.user=notification_test",
                "spring.flyway.password=${NOTIFICATION_TEST_DB_PASSWORD}",
                "spring.flyway.schemas=notification_platform_test",
                "spring.flyway.default-schema=notification_platform_test",
                "spring.flyway.create-schemas=false",
                "spring.flyway.locations=classpath:db/migration",
                "spring.flyway.clean-disabled=true",
                "spring.flyway.baseline-on-migrate=false",
                "spring.flyway.out-of-order=false",
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@Isolated("Uses the dedicated MySQL test schema")
class NotificationApiIntegrationTest {

    private static final String ENDPOINT = "/api/v1/notifications";

    // Dummy credentials from application-test.yaml, not real secrets.
    private static final String EMPLOYEE_KEY =
            "test-only-employee-key-00000000000001";

    private static final String BILLING_KEY =
            "test-only-billing-key-000000000000002";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<CompletableFuture<HttpResponse<String>>> requests =
            new ArrayList<>();

    private String keyPrefix;

    @BeforeEach
    void prepareTestNamespace() {
        assertTestDatabase();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)",
                String.class
        )).isEqualTo("notification_test");

        keyPrefix = "api-test-" + UUID.randomUUID() + "-";
    }

    @AfterEach
    void removeOnlyThisTestsNotifications() throws Exception {
        if (keyPrefix == null) {
            return;
        }

        CompletableFuture<?>[] pending =
                requests.toArray(new CompletableFuture<?>[0]);

        // Retain fixtures if requests have not completed normally.
        CompletableFuture.allOf(pending).get(30, TimeUnit.SECONDS);

        assertTestDatabase();

        jdbcTemplate.update("""
                DELETE FROM notification
                WHERE source_application IN (?, ?)
                  AND idempotency_key LIKE ?
                """,
                "employee-service",
                "billing-service",
                keyPrefix + "%"
        );

        assertThat(storedCount()).isZero();
    }

    @Test
    void authenticatesBeforeParsingRequestBody() throws Exception {
        HttpResponse<String> response = post(null, "{invalid-json");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json(response).get("errorCode"))
                .isEqualTo("INVALID_API_KEY");
        assertThat(storedCount()).isZero();
    }

    @Test
    void rejectsInvalidApiKeyWithoutPersistingNotification() throws Exception {
        HttpResponse<String> response = post(
                "invalid-api-key",
                payload(keyPrefix + "invalid-auth", "Hello")
        );

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(storedCount()).isZero();
    }

    @Test
    void rejectsInvalidRecipientWithoutPersistingNotification()
            throws Exception {
        String body = payload(keyPrefix + "invalid-email", "Hello")
                .replace("recipient@example.com", "not-an-email");

        HttpResponse<String> response = post(EMPLOYEE_KEY, body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("errorCode"))
                .isEqualTo("INVALID_REQUEST");
        assertThat(storedCount()).isZero();
    }

    @Test
    void rejectsMalformedJsonWithoutPersistingNotification() throws Exception {
        HttpResponse<String> response =
                post(EMPLOYEE_KEY, "{invalid-json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(storedCount()).isZero();
    }

    @Test
    void rejectsClientSuppliedSourceApplication() throws Exception {
        String body = payload(keyPrefix + "spoofed-owner", "Hello");

        body = body.substring(0, body.lastIndexOf('}'))
                + ", \"sourceApplication\": \"billing-service\"}";

        HttpResponse<String> response = post(EMPLOYEE_KEY, body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(storedCount()).isZero();
    }

    @Test
    void persistsBeforeReturningAcceptedAndMasksStatusResponse()
            throws Exception {
        HttpResponse<String> submitted = post(
                EMPLOYEE_KEY,
                payload(keyPrefix + "accepted", "Hello")
        );

        assertThat(submitted.statusCode()).isEqualTo(202);

        String id = notificationId(submitted);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notification
                WHERE source_application = ?
                  AND idempotency_key = ?
                """,
                Long.class,
                "employee-service",
                keyPrefix + "accepted"
        )).isEqualTo(1L);

        assertThat(submitted.headers().firstValue("Location"))
                .contains(ENDPOINT + "/" + id);
        assertThat(submitted.headers().firstValue("Cache-Control"))
                .contains("no-store");

        HttpResponse<String> status = get(EMPLOYEE_KEY, id);
        Map<?, ?> result = json(status);

        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(result.get("notificationId")).isEqualTo(id);
        assertThat(result.get("sourceApplication"))
                .isEqualTo("employee-service");
        assertThat(result.get("status")).isEqualTo("PENDING");
        assertThat(result.get("maskedRecipient"))
                .isEqualTo("***@example.com");

        assertThat(result.containsKey("body")).isFalse();
        assertThat(result.containsKey("recipientEmail")).isFalse();
        assertThat(result.containsKey("providerMessageId")).isFalse();
        assertThat(result.containsKey("createdByProcess")).isFalse();
    }

    @ParameterizedTest
    @EnumSource(SuppressionReason.class)
    void exposesSuppressionReasonWithoutTreatingItAsDeliveryFailure(
            SuppressionReason reason
    ) throws Exception {
        String key = keyPrefix + "suppressed";

        HttpResponse<String> submitted = post(
                EMPLOYEE_KEY,
                payload(key, "Synthetic suppressed notification")
        );

        assertThat(submitted.statusCode()).isEqualTo(202);

        String id = notificationId(submitted);

        // Controlled status-view fixture. Actual worker transitions are
        // covered by PreferenceWorkerIntegrationTest.
        int changed = jdbcTemplate.update("""
                UPDATE notification
                SET status = 'SUPPRESSED',
                    suppression_reason = ?,
                    failure_code = NULL,
                    provider_message_id = NULL,
                    processing_expires_at = NULL,
                    version = version + 1,
                    updated_by_process = ?,
                    updated_at_time = UTC_TIMESTAMP(6)
                WHERE source_application = ?
                  AND idempotency_key = ?
                  AND notification_id = UNHEX(REPLACE(?, '-', ''))
                """,
                reason.name(),
                "status-view-fixture",
                "employee-service",
                key,
                id
        );

        assertThat(changed).isEqualTo(1);

        HttpResponse<String> response = get(EMPLOYEE_KEY, id);
        Map<?, ?> result = json(response);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(result.get("status")).isEqualTo("SUPPRESSED");
        assertThat(result.get("suppressionReason"))
                .isEqualTo(reason.name());
        assertThat(result.containsKey("failureCode")).isTrue();
        assertThat(result.get("failureCode")).isNull();
        assertThat(result.get("maskedRecipient"))
                .isEqualTo("***@example.com");

        assertThat(result.containsKey("body")).isFalse();
        assertThat(result.containsKey("recipientEmail")).isFalse();
        assertThat(result.containsKey("providerMessageId")).isFalse();
        assertThat(response.body()).doesNotContain("recipient@example.com");
        assertThat(response.headers().firstValue("Cache-Control"))
                .contains("no-store");
    }

    @Test
    void returnsOriginalNotificationForIdenticalRepeat() throws Exception {
        String body = payload(keyPrefix + "repeat", "Hello");

        HttpResponse<String> first = post(EMPLOYEE_KEY, body);
        HttpResponse<String> repeated = post(EMPLOYEE_KEY, body);

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(notificationId(repeated))
                .isEqualTo(notificationId(first));
        assertThat(storedCount()).isEqualTo(1L);
    }

    @Test
    void rejectsChangedPayloadWithoutOverwritingOriginal() throws Exception {
        String key = keyPrefix + "conflict";

        HttpResponse<String> original =
                post(EMPLOYEE_KEY, payload(key, "Original body"));

        HttpResponse<String> conflict =
                post(EMPLOYEE_KEY, payload(key, "Changed body"));

        assertThat(original.statusCode()).isEqualTo(202);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json(conflict).get("errorCode"))
                .isEqualTo("IDEMPOTENCY_CONFLICT");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT body FROM notification
                WHERE source_application = ?
                  AND idempotency_key = ?
                """,
                String.class,
                "employee-service",
                key
        )).isEqualTo("Original body");

        assertThat(storedCount()).isEqualTo(1L);
    }

    @Test
    void treatsSameKeyFromDifferentApplicationsAsSeparateNotifications()
            throws Exception {
        String body = payload(keyPrefix + "shared", "Hello");

        HttpResponse<String> employee = post(EMPLOYEE_KEY, body);
        HttpResponse<String> billing = post(BILLING_KEY, body);

        assertThat(employee.statusCode()).isEqualTo(202);
        assertThat(billing.statusCode()).isEqualTo(202);
        assertThat(notificationId(employee))
                .isNotEqualTo(notificationId(billing));
        assertThat(storedCount()).isEqualTo(2L);
    }

    @Test
    void returnsNotFoundForAnotherApplicationsNotification()
            throws Exception {
        HttpResponse<String> submitted = post(
                EMPLOYEE_KEY,
                payload(keyPrefix + "private", "Hello")
        );

        assertThat(submitted.statusCode()).isEqualTo(202);

        HttpResponse<String> response =
                get(BILLING_KEY, notificationId(submitted));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("errorCode"))
                .isEqualTo("NOTIFICATION_NOT_FOUND");
    }

    @Test
    void returnsNotFoundForUnknownIdAndBadRequestForMalformedId()
            throws Exception {
        assertThat(get(EMPLOYEE_KEY, UUID.randomUUID().toString())
                .statusCode()).isEqualTo(404);

        assertThat(get(EMPLOYEE_KEY, "not-a-uuid")
                .statusCode()).isEqualTo(400);
    }

    @Test
    void treatsCaseDistinctIdempotencyKeysAsDifferentKeys() throws Exception {
        HttpResponse<String> upper = post(
                EMPLOYEE_KEY,
                payload(keyPrefix + "Case", "Hello")
        );

        HttpResponse<String> lower = post(
                EMPLOYEE_KEY,
                payload(keyPrefix + "case", "Hello")
        );

        assertThat(upper.statusCode()).isEqualTo(202);
        assertThat(lower.statusCode()).isEqualTo(202);
        assertThat(notificationId(upper))
                .isNotEqualTo(notificationId(lower));
        assertThat(storedCount()).isEqualTo(2L);
    }

    @Test
    void concurrentIdenticalRequestsProduceOneNotification() throws Exception {
        String body = payload(keyPrefix + "concurrent", "Hello");

        CompletableFuture<HttpResponse<String>> first =
                start(postRequest(EMPLOYEE_KEY, body));

        CompletableFuture<HttpResponse<String>> second =
                start(postRequest(EMPLOYEE_KEY, body));

        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

        HttpResponse<String> firstResponse = first.join();
        HttpResponse<String> secondResponse = second.join();

        assertThat(new int[]{
                firstResponse.statusCode(),
                secondResponse.statusCode()
        }).containsExactlyInAnyOrder(202, 200);

        assertThat(notificationId(firstResponse))
                .isEqualTo(notificationId(secondResponse));
        assertThat(storedCount()).isEqualTo(1L);
    }

    private String payload(String key, String body) {
        return objectMapper.writeValueAsString(Map.of(
                "idempotencyKey", key,
                "recipientEmail", "recipient@example.com",
                "subject", "Integration test",
                "body", body
        ));
    }

    private HttpRequest postRequest(String apiKey, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(ENDPOINT))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }

        return builder.build();
    }

    private CompletableFuture<HttpResponse<String>> start(
            HttpRequest request
    ) {
        CompletableFuture<HttpResponse<String>> future = CLIENT.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        requests.add(future);
        return future;
    }

    private HttpResponse<String> send(HttpRequest request)
            throws Exception {
        return start(request).get(30, TimeUnit.SECONDS);
    }

    private HttpResponse<String> post(String apiKey, String body)
            throws Exception {
        return send(postRequest(apiKey, body));
    }

    private HttpResponse<String> get(String apiKey, String id)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        uri(ENDPOINT + "/" + id)
                )
                .timeout(Duration.ofSeconds(20))
                .header("X-API-Key", apiKey)
                .GET()
                .build();

        return send(request);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private Map<?, ?> json(HttpResponse<String> response) {
        return objectMapper.readValue(response.body(), Map.class);
    }

    private String notificationId(HttpResponse<String> response) {
        Object id = json(response).get("notificationId");

        assertThat(id).isNotNull();
        return id.toString();
    }

    private long storedCount() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notification
                WHERE source_application IN (?, ?)
                  AND idempotency_key LIKE ?
                """,
                Long.class,
                "employee-service",
                "billing-service",
                keyPrefix + "%"
        );

        assertThat(count).isNotNull();
        return count;
    }

    private void assertTestDatabase() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT DATABASE()",
                String.class
        )).isEqualTo("notification_platform_test");
    }
}