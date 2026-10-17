package com.yetanalytics.hlaxapi.validation;

import com.yetanalytics.hlaxapi.validation.RequestSizeLimitFilter.RequestTooLargeException;
import com.yetanalytics.hlaxapi.validation.ValidationCore.InvalidRequestException;
import com.yetanalytics.hlaxapi.validation.ValidationModels.ProblemResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class ValidationExceptionHandler {

    private static final Logger logger = LogManager.getLogger(ValidationExceptionHandler.class);

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ProblemResponse> invalidEnvelope(InvalidRequestException error) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid validation request", error.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemResponse> unreadable(HttpMessageNotReadableException error) {
        RequestTooLargeException tooLarge = findCause(error, RequestTooLargeException.class);
        if (tooLarge != null) {
            return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Request too large", tooLarge.getMessage());
        }
        return problem(HttpStatus.BAD_REQUEST, "Malformed JSON request", "The request body is not valid JSON");
    }

    @ExceptionHandler(RequestTooLargeException.class)
    ResponseEntity<ProblemResponse> tooLarge(RequestTooLargeException error) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Request too large", error.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemResponse> internal(Exception error) {
        logger.error("Validation request failed", error);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Validation failed",
                "Validation could not complete because of an internal error");
    }

    private static ResponseEntity<ProblemResponse> problem(
            HttpStatus status,
            String title,
            String detail) {
        return ResponseEntity.status(status).body(new ProblemResponse(
                "about:blank", title, status.value(), detail));
    }

    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
