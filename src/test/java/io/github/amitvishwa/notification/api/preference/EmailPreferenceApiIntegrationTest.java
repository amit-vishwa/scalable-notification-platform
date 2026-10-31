package io.github.amitvishwa.notification.api.preference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.LinkedHashMap;
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

                "spring.datasource.url="
                        + "jdbc:mysql://127.0.0.1:3306/"
                        + "notification_platform_test?serverTimezone=UTC",
                "spring.datasource.username=notification_test",
                "spring.datasource.password=${NOTIFICATION_TEST_DB_PASSWORD}",

                "spring.flyway.enabled=true",
                "spring.flyway.url="
                        + "jdbc:mysql://127.0.0.1:3306/"
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
class EmailPreferenceApiIntegrationTest {

    private static final String ENDPOINT = "/api/v1/email-preferences";

    // Dummy credentials from application-test.yaml.
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
    private JdbcTemplate jdbc;

    private final List<CompletableFuture<HttpResponse<String>>> requests =
            new ArrayList<>();

    private String recipientPrefix;

    @BeforeEach
    void verifyDatabaseAndPrepareNamespace() {
        assertTestDatabase();

        assertThat(jdbc.queryForObject(
                "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)",
                String.class
        )).isEqualTo("notification_test");

        recipientPrefix = "p010api-"
                + UUID.randomUUID().toString().replace("-", "")
                + "-";
    }

    @AfterEach
    void cleanOnlyThisTestsPreferences() throws Exception {
        if (recipientPrefix == null) {
            return;
        }

        // If request completion cannot be confirmed, fail before deletion.
        // A timed-out HTTP client does not prove the server stopped working.
        CompletableFuture<?>[] pending =
                requests.toArray(new CompletableFuture<?>[0]);

        CompletableFuture.allOf(pending).get(30, TimeUnit.SECONDS);

        assertTestDatabase();

        jdbc.update("""
                DELETE FROM email_preference
                WHERE source_application IN (?, ?)
                  AND recipient_email LIKE ?
                """,
                "employee-service",
                "billing-service",
                recipientPrefix + "%"
        );

        assertThat(storedCount()).isZero();
    }

    @Test
    void createsReadsAndLooksUpPreferences() throws Exception {
        String recipient = recipient("roundtrip");

        HttpResponse<String> created = send(request(
                "POST",
                ENDPOINT,
                EMPLOYEE_KEY,
                body(createPayload(
                        recipient,
                        settings(
                                true,
                                "en-us",
                                quietHours("22:00", "07:00", "Asia/Kolkata")
                        )
                ))
        ));

        assertThat(created.statusCode())
                .as("Response: %s", created.body())
                .isEqualTo(201);

        Map<?, ?> createdBody = json(created);
        String id = preferenceId(created);

        assertThat(created.headers().firstValue("Location"))
                .contains(ENDPOINT + "/" + id);
        assertThat(created.headers().firstValue("Cache-Control"))
                .contains("no-store");

        assertThat(createdBody.get("sourceApplication"))
                .isEqualTo("employee-service");
        assertThat(createdBody.get("recipientEmail")).isEqualTo(recipient);
        assertThat(version(createdBody)).isZero();
        assertThat(nested(createdBody, "settings").get("localeTag"))
                .isEqualTo("en-US");

        HttpResponse<String> found = get(EMPLOYEE_KEY, id);

        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(json(found).get("preferenceId")).isEqualTo(id);
        assertThat(json(found).get("settings"))
                .isEqualTo(createdBody.get("settings"));

        HttpResponse<String> lookedUp = send(request(
                "POST",
                ENDPOINT + "/lookup",
                EMPLOYEE_KEY,
                body(Map.of("recipientEmail", recipient))
        ));

        assertThat(lookedUp.statusCode()).isEqualTo(200);
        assertThat(json(lookedUp).get("preferenceId")).isEqualTo(id);
        assertThat(storedCount()).isEqualTo(1L);
    }

