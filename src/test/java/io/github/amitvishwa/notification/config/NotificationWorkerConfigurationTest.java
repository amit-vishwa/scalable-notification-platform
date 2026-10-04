package io.github.amitvishwa.notification.config;

import io.github.amitvishwa.notification.application.port.email.EmailProvider;
import io.github.amitvishwa.notification.application.processing.NotificationBatchProcessor;
import io.github.amitvishwa.notification.application.processing.NotificationClaimService;
import io.github.amitvishwa.notification.application.processing.NotificationOutcomeService;
import io.github.amitvishwa.notification.infrastructure.email.SimulatedEmailProvider;
import io.github.amitvishwa.notification.worker.NotificationWorkerScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationWorkerConfigurationTest {

    @Test
    void disablesWorkerByDefaultAndBindsDefaultSettings() {
        runner("test").run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(NotificationBatchProcessor.class)
                    .doesNotHaveBean(NotificationWorkerScheduler.class)
                    .doesNotHaveBean(EmailProvider.class);

            NotificationWorkerProperties properties =
                    context.getBean(NotificationWorkerProperties.class);

            assertThat(properties.enabled()).isFalse();
            assertThat(properties.pollEnabled()).isTrue();
            assertThat(properties.batchSize()).isEqualTo(10);
            assertThat(properties.pollDelay())
                    .isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void doesNotEnableWorkerMerelyBecausePollingIsEnabled() {
        runner("local")
                .withPropertyValues(
                        "notification.worker.enabled=false",
                        "notification.worker.poll-enabled=true"
                )
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(NotificationBatchProcessor.class)
                        .doesNotHaveBean(NotificationWorkerScheduler.class)
                        .doesNotHaveBean(EmailProvider.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test"})
    void enablesSimulatedProcessorWithoutScheduledPolling(String profile) {
        runner(profile)
                .withPropertyValues(
                        "notification.worker.enabled=true",
                        "notification.worker.poll-enabled=false"
                )
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(NotificationBatchProcessor.class)
                            .hasSingleBean(EmailProvider.class)
                            .doesNotHaveBean(NotificationWorkerScheduler.class);

                    assertThat(context.getBean(EmailProvider.class))
                            .isInstanceOf(SimulatedEmailProvider.class);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "true"})
    void enablesPollingByDefaultOrWhenExplicitlyRequested(String setting) {
        ApplicationContextRunner configured = runner("test")
                .withPropertyValues(
                        "notification.worker.enabled=true"
                );

        if (!setting.equals("default")) {
            configured = configured.withPropertyValues(
                    "notification.worker.poll-enabled=" + setting
            );
        }

        configured.run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(NotificationBatchProcessor.class)
                .hasSingleBean(NotificationWorkerScheduler.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "prod",
            "bootstrap",
            "test,prod",
            "local,prod",
            "test,bootstrap",
            "local,bootstrap"
    })
    void failsWithoutRealProviderWhenSimulatorIsForbidden(String profiles) {
        runner(profiles.split(","))
                .withPropertyValues(
                        "notification.worker.enabled=true",
                        "notification.worker.poll-enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasFailed();

                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    NoSuchBeanDefinitionException.class
                            );
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 11})
    void rejectsInvalidBatchSize(int batchSize) {
        runner("test")
                .withPropertyValues(
                        "notification.worker.batch-size=" + batchSize
                )
                .run(context -> {
                    assertThat(context).hasFailed();

                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    BindValidationException.class
                            );
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0.5S", "PT61S"})
    void rejectsInvalidPollingDelay(String delay) {
        runner("test")
                .withPropertyValues(
                        "notification.worker.poll-delay=" + delay
                )
                .run(context -> {
                    assertThat(context).hasFailed();

                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    BindValidationException.class
                            );
                });
    }

    @ParameterizedTest
    @CsvSource({
            "1, PT1S",
            "10, PT1M"
    })
    void acceptsConfigurationBoundaries(int batchSize, String delay) {
        runner("test")
                .withPropertyValues(
                        "notification.worker.batch-size=" + batchSize,
                        "notification.worker.poll-delay=" + delay
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    NotificationWorkerProperties properties =
                            context.getBean(NotificationWorkerProperties.class);

                    assertThat(properties.batchSize()).isEqualTo(batchSize);
                    assertThat(properties.pollDelay())
                            .isEqualTo(Duration.parse(delay));
                });
    }

    private ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    // Ignore host settings in these isolated wiring tests.
                    // This does not modify the actual environment.
                    context.getEnvironment().getPropertySources().remove(
                            StandardEnvironment
                                    .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
                    );
                    context.getEnvironment().getPropertySources().remove(
                            StandardEnvironment
                                    .SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME
                    );
                    context.getEnvironment().setActiveProfiles(profiles);
                })
                .withUserConfiguration(
                        NotificationWorkerConfiguration.class,
                        SimulatedEmailProvider.class
                )
                .withBean(
                        NotificationClaimService.class,
                        () -> mock(NotificationClaimService.class)
                )
                .withBean(
                        NotificationOutcomeService.class,
                        () -> mock(NotificationOutcomeService.class)
                )
                .withBean(
                        "taskScheduler",
                        TaskScheduler.class,
                        NotificationWorkerConfigurationTest::inertScheduler
                );
    }

    private static TaskScheduler inertScheduler() {
        TaskScheduler scheduler = mock(TaskScheduler.class);

        when(scheduler.getClock()).thenReturn(Clock.systemUTC());

        when(scheduler.scheduleWithFixedDelay(
                any(Runnable.class),
                any(Instant.class),
                any(Duration.class)
        )).thenAnswer(invocation -> mock(ScheduledFuture.class));

        return scheduler;
    }
}