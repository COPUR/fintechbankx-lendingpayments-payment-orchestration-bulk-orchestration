package com.enterprise.openfinance.bulkpayments.infrastructure.rest;

import com.enterprise.openfinance.bulkpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.bulkpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ResourceNotFoundException;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto.BulkErrorResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class BulkPaymentsExceptionHandlerTest {

    @Test
    void shouldMapDomainExceptions() {
        BulkPaymentsExceptionHandler handler = new BulkPaymentsExceptionHandler();
        MockHttpServletRequest request = request();

        ResponseEntity<BulkErrorResponse> forbidden = handler.handleForbidden(new ForbiddenException("forbidden"), request);
        ResponseEntity<BulkErrorResponse> notFound = handler.handleNotFound(new ResourceNotFoundException("missing"), request);
        ResponseEntity<BulkErrorResponse> conflict = handler.handleConflict(new IdempotencyConflictException("conflict"), request);

        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void shouldMapBusinessRuleBadRequestAndUnexpected() {
        BulkPaymentsExceptionHandler handler = new BulkPaymentsExceptionHandler();
        MockHttpServletRequest request = request();

        ResponseEntity<BulkErrorResponse> business = handler.handleBusinessRule(new BusinessRuleViolationException("rule"), request);
        ResponseEntity<BulkErrorResponse> badRequest = handler.handleBadRequest(new IllegalArgumentException("bad"), request);
        ResponseEntity<BulkErrorResponse> unexpected = handler.handleUnexpected(new RuntimeException("boom"), request);

        assertThat(business.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unexpected.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-FAPI-Interaction-ID", "ix-1");
        return request;
    }
}