    @Test
    void updatesSettingsPreservesCreationAuditAndRejectsStaleVersion()
            throws Exception {
        String id = createPreference(
                EMPLOYEE_KEY,
                recipient("update"),
                settings(
                        true,
                        "en-IN",
                        quietHours("22:00", "07:00", "Asia/Kolkata")
                )
        );

        // Compare persisted GET snapshots, not POST timestamp precision.
        Map<?, ?> before = json(get(EMPLOYEE_KEY, id));
        long initialVersion = version(before);

        HttpResponse<String> updated = send(request(
                "PUT",
                ENDPOINT + "/" + id,
                EMPLOYEE_KEY,
                body(updatePayload(
                        initialVersion,
                        settings(false, "hi-IN", null)
                ))
        ));

        assertThat(updated.statusCode())
                .as("Response: %s", updated.body())
                .isEqualTo(200);
        assertThat(version(json(updated)))
                .isEqualTo(initialVersion + 1);

        Map<?, ?> after = json(get(EMPLOYEE_KEY, id));
        Map<?, ?> afterSettings = nested(after, "settings");

        assertThat(afterSettings.get("emailEnabled")).isEqualTo(false);
        assertThat(afterSettings.get("localeTag")).isEqualTo("hi-IN");
        assertThat(afterSettings.get("quietHours")).isNull();

        assertThat(after.get("recipientEmail"))
                .isEqualTo(before.get("recipientEmail"));
        assertThat(after.get("sourceApplication"))
                .isEqualTo(before.get("sourceApplication"));
        assertThat(after.get("createdByProcess"))
                .isEqualTo(before.get("createdByProcess"));
        assertThat(after.get("createdAtTime"))
                .isEqualTo(before.get("createdAtTime"));
        assertThat(after.get("updatedByProcess"))
                .isEqualTo("email-preference-api-update");

        HttpResponse<String> stale = send(request(
                "PUT",
                ENDPOINT + "/" + id,
                EMPLOYEE_KEY,
                body(updatePayload(
                        initialVersion,
                        settings(true, "en-IN", null)
                ))
        ));

        assertError(stale, 409, "PREFERENCE_VERSION_CONFLICT");

        Map<?, ?> unchanged = json(get(EMPLOYEE_KEY, id));

        assertThat(version(unchanged)).isEqualTo(initialVersion + 1);
        assertThat(unchanged.get("settings"))
                .isEqualTo(after.get("settings"));
    }

    @Test
    void duplicateCreationDoesNotOverwriteExistingSettings()
            throws Exception {
        String recipient = recipient("duplicate");

        String id = createPreference(
                EMPLOYEE_KEY,
                recipient,
                settings(true, "en-IN", null)
        );

        Map<?, ?> before = json(get(EMPLOYEE_KEY, id));

        HttpResponse<String> duplicate = send(request(
                "POST",
                ENDPOINT,
                EMPLOYEE_KEY,
                body(createPayload(
                        recipient,
                        settings(false, "hi-IN", null)
                ))
        ));

        assertError(duplicate, 409, "PREFERENCE_ALREADY_EXISTS");
        assertThat(duplicate.body()).doesNotContain(recipient);

        Map<?, ?> after = json(get(EMPLOYEE_KEY, id));

        assertThat(after.get("settings"))
                .isEqualTo(before.get("settings"));
        assertThat(version(after)).isEqualTo(version(before));
        assertThat(after.get("createdAtTime"))
                .isEqualTo(before.get("createdAtTime"));
        assertThat(storedCount()).isEqualTo(1L);
    }

    @Test
    void isolatesApplicationsForReadsUpdatesAndRecipientLookup()
            throws Exception {
        String recipient = recipient("private");

        String employeeId = createPreference(
                EMPLOYEE_KEY,
                recipient,
                settings(true, "en-IN", null)
        );

        HttpResponse<String> forbiddenRead = get(BILLING_KEY, employeeId);
        assertError(forbiddenRead, 404, "PREFERENCE_NOT_FOUND");
        assertThat(forbiddenRead.body()).doesNotContain(recipient);

        HttpResponse<String> forbiddenUpdate = send(request(
                "PUT",
                ENDPOINT + "/" + employeeId,
                BILLING_KEY,
                body(updatePayload(
                        999,
                        settings(false, "hi-IN", null)
                ))
        ));

        assertError(forbiddenUpdate, 404, "PREFERENCE_NOT_FOUND");

        HttpResponse<String> forbiddenLookup = send(request(
                "POST",
                ENDPOINT + "/lookup",
                BILLING_KEY,
                body(Map.of("recipientEmail", recipient))
        ));

        assertError(forbiddenLookup, 404, "PREFERENCE_NOT_FOUND");

        String billingId = createPreference(
                BILLING_KEY,
                recipient,
                settings(false, "hi-IN", null)
        );

        assertThat(billingId).isNotEqualTo(employeeId);

        HttpResponse<String> billingLookup = send(request(
                "POST",
                ENDPOINT + "/lookup",
                BILLING_KEY,
                body(Map.of("recipientEmail", recipient))
        ));

        assertThat(billingLookup.statusCode()).isEqualTo(200);
        assertThat(json(billingLookup).get("preferenceId"))
                .isEqualTo(billingId);

        Map<?, ?> employee = json(get(EMPLOYEE_KEY, employeeId));

        assertThat(employee.get("sourceApplication"))
                .isEqualTo("employee-service");
        assertThat(nested(employee, "settings").get("emailEnabled"))
                .isEqualTo(true);
        assertThat(version(employee)).isZero();
        assertThat(storedCount()).isEqualTo(2L);
    }

