package com.enterprise.openfinance.bulkpayments.infrastructure.rest;

import com.enterprise.openfinance.bulkpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ConsentAlreadyUsedException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.bulkpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ResourceNotFoundException;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto.BulkErrorResponse;
import com.enterprise.openfinance.bulkpayments.infrastructure.consent.ConsentServiceUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.enterprise.openfinance.bulkpayments.infrastructure.rest")
public class BulkPaymentsExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BulkPaymentsExceptionHandler.class);

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<BulkErrorResponse> handleForbidden(ForbiddenException exception,
                                                             HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(BulkErrorResponse.of("FORBIDDEN", exception.getMessage(), interactionId(request)));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<BulkErrorResponse> handleNotFound(ResourceNotFoundException exception,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(BulkErrorResponse.of("NOT_FOUND", exception.getMessage(), interactionId(request)));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<BulkErrorResponse> handleConflict(IdempotencyConflictException exception,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(BulkErrorResponse.of("CONFLICT", exception.getMessage(), interactionId(request)));
    }

    @ExceptionHandler(ConsentAlreadyUsedException.class)
    public ResponseEntity<BulkErrorResponse> handleConsentAlreadyUsed(ConsentAlreadyUsedException exception,
                                                                      HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(BulkErrorResponse.of("CONSENT_ALREADY_USED", exception.getMessage(), interactionId(request)));
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<BulkErrorResponse> handleBusinessRule(BusinessRuleViolationException exception,
                                                                HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(BulkErrorResponse.of("BUSINESS_RULE_VIOLATION", exception.getMessage(), interactionId(request)));
    }

    @ExceptionHandler(ConsentServiceUnavailableException.class)
    public ResponseEntity<BulkErrorResponse> handleConsentUnavailable(ConsentServiceUnavailableException exception,
                                                                      HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(BulkErrorResponse.of("CONSENT_SERVICE_UNAVAILABLE", "Consent could not be verified; retry later",
                        interactionId(request)));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<BulkErrorResponse> handleMissingHeader(MissingRequestHeaderException exception,
                                                                 HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(BulkErrorResponse.of("INVALID_REQUEST", "Missing header: " + exception.getHeaderName(),
                        interactionId(request)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BulkErrorResponse> handleUnreadable(HttpMessageNotReadableException exception,
                                                              HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(BulkErrorResponse.of("INVALID_REQUEST", "Malformed request body", interactionId(request)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<BulkErrorResponse> handleBadRequest(IllegalArgumentException exception,
                                                              HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(BulkErrorResponse.of("INVALID_REQUEST", exception.getMessage(), interactionId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<BulkErrorResponse> handleUnexpected(Exception exception,
                                                              HttpServletRequest request) {
        if (exception instanceof ErrorResponse standard) {
            // Spring MVC's own client errors (405, 406, 415, 404, ...) keep their status and headers.
            HttpStatusCode status = standard.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            return ResponseEntity.status(status).headers(standard.getHeaders())
                    .body(BulkErrorResponse.of(known != null ? known.name() : "HTTP_" + status.value(),
                            known != null ? known.getReasonPhrase() : "Request failed", interactionId(request)));
        }
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BulkErrorResponse.of("INTERNAL_ERROR", "Unexpected error occurred", interactionId(request)));
    }

    private static String interactionId(HttpServletRequest request) {
        return request.getHeader("X-FAPI-Interaction-ID");
    }
}
