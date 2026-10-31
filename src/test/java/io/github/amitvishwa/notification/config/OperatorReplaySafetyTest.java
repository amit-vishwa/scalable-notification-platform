package io.github.amitvishwa.notification.config;

import io.github.amitvishwa.notification.api.security.ApiKeyAuthenticationFilter;
import io.github.amitvishwa.notification.application.exception.ReplayRejectedException;
import io.github.amitvishwa.notification.application.processing.NotificationBatchProcessor;
import io.github.amitvishwa.notification.application.replay.ManualReplayResult;
import io.github.amitvishwa.notification.application.replay.NotificationReplayService;
import io.github.amitvishwa.notification.domain.model.ManualReplayRequest;
import io.github.amitvishwa.notification.operator.replay.OperatorReplayArguments;
import io.github.amitvishwa.notification.operator.replay.OperatorReplayRunner;
import io.github.amitvishwa.notification.worker.NotificationWorkerScheduler;
import org.assertj.core.util.Throwables;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperatorReplaySafetyTest {

    private static final String[] SAFE_SETTINGS = {
            "spring.main.web-application-type=none",
            "notification.worker.enabled=false",
            "notification.worker.poll-enabled=false",
            "spring.flyway.enabled=false",
            "spring.sql.init.mode=never",
            "spring.jpa.generate-ddl=false",
            "spring.jpa.hibernate.ddl-auto=validate"
    };

    @Test
    void shouldPrepareOneRequestWithoutWebSecurityOrWorkers() {
        runner(true, validArguments()).run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(ManualReplayRequest.class)
                    .doesNotHaveBean(ApiKeyProperties.class)
                    .doesNotHaveBean(ApiKeyAuthenticationFilter.class)
                    .doesNotHaveBean(NotificationBatchProcessor.class)
                    .doesNotHaveBean(NotificationWorkerScheduler.class);

            ManualReplayRequest request =
                    context.getBean(ManualReplayRequest.class);

            assertEquals("order-service", request.sourceApplication());
            assertEquals(7, request.expectedVersion());
            assertEquals(
                    System.getProperty("user.name"),
                    request.operatorId()
            );
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "spring.main.web-application-type=servlet",
            "spring.main.web-application-type=reactive",
            "notification.worker.enabled=true",
            "notification.worker.poll-enabled=true",
            "spring.flyway.enabled=true",
            "spring.sql.init.mode=always",
            "spring.jpa.generate-ddl=true",
            "spring.jpa.hibernate.ddl-auto=create",
            "spring.jpa.hibernate.ddl-auto=update",
            "spring.jpa.properties.hibernate.hbm2ddl.auto=create",
            "spring.jpa.properties.jakarta.persistence.schema-generation.database.action=create"
    })
    void shouldRejectUnsafeSettingsBeforeOrdinaryBeans(String setting) {
        AtomicBoolean ordinaryBeanCreated = new AtomicBoolean();

        runner(true, validArguments())
                .withPropertyValues(setting)
                .withBean("ordinaryBean", Object.class, () -> {
                    ordinaryBeanCreated.set(true);
                    return new Object();
                })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "Operator replay refused"
                            );
                    assertFalse(ordinaryBeanCreated.get());
                });
    }

    @Test
    void shouldRejectAnActualWebContextEvenWhenPropertySaysNone() {
        AtomicBoolean ordinaryBeanCreated = new AtomicBoolean();

        new WebApplicationContextRunner()
                .withInitializer(context ->
                        initialize(context, true, validArguments())
                )
                .withUserConfiguration(
                        OperatorReplaySafetyConfiguration.class
                )
                .withPropertyValues(SAFE_SETTINGS)
                .withBean("ordinaryBean", Object.class, () -> {
                    ordinaryBeanCreated.set(true);
                    return new Object();
                })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "A web application context is not allowed"
                            );
                    assertFalse(ordinaryBeanCreated.get());
                });
    }

    @Test
    void shouldRejectReplayArgumentsWithoutTheOperatorProfile() {
        runner(false, validArguments()).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining(
                            "Replay arguments require the operator profile"
                    );
        });
    }

    @Test
    void shouldNotPrepareReplayDuringNormalStartup() {
        runner(false, new String[0]).run(context ->
                assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(ManualReplayRequest.class)
        );
    }

    @Test
    void shouldRejectMalformedCommandsBeforeOrdinaryBeans() {
        AtomicBoolean ordinaryBeanCreated = new AtomicBoolean();

        runner(true, append("--unknown=do-not-print"))
                .withBean("ordinaryBean", Object.class, () -> {
                    ordinaryBeanCreated.set(true);
                    return new Object();
                })
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();

                    assertThat(failure)
                            .hasStackTraceContaining(
                                    "Invalid operator replay arguments"
                            );

                    assertThat(Throwables.getStackTrace(failure))
                            .doesNotContain("do-not-print");
                    assertFalse(ordinaryBeanCreated.get());
                });
    }

    @Test
    void shouldStrictlyValidateRawArguments() {
        List<String[]> invalidCommands = new ArrayList<>();

        invalidCommands.add(new String[0]);
        invalidCommands.add(Arrays.copyOf(validArguments(), 6));
        invalidCommands.add(append("--unknown=do-not-print"));
        invalidCommands.add(append("--replay.operator-id=someone"));
        invalidCommands.add(append("--spring.datasource.password=secret"));
        invalidCommands.add(append("positional-argument"));
        invalidCommands.add(append(validArguments()[0]));
        invalidCommands.add(append(
                "--spring.profiles.active=local,operator-replay"
        ));

        invalidCommands.add(replace(0, "--replay.command-id=1-1-1-1-1"));
        invalidCommands.add(replace(1, "--replay.notification-id="));
        invalidCommands.add(replace(3, "--replay.expected-version=-1"));
        invalidCommands.add(replace(3, "--replay.expected-version=01"));
        invalidCommands.add(replace(
                3,
                "--replay.expected-version=9223372036854775808"
        ));
        invalidCommands.add(replace(
                5,
                "--replay.duplicate-risk-acknowledged=false"
        ));
        invalidCommands.add(replace(
                5,
                "--replay.duplicate-risk-acknowledged=TRUE"
        ));
        invalidCommands.add(replace(
                6,
                "--replay.previous-sender-stopped=false"
        ));
        invalidCommands.add(replace(
                6,
                "--replay.previous-sender-stopped"
        ));

        for (String[] command : invalidCommands) {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> OperatorReplayArguments.parse(
                            command,
                            "test-operator"
                    )
            );

            assertEquals(
                    "Invalid operator replay arguments",
                    exception.getMessage()
            );
            assertNull(exception.getCause());
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> OperatorReplayArguments.parse(
                        validArguments(),
                        " "
                )
        );
    }

    @Test
    void shouldAcceptTheExactOptionalProfileArgument() {
        ManualReplayRequest request = OperatorReplayArguments.parse(
                append("--spring.profiles.active=operator-replay"),
                "test-operator"
        );

        assertEquals("INC-2026-001", request.investigationReference());
        assertTrue(request.duplicateRiskAcknowledged());
        assertTrue(request.previousSenderStopped());
    }

    @Test
    void shouldInvokeReplayOnceAndPropagateFailureWithoutRetrying() {
        ManualReplayRequest request = OperatorReplayArguments.parse(
                validArguments(),
                "test-operator"
        );

        NotificationReplayService successService =
                mock(NotificationReplayService.class);

        when(successService.replay(request)).thenReturn(
                new ManualReplayResult(
                        request.commandId(),
                        request.notificationId(),
                        8,
                        Instant.parse("2026-10-17T09:00:00Z")
                )
        );

        OperatorReplayRunner successfulRunner =
                new OperatorReplayRunner(successService, request);

        successfulRunner.run(new DefaultApplicationArguments());

        verify(successService).replay(request);
        verifyNoMoreInteractions(successService);

        NotificationReplayService failedService =
                mock(NotificationReplayService.class);

        ReplayRejectedException rejection = new ReplayRejectedException(
                ReplayRejectedException.Reason.STALE_VERSION
        );

        when(failedService.replay(request)).thenThrow(rejection);

        OperatorReplayRunner failedRunner =
                new OperatorReplayRunner(failedService, request);

        assertSame(
                rejection,
                assertThrows(
                        ReplayRejectedException.class,
                        () -> failedRunner.run(
                                new DefaultApplicationArguments()
                        )
                )
        );

        verify(failedService).replay(request);
        verifyNoMoreInteractions(failedService);
    }

    @Test
    void shouldStillRejectUnauthenticatedHttpRequests() {
        webSecurityRunner()
                .withPropertyValues(
                        "notification.security.api-keys.employee-service="
                                + "test-only-employee-key-00000000000001"
                )
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(ApiKeyProperties.class)
                            .hasSingleBean(ApiKeyAuthenticationFilter.class);

                    MockHttpServletRequest request =
                            new MockHttpServletRequest(
                                    "POST",
                                    "/api/v1/notifications"
                            );

                    MockHttpServletResponse response =
                            new MockHttpServletResponse();

                    assertDoesNotThrow(() ->
                            context.getBean(
                                    ApiKeyAuthenticationFilter.class
                            ).doFilter(
                                    request,
                                    response,
                                    new MockFilterChain()
                            )
                    );

                    assertEquals(401, response.getStatus());
                });
    }

    @Test
    void shouldRejectMissingOrInvalidApiKeysDuringWebStartup() {
        webSecurityRunner().run(context ->
                assertThat(context).hasFailed()
        );

        webSecurityRunner()
                .withPropertyValues(
                        "notification.security.api-keys.employee-service=short"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldAcceptShippedOperatorConfigurationWithPrivateDatabaseLogging() {
        shippedOperatorRunner().run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(ManualReplayRequest.class)
                    .doesNotHaveBean(ApiKeyAuthenticationFilter.class)
                    .doesNotHaveBean(NotificationBatchProcessor.class)
                    .doesNotHaveBean(OperatorReplayRunner.class);

            Map<String, String> overrides = Binder.get(
                            context.getEnvironment()
                    )
                    .bind(
                            "spring.jpa.properties",
                            Bindable.mapOf(String.class, String.class)
                    )
                    .orElse(Map.of());

            assertEquals(
                    "UTC",
                    overrides.get("hibernate.jdbc.time_zone")
            );
            assertEquals(
                    "false",
                    overrides.get("hibernate.jdbc.log.errors")
            );
            assertEquals(
                    "false",
                    overrides.get("hibernate.jdbc.log.warnings")
            );
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "spring.jpa.properties.hibernate.jdbc.log.errors=true",
            "spring.jpa.properties.hibernate.jdbc.log.warnings=true",
            "spring.jpa.properties[hibernate.jdbc.log.errors]=true",
            "spring.jpa.properties[hibernate.jdbc.log.warnings]=true",
            "spring.jpa.properties.hibernate.jdbc.log.errors=",
            "spring.jpa.properties.hibernate.jdbc.log.warnings=",
            "spring.jpa.properties.hibernate.jdbc.time_zone=Europe/Paris",
            "spring.jpa.properties.hibernate.hbm2ddl.auto=create"
    })
    void shouldRejectUnsafeOverridesWithShippedOperatorConfiguration(
            String setting
    ) {
        AtomicBoolean ordinaryBeanCreated = new AtomicBoolean();

        shippedOperatorRunner()
                .withPropertyValues(setting)
                .withBean("ordinaryBean", Object.class, () -> {
                    ordinaryBeanCreated.set(true);
                    return new Object();
                })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "Custom JPA overrides are not allowed"
                            );
                    assertFalse(ordinaryBeanCreated.get());
                });
    }

    private ApplicationContextRunner shippedOperatorRunner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    initialize(context, true, validArguments());

                    new ConfigDataApplicationContextInitializer()
                            .initialize(context);
                })
                .withUserConfiguration(
                        OperatorReplaySafetyConfiguration.class,
                        NotificationWorkerConfiguration.class,
                        ApiSecurityConfiguration.class,
                        ApiKeyAuthenticationFilter.class
                );
    }

    private ApplicationContextRunner runner(
            boolean operatorProfile,
            String[] arguments
    ) {
        return new ApplicationContextRunner()
                .withInitializer(context ->
                        initialize(context, operatorProfile, arguments)
                )
                .withUserConfiguration(
                        OperatorReplaySafetyConfiguration.class,
                        NotificationWorkerConfiguration.class,
                        ApiSecurityConfiguration.class,
                        ApiKeyAuthenticationFilter.class
                )
                .withPropertyValues(SAFE_SETTINGS);
    }

    private WebApplicationContextRunner webSecurityRunner() {
        return new WebApplicationContextRunner()
                .withInitializer(
                        OperatorReplaySafetyTest::removeExternalProperties
                )
                .withUserConfiguration(
                        ApiSecurityConfiguration.class,
                        ApiKeyAuthenticationFilter.class
                );
    }

    private static void initialize(
            ConfigurableApplicationContext context,
            boolean operatorProfile,
            String[] arguments
    ) {
        removeExternalProperties(context);

        context.getEnvironment().setActiveProfiles(
                operatorProfile
                        ? new String[]{OperatorReplayArguments.PROFILE}
                        : new String[0]
        );

        context.getBeanFactory().registerSingleton(
                "springApplicationArguments",
                new DefaultApplicationArguments(arguments)
        );
    }

    private static void removeExternalProperties(
            ConfigurableApplicationContext context
    ) {
        var sources = context.getEnvironment().getPropertySources();

        sources.remove(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
        );
        sources.remove(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME
        );
    }

    private static String[] validArguments() {
        return new String[]{
                "--replay.command-id=11111111-1111-4111-8111-111111111111",
                "--replay.notification-id=22222222-2222-4222-8222-222222222222",
                "--replay.source-application=order-service",
                "--replay.expected-version=7",
                "--replay.investigation-reference=INC-2026-001",
                "--replay.duplicate-risk-acknowledged=true",
                "--replay.previous-sender-stopped=true"
        };
    }

    private static String[] append(String argument) {
        String[] arguments = Arrays.copyOf(validArguments(), 8);
        arguments[7] = argument;
        return arguments;
    }

    private static String[] replace(int index, String argument) {
        String[] arguments = validArguments();
        arguments[index] = argument;
        return arguments;
    }
}