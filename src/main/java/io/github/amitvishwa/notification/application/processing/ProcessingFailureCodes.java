package io.github.amitvishwa.notification.application.processing;

final class ProcessingFailureCodes {

    static final String RETRY_EXHAUSTED = "RETRY_EXHAUSTED";
    static final String OUTCOME_UNKNOWN = "DELIVERY_OUTCOME_UNKNOWN";
    static final String HISTORY_MISSING = "ATTEMPT_HISTORY_MISSING";

    private ProcessingFailureCodes() {
    }
}