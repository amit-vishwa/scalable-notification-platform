package io.github.amitvishwa.notification.config.email;

import io.github.amitvishwa.notification.application.port.email.EmailProvider;
import io.github.amitvishwa.notification.infrastructure.email.smtp.SmtpEmailProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Configuration(proxyBeanMethods = false)
@Profile("!operator-replay & !bootstrap")
@ConditionalOnProperty(
        prefix = "notification.worker",
        name = "enabled",
        havingValue = "true"
)
@ConditionalOnProperty(
        prefix = "notification.email",
        name = "provider",
        havingValue = "smtp"
)
@EnableConfigurationProperties(SmtpProperties.class)
public class SmtpEmailConfiguration {

    @Bean
    public EmailProvider smtpEmailProvider(
            SmtpProperties properties,
            Environment environment
    ) {
        if (properties.security() == SmtpSecurityMode.PLAIN
                && !environment.acceptsProfiles(
                        Profiles.of("(local | test) & !prod")
                )) {
            throw new IllegalStateException(
                    "Plaintext SMTP requires an eligible local/test profile"
            );
        }

        return new SmtpEmailProvider(
                createSender(properties),
                properties.fromAddress()
        );
    }

    static JavaMailSenderImpl createSender(SmtpProperties properties) {
        String protocol = properties.security() == SmtpSecurityMode.SMTPS
                ? "smtps"
                : "smtp";

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setProtocol(protocol);
        sender.setHost(properties.host());
        sender.setPort(properties.port());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        sender.setJavaMailProperties(mailProperties(properties, protocol));

        if (properties.authentication()) {
            sender.setUsername(properties.username());
            sender.setPassword(properties.password());
        }

        return sender;
    }

    private static Properties mailProperties(
            SmtpProperties properties,
            String protocol
    ) {
        String prefix = "mail." + protocol + ".";

        boolean implicitTls =
                properties.security() == SmtpSecurityMode.SMTPS;
        boolean startTls =
                properties.security() == SmtpSecurityMode.STARTTLS;

        Properties mail = new Properties();

        mail.setProperty(
                prefix + "connectiontimeout",
                Long.toString(properties.connectionTimeout().toMillis())
        );
        mail.setProperty(
                prefix + "timeout",
                Long.toString(properties.readTimeout().toMillis())
        );
        mail.setProperty(
                prefix + "writetimeout",
                Long.toString(properties.writeTimeout().toMillis())
        );
        mail.setProperty(
                prefix + "auth",
                Boolean.toString(properties.authentication())
        );
        mail.setProperty(
                prefix + "ssl.enable",
                Boolean.toString(implicitTls)
        );
        mail.setProperty(
                prefix + "starttls.enable",
                Boolean.toString(startTls)
        );
        mail.setProperty(
                prefix + "starttls.required",
                Boolean.toString(startTls)
        );

        mail.setProperty(prefix + "ssl.checkserveridentity", "true");
        mail.setProperty(prefix + "sendpartial", "false");
        mail.setProperty(prefix + "reportsuccess", "false");
        mail.setProperty("mail.debug", "false");

        return mail;
    }
}