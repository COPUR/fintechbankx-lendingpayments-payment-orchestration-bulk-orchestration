package com.enterprise.openfinance.bulkpayments.infrastructure.rest;

import com.enterprise.openfinance.bulkpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.bulkpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ResourceNotFoundException;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto.BulkErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.enterprise.openfinance.bulkpayments.infrastructure.rest")
public class BulkPaymentsExceptionHandler {

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

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<BulkErrorResponse> handleBusinessRule(BusinessRuleViolationException exception,
                                                                HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(BulkErrorResponse.of("BUSINESS_RULE_VIOLATION", exception.getMessage(), interactionId(request)));
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
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BulkErrorResponse.of("INTERNAL_ERROR", "Unexpected error occurred", interactionId(request)));
    }

    private static String interactionId(HttpServletRequest request) {
        return request.getHeader("X-FAPI-Interaction-ID");
    }
}
