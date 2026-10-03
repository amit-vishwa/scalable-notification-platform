package io.github.amitvishwa.notification.api;

import io.github.amitvishwa.notification.api.dto.NotificationStatusResponse;
import io.github.amitvishwa.notification.api.dto.NotificationSubmissionRequest;
import io.github.amitvishwa.notification.api.dto.NotificationSubmissionResponse;
import io.github.amitvishwa.notification.api.security.AuthenticatedCaller;
import io.github.amitvishwa.notification.application.query.NotificationQueryService;
import io.github.amitvishwa.notification.application.query.NotificationStatusResult;
import io.github.amitvishwa.notification.application.submission.NotificationSubmissionResult;
import io.github.amitvishwa.notification.application.submission.NotificationSubmissionService;
import io.github.amitvishwa.notification.domain.model.NotificationKey;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationSubmissionService submissionService;
    private final NotificationQueryService queryService;

    public NotificationController(
            NotificationSubmissionService submissionService,
            NotificationQueryService queryService
    ) {
        this.submissionService = submissionService;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<NotificationSubmissionResponse> submit(
            @RequestAttribute(AuthenticatedCaller.REQUEST_ATTRIBUTE)
            AuthenticatedCaller caller,

            @Valid @RequestBody
            NotificationSubmissionRequest request
    ) {
        NotificationSubmissionResult result = submissionService.submit(
                new NotificationKey(
                        caller.sourceApplication(),
                        request.idempotencyKey()
                ),
                request.recipientEmail(),
                request.subject(),
                request.body()
        );

        String statusUrl =
                "/api/v1/notifications/" + result.notificationId();

        NotificationSubmissionResponse response =
                new NotificationSubmissionResponse(
                        result.notificationId(),
                        result.status(),
                        result.created()
                                ? "Notification accepted for processing."
                                : "Existing notification returned.",
                        statusUrl
                );

        if (result.created()) {
            return ResponseEntity.accepted()
                    .location(URI.create(statusUrl))
                    .cacheControl(CacheControl.noStore())
                    .body(response);
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping("/{notificationId}")
    public ResponseEntity<NotificationStatusResponse> findStatus(
            @RequestAttribute(AuthenticatedCaller.REQUEST_ATTRIBUTE)
            AuthenticatedCaller caller,

            @PathVariable("notificationId")
            UUID notificationId
    ) {
        NotificationStatusResult result = queryService.findStatus(
                notificationId,
                caller.sourceApplication()
        );

        NotificationStatusResponse response =
                new NotificationStatusResponse(
                        result.notificationId(),
                        result.sourceApplication(),
                        result.idempotencyKey(),
                        result.maskedRecipient(),
                        result.subject(),
                        result.status(),
                        result.retryCount(),
                        result.nextAttemptAt(),
                        result.lastAttemptAt(),
                        result.failureCode(),
                        result.createdAtTime(),
                        result.updatedAtTime()
                );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}