package io.github.amitvishwa.notification.api.security;

import io.github.amitvishwa.notification.config.ApiKeyProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationFilterTest {

    private static final String API_KEY =
            "test-only-p010-key-0000000000000000001";

    @Mock
    private FilterChain chain;

    private ApiKeyAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthenticationFilter(
                new ApiKeyProperties(Map.of(
                        "employee-service",
                        API_KEY
                ))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1",
            "/api/v1/",
            "/api/v1/notifications",
            "/api/v1/email-preferences",
            "/api/v1/email-preferences/lookup",
            "/api/v1/email-preferences/00000000-0000-0000-0000-000000000001",
            "/api/v1;x=1/email-preferences",
            "/api;x=1/v1/email-preferences"
    })
    void requiresAuthenticationAcrossTheVersionedApi(String path)
            throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", path);
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        request.setContentType("application/json");
        request.setContent("{malformed".getBytes(
                java.nio.charset.StandardCharsets.UTF_8
        ));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control"))
                .isEqualTo("no-store");

        verifyNoInteractions(chain);
    }

    @Test
    void protectsApiWhenApplicationHasAContextPath() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "GET",
                        "/notification-platform/api/v1/email-preferences"
                );
        request.setContextPath("/notification-platform");

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void authenticatesAndSetsServerOwnedCaller() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "POST",
                        "/api/v1/email-preferences"
                );

        request.addHeader("X-API-Key", API_KEY);

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);

        Object attribute = request.getAttribute(
                AuthenticatedCaller.REQUEST_ATTRIBUTE
        );

        assertThat(attribute).isInstanceOf(AuthenticatedCaller.class);

        AuthenticatedCaller caller = (AuthenticatedCaller) attribute;

        assertThat(caller.sourceApplication())
                .isEqualTo("employee-service");
        assertThat(caller.correlationId())
                .isEqualTo(response.getHeader("X-Correlation-ID"));
        assertThat(UUID.fromString(caller.correlationId())).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-key", "", " "})
    void rejectsInvalidCredentials(String apiKey) throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "GET",
                        "/api/v1/email-preferences/lookup"
                );
        request.addHeader("X-API-Key", apiKey);

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void rejectsDuplicateCredentialHeaders() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "POST",
                        "/api/v1/email-preferences"
                );

        request.addHeader("X-API-Key", API_KEY);
        request.addHeader("X-API-Key", API_KEY);

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/health/readiness",
            "/api/v10/email-preferences"
    })
    void doesNotApplyV1AuthenticationOutsideItsBoundary(String path)
            throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);

        assertThat(request.getAttribute(
                AuthenticatedCaller.REQUEST_ATTRIBUTE
        )).isNull();
    }
}