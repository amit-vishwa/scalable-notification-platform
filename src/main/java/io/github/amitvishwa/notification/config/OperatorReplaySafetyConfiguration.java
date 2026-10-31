package io.github.amitvishwa.notification.config;

import io.github.amitvishwa.notification.domain.model.ManualReplayRequest;
import io.github.amitvishwa.notification.operator.replay.OperatorReplayArguments;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.context.WebApplicationContext;

import java.util.Arrays;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class OperatorReplaySafetyConfiguration {

    private static final String REQUEST_BEAN = "manualReplayRequest";

    @Bean
    static BeanFactoryPostProcessor operatorReplayStartupGuard(
            ConfigurableApplicationContext context,
            ApplicationArguments arguments
    ) {
        return beanFactory -> {
            Environment environment = context.getEnvironment();

            boolean operatorMode = environment.acceptsProfiles(
                    Profiles.of(OperatorReplayArguments.PROFILE)
            );

            if (!operatorMode) {
                if (OperatorReplayArguments.hasReplayArguments(
                        arguments.getSourceArgs()
                )) {
                    throw refused(
                            "Replay arguments require the operator profile"
                    );
                }

                return;
            }

            if (!Arrays.equals(
                    environment.getActiveProfiles(),
                    new String[]{OperatorReplayArguments.PROFILE}
            )) {
                throw refused(
                        "The operator profile must be used on its own"
                );
            }

            if (context instanceof WebServerApplicationContext
                    || context instanceof WebApplicationContext) {
                throw refused("A web application context is not allowed");
            }

            requireValue(
                    environment,
                    "spring.main.web-application-type",
                    "none"
            );
            requireValue(
                    environment,
                    "notification.worker.enabled",
                    "false"
            );
            requireValue(
                    environment,
                    "notification.worker.poll-enabled",
                    "false"
            );
            requireValue(environment, "spring.flyway.enabled", "false");
            requireValue(environment, "spring.sql.init.mode", "never");
            requireValue(environment, "spring.jpa.generate-ddl", "false");
            requireValue(
                    environment,
                    "spring.jpa.hibernate.ddl-auto",
                    "validate"
            );

            rejectUnapprovedJpaOverrides(environment);

            ManualReplayRequest request = OperatorReplayArguments.parse(
                    arguments.getSourceArgs(),
                    System.getProperty("user.name")
            );

            if (beanFactory.containsSingleton(REQUEST_BEAN)
                    || beanFactory.containsBeanDefinition(REQUEST_BEAN)) {
                throw refused("The replay request bean name is reserved");
            }

            beanFactory.registerSingleton(REQUEST_BEAN, request);
        };
    }

    private static void requireValue(
            Environment environment,
            String property,
            String requiredValue
    ) {
        if (!requiredValue.equalsIgnoreCase(
                environment.getProperty(property)
        )) {
            throw refused("Unsafe or missing setting: " + property);
        }
    }

    private static void rejectUnapprovedJpaOverrides(
            Environment environment
    ) {
        Map<String, String> overrides = Binder.get(environment)
                .bind(
                        "spring.jpa.properties",
                        Bindable.mapOf(String.class, String.class)
                )
                .orElse(Map.of());

        boolean unapproved = overrides.entrySet().stream()
                .anyMatch(entry -> !isApprovedJpaOverride(
                        entry.getKey(),
                        entry.getValue()
                ));

        if (unapproved) {
            throw refused("Custom JPA overrides are not allowed");
        }
    }

    private static boolean isApprovedJpaOverride(
            String property,
            String value
    ) {
        return switch (property) {
            case "hibernate.jdbc.time_zone" ->
                    "UTC".equalsIgnoreCase(value);

            case "hibernate.jdbc.log.errors",
                 "hibernate.jdbc.log.warnings" ->
                    "false".equalsIgnoreCase(value);

            default -> false;
        };
    }

    private static IllegalStateException refused(String reason) {
        return new IllegalStateException(
                "Operator replay refused: " + reason
        );
    }
}