    @Test
    void preservesCaseDistinctRecipientIdentity() throws Exception {
        String upperId = createPreference(
                EMPLOYEE_KEY,
                recipient("Case"),
                settings(true, "en-IN", null)
        );

        String lowerId = createPreference(
                EMPLOYEE_KEY,
                recipient("case"),
                settings(true, "en-IN", null)
        );

        assertThat(upperId).isNotEqualTo(lowerId);
        assertThat(storedCount()).isEqualTo(2L);
    }

    @ParameterizedTest
    @CsvSource({
            "POST,",
            "POST,/lookup",
            "GET,/00000000-0000-0000-0000-000000000001",
            "PUT,/00000000-0000-0000-0000-000000000001"
    })
    void authenticatesEveryRouteBeforeProcessingInput(
            String method,
            String suffix
    ) throws Exception {
        String path = ENDPOINT + (suffix == null ? "" : suffix);

        for (String apiKey : new String[]{null, "invalid-api-key"}) {
            HttpResponse<String> response = send(request(
                    method,
                    path,
                    apiKey,
                    "GET".equals(method) ? null : "{malformed-json"
            ));

            assertError(response, 401, "INVALID_API_KEY");
        }

        assertThat(storedCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "missing-settings",
            "null-settings",
            "missing-enabled",
            "null-enabled",
            "missing-locale",
            "invalid-email",
            "invalid-locale",
            "unknown-zone",
            "offset-zone",
            "partial-quiet",
            "seconds",
            "equal-times",
            "client-owner",
            "unknown-setting"
    })
    void rejectsInvalidCreationWithoutPersistingAnything(
            String invalidCase
    ) throws Exception {
        Map<String, Object> requestedSettings =
                settings(true, "en-IN", null);

        Map<String, Object> payload = createPayload(
                recipient("invalid"),
                requestedSettings
        );

        String expectedError = "INVALID_REQUEST";

        switch (invalidCase) {
            case "missing-settings" -> payload.remove("settings");
            case "null-settings" -> payload.put("settings", null);
            case "missing-enabled" ->
                    requestedSettings.remove("emailEnabled");
            case "null-enabled" ->
                    requestedSettings.put("emailEnabled", null);
            case "missing-locale" ->
                    requestedSettings.remove("localeTag");
            case "invalid-email" ->
                    payload.put("recipientEmail", recipientPrefix + "invalid");
            case "invalid-locale" -> {
                requestedSettings.put(
                        "localeTag",
                        "not_a_locale_sensitive"
                );
                expectedError = "INVALID_PREFERENCE_REQUEST";
            }
            case "unknown-zone" -> {
                requestedSettings.put(
                        "quietHours",
                        quietHours("22:00", "07:00", "Private/Invalid-zone")
                );
                expectedError = "INVALID_PREFERENCE_REQUEST";
            }
            case "offset-zone" -> {
                requestedSettings.put(
                        "quietHours",
                        quietHours("22:00", "07:00", "+02:00")
                );
                expectedError = "INVALID_PREFERENCE_REQUEST";
            }
            case "partial-quiet" -> requestedSettings.put(
                    "quietHours",
                    Map.of("start", "22:00")
            );
            case "seconds" -> requestedSettings.put(
                    "quietHours",
                    quietHours("22:00:00", "07:00", "Asia/Kolkata")
            );
            case "equal-times" -> {
                requestedSettings.put(
                        "quietHours",
                        quietHours("22:00", "22:00", "Asia/Kolkata")
                );
                expectedError = "INVALID_PREFERENCE_REQUEST";
            }
            case "client-owner" ->
                    payload.put("sourceApplication", "billing-service");
            case "unknown-setting" ->
                    requestedSettings.put("unexpectedSetting", true);
            default -> throw new IllegalArgumentException(
                    "Unknown test case"
            );
        }

        HttpResponse<String> response = send(request(
                "POST",
                ENDPOINT,
                EMPLOYEE_KEY,
                body(payload)
        ));

        assertError(response, 400, expectedError);

        assertThat(response.body()).doesNotContain(
                recipient("invalid"),
                "not_a_locale_sensitive",
                "Private/Invalid-zone"
        );

        assertThat(storedCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "missing-version",
            "null-version",
            "negative-version",
            "recipient",
            "owner",
            "id"
    })
    void rejectsInvalidUpdatesWithoutChangingStoredPreference(
            String invalidCase
    ) throws Exception {
        String id = createPreference(
                EMPLOYEE_KEY,
                recipient("immutable"),
                settings(true, "en-IN", null)
        );

        Map<String, Object> payload = updatePayload(
                0,
                settings(false, "hi-IN", null)
        );

        switch (invalidCase) {
            case "missing-version" -> payload.remove("expectedVersion");
            case "null-version" -> payload.put("expectedVersion", null);
            case "negative-version" -> payload.put("expectedVersion", -1);
            case "recipient" ->
                    payload.put("recipientEmail", recipient("changed"));
            case "owner" ->
                    payload.put("sourceApplication", "billing-service");
            case "id" ->
                    payload.put("preferenceId", UUID.randomUUID().toString());
            default -> throw new IllegalArgumentException(
                    "Unknown test case"
            );
        }

        HttpResponse<String> response = send(request(
                "PUT",
                ENDPOINT + "/" + id,
                EMPLOYEE_KEY,
                body(payload)
        ));

        assertError(response, 400, "INVALID_REQUEST");

        Map<?, ?> unchanged = json(get(EMPLOYEE_KEY, id));

        assertThat(unchanged.get("recipientEmail"))
                .isEqualTo(recipient("immutable"));
        assertThat(nested(unchanged, "settings").get("emailEnabled"))
                .isEqualTo(true);
        assertThat(version(unchanged)).isZero();
        assertThat(storedCount()).isEqualTo(1L);
    }

