package com.enterprise.openfinance.bulkpayments.infrastructure.rest;

import com.enterprise.openfinance.bulkpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.bulkpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.bulkpayments.domain.exception.ResourceNotFoundException;
import com.enterprise.openfinance.bulkpayments.infrastructure.rest.dto.BulkErrorResponse;
import com.enterprise.openfinance.bulkpayments.infrastructure.consent.ConsentServiceUnavailableException;
import org.junit.jupiter.api.Tag;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.bind.MissingRequestHeaderException;
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

    @Test
    void consentOutageIsServiceUnavailableWithoutLeakingDetails() {
        BulkPaymentsExceptionHandler handler = new BulkPaymentsExceptionHandler();

        ResponseEntity<BulkErrorResponse> response = handler.handleConsentUnavailable(
                new ConsentServiceUnavailableException("Consent service refused the lookup: 401", null), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo("CONSENT_SERVICE_UNAVAILABLE");
        assertThat(response.getBody().message()).doesNotContain("401");
        assertThat(response.getBody().interactionId()).isEqualTo("ix-1");
    }

    @Test
    void missingHeaderAndUnreadableBodyAreBadRequests() throws Exception {
        BulkPaymentsExceptionHandler handler = new BulkPaymentsExceptionHandler();
        MethodParameter parameter = new MethodParameter(
                BulkPaymentsExceptionHandlerTest.class.getDeclaredMethod("request"), -1);

        ResponseEntity<BulkErrorResponse> header = handler.handleMissingHeader(
                new MissingRequestHeaderException("x-idempotency-key", parameter), request());
        ResponseEntity<BulkErrorResponse> body = handler.handleUnreadable(
                new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0])), request());

        assertThat(header.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(header.getBody().message()).contains("x-idempotency-key");
        assertThat(body.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body.getBody().message()).isEqualTo("Malformed request body");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-FAPI-Interaction-ID", "ix-1");
        return request;
    }
}
