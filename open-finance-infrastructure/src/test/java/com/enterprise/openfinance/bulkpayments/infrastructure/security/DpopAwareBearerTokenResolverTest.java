package com.enterprise.openfinance.bulkpayments.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class DpopAwareBearerTokenResolverTest {

    private final DpopAwareBearerTokenResolver resolver = new DpopAwareBearerTokenResolver();

    @Test
    void readsDpopAndBearerSchemes() {
        assertThat(resolver.resolve(request("DPoP abc.def.ghi"))).isEqualTo("abc.def.ghi");
        assertThat(resolver.resolve(request("dpop abc.def.ghi"))).isEqualTo("abc.def.ghi");
        assertThat(resolver.resolve(request("Bearer abc.def.ghi"))).isEqualTo("abc.def.ghi");
        assertThat(resolver.resolve(request("DPoP  "))).isNull();
        assertThat(resolver.resolve(new MockHttpServletRequest())).isNull();
    }

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", authorization);
        return request;
    }
}
