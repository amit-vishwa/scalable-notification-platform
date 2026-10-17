package io.github.amitvishwa.notification.config;

import io.github.amitvishwa.notification.domain.policy.RetryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRetryConfigurationTest {

    @Test
    void bindsDefaultsAndCreatesPolicy() {
        runner().run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(RetryPolicy.class);

            NotificationRetryProperties properties =
                    context.getBean(NotificationRetryProperties.class);

            assertThat(properties.maxAttempts()).isEqualTo(5);
            assertThat(properties.initialDelay())
                    .isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.maxDelay())
                    .isEqualTo(Duration.ofMinutes(15));
            assertThat(properties.processingLease())
                    .isEqualTo(Duration.ofMinutes(5));

            assertThat(context.getBean(RetryPolicy.class).delayAfter(1))
                    .contains(Duration.ofMinutes(1));
        });
    }

    @Test
    void bindsExplicitOverrides() {
        runner()
                .withPropertyValues(
                        "notification.retry.max-attempts=3",
                        "notification.retry.initial-delay=PT2S",
                        "notification.retry.max-delay=PT10S",
                        "notification.retry.processing-lease=PT1M"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    RetryPolicy policy =
                            context.getBean(RetryPolicy.class);

                    assertThat(policy.maxAttempts()).isEqualTo(3);
                    assertThat(policy.delayAfter(2))
                            .contains(Duration.ofSeconds(4));
                    assertThat(policy.delayAfter(3)).isEmpty();

                    assertThat(context.getBean(
                            NotificationRetryProperties.class
                    ).processingLease())
                            .isEqualTo(Duration.ofMinutes(1));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "notification.retry.max-attempts=0",
            "notification.retry.max-attempts=101",
            "notification.retry.initial-delay=PT0S",
            "notification.retry.initial-delay=PT25H",
            "notification.retry.max-delay=PT0S",
            "notification.retry.max-delay=PT25H",
            "notification.retry.processing-lease=PT29S",
            "notification.retry.processing-lease=PT61M"
    })
    void rejectsInvalidSettings(String setting) {
        runner()
                .withPropertyValues(setting)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    BindValidationException.class
                            );
                });
    }

    @Test
    void rejectsInitialDelayAboveMaximum() {
        runner()
                .withPropertyValues(
                        "notification.retry.initial-delay=PT2M",
                        "notification.retry.max-delay=PT1M"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    BindValidationException.class
                            );
                });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    var sources =
                            context.getEnvironment().getPropertySources();

                    sources.remove(
                            StandardEnvironment
                                    .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
                    );
                    sources.remove(
                            StandardEnvironment
                                    .SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME
                    );
                })
                .withUserConfiguration(
                        NotificationRetryConfiguration.class
                );
    }
}