    @Test
    void returnsSafeErrorsForUnknownAndMalformedIds() throws Exception {
        assertError(
                get(EMPLOYEE_KEY, UUID.randomUUID().toString()),
                404,
                "PREFERENCE_NOT_FOUND"
        );

        assertError(
                get(EMPLOYEE_KEY, "not-a-uuid"),
                400,
                "INVALID_REQUEST"
        );
    }

    @Test
    void concurrentCreatesProduceOnePreferenceAndOneConflict()
            throws Exception {
        String payload = body(createPayload(
                recipient("concurrent-create"),
                settings(true, "en-IN", null)
        ));

        CompletableFuture<HttpResponse<String>> first = start(request(
                "POST", ENDPOINT, EMPLOYEE_KEY, payload
        ));

        CompletableFuture<HttpResponse<String>> second = start(request(
                "POST", ENDPOINT, EMPLOYEE_KEY, payload
        ));

        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

        HttpResponse<String> firstResponse = first.join();
        HttpResponse<String> secondResponse = second.join();

        assertThat(new int[]{
                firstResponse.statusCode(),
                secondResponse.statusCode()
        }).containsExactlyInAnyOrder(201, 409);

        HttpResponse<String> winner = firstResponse.statusCode() == 201
                ? firstResponse
                : secondResponse;

        HttpResponse<String> conflict = firstResponse.statusCode() == 409
                ? firstResponse
                : secondResponse;

        assertError(conflict, 409, "PREFERENCE_ALREADY_EXISTS");

        String id = preferenceId(winner);

        assertThat(get(EMPLOYEE_KEY, id).statusCode()).isEqualTo(200);
        assertThat(storedCount()).isEqualTo(1L);
    }

    @Test
    void concurrentUpdatesHaveOneWinnerWithoutLostUpdate()
            throws Exception {
        String id = createPreference(
                EMPLOYEE_KEY,
                recipient("concurrent-update"),
                settings(true, "en-IN", null)
        );

        long initialVersion = version(json(get(EMPLOYEE_KEY, id)));

        CompletableFuture<HttpResponse<String>> first = start(request(
                "PUT",
                ENDPOINT + "/" + id,
                EMPLOYEE_KEY,
                body(updatePayload(
                        initialVersion,
                        settings(false, "hi-IN", null)
                ))
        ));

        CompletableFuture<HttpResponse<String>> second = start(request(
                "PUT",
                ENDPOINT + "/" + id,
                EMPLOYEE_KEY,
                body(updatePayload(
                        initialVersion,
                        settings(true, "fr-FR", null)
                ))
        ));

        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

        HttpResponse<String> firstResponse = first.join();
        HttpResponse<String> secondResponse = second.join();

        assertThat(new int[]{
                firstResponse.statusCode(),
                secondResponse.statusCode()
        }).containsExactlyInAnyOrder(200, 409);

        HttpResponse<String> winner = firstResponse.statusCode() == 200
                ? firstResponse
                : secondResponse;

        HttpResponse<String> conflict = firstResponse.statusCode() == 409
                ? firstResponse
                : secondResponse;

        assertThat(json(conflict).get("errorCode")).isIn(
                "PREFERENCE_VERSION_CONFLICT",
                "CONCURRENT_MODIFICATION"
        );

        Map<?, ?> persisted = json(get(EMPLOYEE_KEY, id));

        assertThat(version(persisted)).isEqualTo(initialVersion + 1);
        assertThat(persisted.get("settings"))
                .isEqualTo(json(winner).get("settings"));
        assertThat(storedCount()).isEqualTo(1L);
    }

