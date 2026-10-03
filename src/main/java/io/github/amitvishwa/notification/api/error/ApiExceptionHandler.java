package io.github.amitvishwa.notification.api.error;

import io.github.amitvishwa.notification.api.security.AuthenticatedCaller;
import io.github.amitvishwa.notification.application.exception.IdempotencyConflictException;
import io.github.amitvishwa.notification.application.exception.NotificationNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final Clock clock;

    public ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(
            NotificationNotFoundException exception,
            WebRequest request
    ) {
        return applicationError(
                HttpStatus.NOT_FOUND,
                "NOTIFICATION_NOT_FOUND",
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Object> handleConflict(
            IdempotencyConflictException exception,
            WebRequest request
    ) {
        return applicationError(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                exception.getMessage(),
                request
        );
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                "One or more request fields are invalid."
        );

        problem.setProperty(
                "fieldErrors",
                exception.getBindingResult()
                        .getAllErrors()
                        .stream()
                        .map(error -> Map.of(
                                "field",
                                error instanceof FieldError fieldError
                                        ? fieldError.getField()
                                        : "request",
                                "message",
                                Objects.requireNonNullElse(
                                        error.getDefaultMessage(),
                                        "Invalid value"
                                )
                        ))
                        .toList()
        );

        return handleExceptionInternal(
                exception,
                problem,
                headers,
                status,
                request
        );
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ProblemDetail problem;

        if (body instanceof ProblemDetail suppliedProblem) {
            problem = suppliedProblem;
        } else {
            problem = ProblemDetail.forStatusAndDetail(
                    status,
                    status.is5xxServerError()
                            ? "An unexpected error occurred."
                            : "The request could not be processed."
            );
        }

        // Use safe wording for malformed JSON, UUIDs and other MVC errors.
        if (status.value() == 400
                && !(exception instanceof MethodArgumentNotValidException)) {
            problem.setDetail(
                    "The request contains malformed or unsupported input."
            );
        }

        if (status.is5xxServerError()) {
            problem = ProblemDetail.forStatusAndDetail(
                    status,
                    "An unexpected error occurred."
            );
        }

        return response(
                problem,
                headers,
                status.is5xxServerError()
                        ? "INTERNAL_ERROR"
                        : "INVALID_REQUEST",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(
            Exception exception,
            WebRequest request
    ) {
        ResponseEntity<Object> response = applicationError(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred.",
                request
        );

        LOGGER.error(
                "Unexpected API failure: correlationId={}, exceptionType={}",
                response.getHeaders().getFirst("X-Correlation-ID"),
                exception.getClass().getName()
        );

        return response;
    }

    private ResponseEntity<Object> applicationError(
            HttpStatus status,
            String errorCode,
            String detail,
            WebRequest request
    ) {
        return response(
                ProblemDetail.forStatusAndDetail(status, detail),
                HttpHeaders.EMPTY,
                errorCode,
                request
        );
    }

    private ResponseEntity<Object> response(
            ProblemDetail problem,
            HttpHeaders originalHeaders,
            String errorCode,
            WebRequest request
    ) {
        String correlationId = correlationId(request);

        problem.setProperty("errorCode", errorCode);
        problem.setProperty("timestamp", clock.instant().toString());
        problem.setProperty("correlationId", correlationId);

        if (request instanceof ServletWebRequest servletRequest) {
            String path = servletRequest.getRequest().getRequestURI();

            problem.setInstance(URI.create(path));
            problem.setProperty("path", path);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.putAll(originalHeaders);
        headers.set("X-Correlation-ID", correlationId);
        headers.setCacheControl("no-store");

        return new ResponseEntity<>(
                problem,
                headers,
                HttpStatusCode.valueOf(problem.getStatus())
        );
    }

    private String correlationId(WebRequest request) {
        if (request instanceof ServletWebRequest servletRequest) {
            Object attribute = servletRequest.getRequest()
                    .getAttribute(AuthenticatedCaller.REQUEST_ATTRIBUTE);

            if (attribute instanceof AuthenticatedCaller caller) {
                return caller.correlationId();
            }
        }

        return UUID.randomUUID().toString();
    }
}