package io.github.amitvishwa.notification.config.email;

import io.github.amitvishwa.notification.application.port.email.EmailProvider;
import io.github.amitvishwa.notification.infrastructure.email.SimulatedEmailProvider;
import io.github.amitvishwa.notification.infrastructure.email.smtp.SmtpEmailProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class SmtpEmailConfigurationTest {

    @Test
    void ignoresSmtpSettingsWhenWorkerIsDisabled() {
        runner("local")
                .withPropertyValues(
                        "notification.worker.enabled=false",
                        "notification.email.provider=smtp",
                        "notification.email.smtp.port=-1",
                        "notification.email.smtp.security=INVALID"
                )
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(EmailProvider.class)
                        .doesNotHaveBean(EmailProviderProperties.class)
                        .doesNotHaveBean(SmtpProperties.class)
                        .doesNotHaveBean(JavaMailSender.class));
    }

    @Test
    void retainsDefaultSimulatorWithoutBindingSmtpSettings() {
        runner("test")
                .withPropertyValues(
                        "notification.worker.enabled=true",
                        "notification.email.smtp.port=-1"
                )
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(EmailProvider.class)
                            .doesNotHaveBean(SmtpProperties.class)
                            .doesNotHaveBean(JavaMailSender.class);

                    assertThat(context.getBean(EmailProvider.class))
                            .isInstanceOf(SimulatedEmailProvider.class);
                    assertThat(context.getBean(EmailProviderProperties.class)
                            .provider())
                            .isEqualTo(EmailProviderType.SIMULATED);
                });
    }

    @ParameterizedTest
    @CsvSource({"local, smtp", "test, SMTP"})
    void selectsOnlySmtpForEligibleLocalCapture(
            String profile,
            String provider
    ) {
        smtpRunner(profile)
                .withPropertyValues(
                        "notification.email.provider=" + provider
                )
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(EmailProvider.class)
                            .hasSingleBean(SmtpProperties.class)
                            .doesNotHaveBean(SimulatedEmailProvider.class)
                            .doesNotHaveBean(JavaMailSender.class);

                    assertThat(context.getBean(EmailProvider.class))
                            .isInstanceOf(SmtpEmailProvider.class);
                });
    }

    @Test
    void allowsEncryptedSmtpInProductionWithoutConnecting() {
        smtpRunner("prod")
                .withPropertyValues(
                        "notification.email.smtp.host=smtp.example.invalid",
                        "notification.email.smtp.security=STARTTLS"
                )
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(EmailProvider.class)
                            .doesNotHaveBean(JavaMailSender.class);

                    assertThat(context.getBean(EmailProvider.class))
                            .isInstanceOf(SmtpEmailProvider.class);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "bootstrap",
            "operator-replay",
            "local,bootstrap",
            "test,operator-replay",
            "prod,operator-replay"
    })
    void excludesSendingAndPropertyBindingInRestrictedProfiles(
            String profiles
    ) {
        smtpRunner(profiles.split(","))
                .withPropertyValues(
                        "notification.email.smtp.security=INVALID"
                )
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(EmailProvider.class)
                        .doesNotHaveBean(EmailProviderProperties.class)
                        .doesNotHaveBean(SmtpProperties.class)
                        .doesNotHaveBean(JavaMailSender.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "local,prod", "test,prod"})
    void rejectsProductionPlaintextEvenWithLocalProfiles(String profiles) {
        smtpRunner(profiles.split(","))
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "notification.email.smtp.host=",
            "notification.email.smtp.from-address=",
            "notification.email.provider=UNSUPPORTED"
    })
    void rejectsInvalidActiveConfigurationWithoutFallback(String setting) {
        smtpRunner("local")
                .withPropertyValues(setting)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @CsvSource({
            "PLAIN, smtp, false, false",
            "STARTTLS, smtp, false, true",
            "SMTPS, smtps, true, false"
    })
    void configuresTransportSecurityAndTimeouts(
            SmtpSecurityMode security,
            String protocol,
            boolean implicitTls,
            boolean startTls
    ) {
        boolean authentication = security != SmtpSecurityMode.PLAIN;

        SmtpProperties properties = new SmtpProperties(
                "127.0.0.1",
                implicitTls ? 465 : 587,
                "notifications@example.invalid",
                security,
                authentication,
                authentication ? "test-user" : null,
                authentication ? "test-only-password" : null,
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ofSeconds(4)
        );

        JavaMailSenderImpl sender =
                SmtpEmailConfiguration.createSender(properties);

        assertThat(sender.getProtocol()).isEqualTo(protocol);
        assertThat(sender.getHost()).isEqualTo("127.0.0.1");
        assertThat(sender.getPort()).isEqualTo(implicitTls ? 465 : 587);
        assertThat(sender.getDefaultEncoding()).isEqualTo("UTF-8");
        assertThat(sender.getUsername())
                .isEqualTo(authentication ? "test-user" : null);
        assertThat(sender.getPassword())
                .isEqualTo(authentication ? "test-only-password" : null);

        String prefix = "mail." + protocol + ".";
        Properties mail = sender.getJavaMailProperties();

        assertThat(mail)
                .containsEntry(prefix + "connectiontimeout", "2000")
                .containsEntry(prefix + "timeout", "3000")
                .containsEntry(prefix + "writetimeout", "4000")
                .containsEntry(
                        prefix + "auth",
                        Boolean.toString(authentication)
                )
                .containsEntry(
                        prefix + "ssl.enable",
                        Boolean.toString(implicitTls)
                )
                .containsEntry(
                        prefix + "starttls.enable",
                        Boolean.toString(startTls)
                )
                .containsEntry(
                        prefix + "starttls.required",
                        Boolean.toString(startTls)
                )
                .containsEntry(prefix + "ssl.checkserveridentity", "true")
                .containsEntry(prefix + "sendpartial", "false")
                .containsEntry(prefix + "reportsuccess", "false")
                .containsEntry("mail.debug", "false")
                .doesNotContainKey(prefix + "ssl.trust");
    }

    private ApplicationContextRunner smtpRunner(String... profiles) {
        return runner(profiles).withPropertyValues(
                "notification.worker.enabled=true",
                "notification.email.provider=smtp",
                "notification.email.smtp.host=127.0.0.1",
                "notification.email.smtp.port=1025",
                "notification.email.smtp.from-address="
                        + "notifications@example.invalid",
                "notification.email.smtp.security=PLAIN"
        );
    }

    private ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
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
                        NotificationEmailConfiguration.class,
                        SmtpEmailConfiguration.class,
                        SimulatedEmailProvider.class
                );
    }
}