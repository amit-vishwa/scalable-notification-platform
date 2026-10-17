package io.github.amitvishwa.notification.operator.replay;

import io.github.amitvishwa.notification.domain.model.ManualReplayRequest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class OperatorReplayArguments {

    public static final String PROFILE = "operator-replay";

    private static final String PROFILE_OPTION = "spring.profiles.active";

    private static final Set<String> REQUIRED_OPTIONS = Set.of(
            "replay.command-id",
            "replay.notification-id",
            "replay.source-application",
            "replay.expected-version",
            "replay.investigation-reference",
            "replay.duplicate-risk-acknowledged",
            "replay.previous-sender-stopped"
    );

    private OperatorReplayArguments() {
    }

    public static boolean hasReplayArguments(String[] arguments) {
        return Arrays.stream(arguments)
                .anyMatch(argument -> argument.startsWith("--replay."));
    }

    public static ManualReplayRequest parse(
            String[] arguments,
            String operatorId
    ) {
        try {
            Map<String, String> options = readOptions(arguments);

            if (!options.keySet().containsAll(REQUIRED_OPTIONS)) {
                throw invalidArguments();
            }

            String version = options.get("replay.expected-version");

            if (!version.matches("0|[1-9][0-9]{0,18}")) {
                throw invalidArguments();
            }

            return new ManualReplayRequest(
                    parseUuid(options.get("replay.command-id")),
                    parseUuid(options.get("replay.notification-id")),
                    options.get("replay.source-application"),
                    Long.parseLong(version),
                    operatorId,
                    options.get("replay.investigation-reference"),
                    requireConfirmation(
                            options.get("replay.duplicate-risk-acknowledged")
                    ),
                    requireConfirmation(
                            options.get("replay.previous-sender-stopped")
                    )
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            // Do not expose argument values or attach a value-bearing cause.
            throw invalidArguments();
        }
    }

    private static Map<String, String> readOptions(String[] arguments) {
        Map<String, String> options = new HashMap<>();

        for (String argument : arguments) {
            if (!argument.startsWith("--")) {
                throw invalidArguments();
            }

            int separator = argument.indexOf('=');

            if (separator <= 2 || separator == argument.length() - 1) {
                throw invalidArguments();
            }

            String name = argument.substring(2, separator);
            String value = argument.substring(separator + 1);

            if (!REQUIRED_OPTIONS.contains(name)
                    && !PROFILE_OPTION.equals(name)) {
                throw invalidArguments();
            }

            if (options.putIfAbsent(name, value) != null) {
                throw invalidArguments();
            }

            if (PROFILE_OPTION.equals(name) && !PROFILE.equals(value)) {
                throw invalidArguments();
            }
        }

        return options;
    }

    private static UUID parseUuid(String value) {
        UUID parsed = UUID.fromString(value);

        if (!parsed.toString().equalsIgnoreCase(value)) {
            throw invalidArguments();
        }

        return parsed;
    }

    private static boolean requireConfirmation(String value) {
        if (!"true".equals(value)) {
            throw invalidArguments();
        }

        return true;
    }

    private static IllegalArgumentException invalidArguments() {
        return new IllegalArgumentException(
                "Invalid operator replay arguments"
        );
    }
}