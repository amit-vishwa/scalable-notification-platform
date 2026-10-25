package io.github.amitvishwa.notification.config.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SmtpPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(context -> {
                        context.getEnvironment().getPropertySources().remove(
                                StandardEnvironment
                                        .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
                        );
                        context.getEnvironment().getPropertySources().remove(
                                StandardEnvironment
                                        .SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME
                        );
                    })
                    .withUserConfiguration(PropertiesConfiguration.class)
                    .withPropertyValues(
                            "notification.email.smtp.host=127.0.0.1",
                            "notification.email.smtp.from-address="
                                    + "notifications@example.invalid"
                    );

    @Test
    void bindsSafeDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            SmtpProperties properties =
                    context.getBean(SmtpProperties.class);

            assertThat(properties.port()).isEqualTo(587);
            assertThat(properties.security())
                    .isEqualTo(SmtpSecurityMode.STARTTLS);
            assertThat(properties.authentication()).isFalse();
            assertThat(properties.connectionTimeout())
                    .isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.readTimeout())
                    .isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.writeTimeout())
                    .isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void acceptsLocalCaptureSettings() {
        contextRunner
                .withPropertyValues(
                        "notification.email.smtp.port=1025",
                        "notification.email.smtp.security=PLAIN"
                )
                .run(context ->
                        assertThat(context).hasNotFailed()
                );
    }

    @Test
    void acceptsEncryptedAuthenticationAndRedactsCredentials() {
        contextRunner
                .withPropertyValues(
                        "notification.email.smtp.authentication=true",
                        "notification.email.smtp.username=smtp-test-user",
                        "notification.email.smtp.password=test-only-secret"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    SmtpProperties properties =
                            context.getBean(SmtpProperties.class);

                    assertThat(properties.toString())
                            .doesNotContain(
                                    "smtp-test-user",
                                    "test-only-secret"
                            );
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT1S", "PT30S"})
    void acceptsTimeoutBoundaries(String timeout) {
        contextRunner
                .withPropertyValues(
                        "notification.email.smtp.connection-timeout=" + timeout,
                        "notification.email.smtp.read-timeout=" + timeout,
                        "notification.email.smtp.write-timeout=" + timeout
                )
                .run(context ->
                        assertThat(context).hasNotFailed()
                );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "notification.email.smtp.host=",
            "notification.email.smtp.from-address=",
            "notification.email.smtp.from-address=not-an-email",
            "notification.email.smtp.port=0",
            "notification.email.smtp.port=65536",
            "notification.email.smtp.security=UNSUPPORTED",
            "notification.email.smtp.connection-timeout=PT0.5S",
            "notification.email.smtp.read-timeout=PT31S",
            "notification.email.smtp.write-timeout=PT0S",
            "notification.email.smtp.authentication=true",
            "notification.email.smtp.username=unused-user",
            "notification.email.smtp.password=test-only-unused-secret"
    })
    void rejectsInvalidSettings(String setting) {
        contextRunner
                .withPropertyValues(setting)
                .run(context ->
                        assertThat(context).hasFailed()
                );
    }

    @Test
    void rejectsAuthenticationOverPlaintext() {
        contextRunner
                .withPropertyValues(
                        "notification.email.smtp.security=PLAIN",
                        "notification.email.smtp.authentication=true",
                        "notification.email.smtp.username=smtp-test-user",
                        "notification.email.smtp.password=test-only-secret"
                )
                .run(context ->
                        assertThat(context).hasFailed()
                );
    }

    @Test
    void rejectsPlaintextForNonLoopbackHost() {
        contextRunner
                .withPropertyValues(
                        "notification.email.smtp.host=smtp.example.invalid",
                        "notification.email.smtp.security=PLAIN"
                )
                .run(context ->
                        assertThat(context).hasFailed()
                );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SmtpProperties.class)
    static class PropertiesConfiguration {
    }
}