    private String createPreference(
            String apiKey,
            String recipient,
            Map<String, Object> requestedSettings
    ) throws Exception {
        HttpResponse<String> response = send(request(
                "POST",
                ENDPOINT,
                apiKey,
                body(createPayload(recipient, requestedSettings))
        ));

        assertThat(response.statusCode())
                .as("Response: %s", response.body())
                .isEqualTo(201);

        return preferenceId(response);
    }

    private HttpResponse<String> get(String apiKey, String id)
            throws Exception {
        return send(request(
                "GET",
                ENDPOINT + "/" + id,
                apiKey,
                null
        ));
    }

    private HttpRequest request(
            String method,
            String path,
            String apiKey,
            String requestBody
    ) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path)
                )
                .timeout(Duration.ofSeconds(20));

        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }

        if (requestBody != null) {
            builder.header("Content-Type", "application/json");
        }

        return builder.method(
                method,
                requestBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(requestBody)
        ).build();
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

    private void assertError(
            HttpResponse<String> response,
            int expectedStatus,
            String expectedCode
    ) {
        assertThat(response.statusCode())
                .as("Response: %s", response.body())
                .isEqualTo(expectedStatus);

        assertThat(json(response).get("errorCode"))
                .isEqualTo(expectedCode);
        assertThat(response.headers().firstValue("Cache-Control"))
                .contains("no-store");
        assertThat(response.headers().firstValue("X-Correlation-ID"))
                .isPresent();
        assertThat(response.body())
                .doesNotContain(EMPLOYEE_KEY, BILLING_KEY);
    }

    private String recipient(String label) {
        return recipientPrefix + label + "@example.invalid";
    }

    private String body(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private Map<?, ?> json(HttpResponse<String> response) {
        return objectMapper.readValue(response.body(), Map.class);
    }

    private String preferenceId(HttpResponse<String> response) {
        Object id = json(response).get("preferenceId");

        assertThat(id).isInstanceOf(String.class);
        return (String) id;
    }

    private static Map<?, ?> nested(Map<?, ?> parent, String field) {
        Object value = parent.get(field);

        assertThat(value).isInstanceOf(Map.class);
        return (Map<?, ?>) value;
    }

    private static long version(Map<?, ?> response) {
        Object value = response.get("version");

        assertThat(value).isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }

    private static Map<String, Object> createPayload(
            String recipient,
            Map<String, Object> requestedSettings
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("recipientEmail", recipient);
        payload.put("settings", requestedSettings);

        return payload;
    }

    private static Map<String, Object> updatePayload(
            long version,
            Map<String, Object> requestedSettings
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("expectedVersion", version);
        payload.put("settings", requestedSettings);

        return payload;
    }

    private static Map<String, Object> settings(
            boolean enabled,
            String locale,
            Object quietHours
    ) {
        Map<String, Object> settings = new LinkedHashMap<>();

        settings.put("emailEnabled", enabled);
        settings.put("localeTag", locale);
        settings.put("quietHours", quietHours);

        return settings;
    }

    private static Map<String, String> quietHours(
            String start,
            String end,
            String zone
    ) {
        return Map.of(
                "start", start,
                "end", end,
                "timeZone", zone
        );
    }

    private long storedCount() {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM email_preference
                WHERE source_application IN (?, ?)
                  AND recipient_email LIKE ?
                """,
                Long.class,
                "employee-service",
                "billing-service",
                recipientPrefix + "%"
        );

        assertThat(count).isNotNull();
        return count;
    }

    private void assertTestDatabase() {
        assertThat(jdbc.queryForObject(
                "SELECT DATABASE()",
                String.class
        )).isEqualTo("notification_platform_test");
    }
}