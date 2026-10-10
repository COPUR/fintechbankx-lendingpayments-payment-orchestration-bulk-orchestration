package com.enterprise.openfinance.bulkpayments.startup;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.util.Locale;
import java.util.Set;

/**
 * Startup TLS assertion (governance round 3, item 2). The application refuses to
 * start, before any bean is created or any connection is opened, unless
 * <ul>
 *   <li>{@code spring.datasource.url} carries {@code sslmode=verify-full}, and</li>
 *   <li>when a Kafka client is configured ({@code spring.kafka.bootstrap-servers}
 *   has a value), {@code spring.kafka.security.protocol} is a TLS protocol:
 *   {@code SASL_SSL} (MSK, IAM over TLS) or {@code SSL} (Strimzi mutual TLS).</li>
 * </ul>
 * One switch, {@code fintechbankx.tls.enforce}, true in application.yml; only
 * the local profile and explicit test configuration set it false. The Helm chart
 * never sets it (its values guard refuses FINTECHBANKX_TLS_ENFORCE). Registered
 * in META-INF/spring.factories, so the service, the "migrate" Job and every
 * {@code @SpringBootTest} of this module run it. The failure message names the
 * offending setting and the value found for the mode or protocol only; it never
 * repeats the datasource URL, which may carry credentials.
 */
public class TlsEnforcementInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String ENFORCE = "fintechbankx.tls.enforce";
    static final String DATASOURCE_URL = "spring.datasource.url";
    static final String KAFKA_BOOTSTRAP_SERVERS = "spring.kafka.bootstrap-servers";
    static final String KAFKA_SECURITY_PROTOCOL = "spring.kafka.security.protocol";
    static final String REQUIRED_SSLMODE = "verify-full";
    /** Kafka protocols with TLS in transit; PLAINTEXT and SASL_PLAINTEXT are refused. */
    static final Set<String> TLS_KAFKA_PROTOCOLS = Set.of("SASL_SSL", "SSL");

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Environment environment = context.getEnvironment();
        if (!environment.getProperty(ENFORCE, Boolean.class, true)) {
            return;
        }
        assertDatasourceVerifyFull(environment.getProperty(DATASOURCE_URL));
        String bootstrapServers = environment.getProperty(KAFKA_BOOTSTRAP_SERVERS);
        if (bootstrapServers != null && !bootstrapServers.isBlank()) {
            assertKafkaTls(environment.getProperty(KAFKA_SECURITY_PROTOCOL));
        }
    }

    private static void assertDatasourceVerifyFull(String url) {
        String sslmode = sslmode(url);
        if (!REQUIRED_SSLMODE.equals(sslmode)) {
            throw new IllegalStateException(ENFORCE + "=true: " + DATASOURCE_URL + " must carry sslmode="
                    + REQUIRED_SSLMODE + " (found " + (sslmode == null ? "no sslmode" : "sslmode=" + sslmode)
                    + "); only the local profile or test configuration may set " + ENFORCE + "=false");
        }
    }

    private static void assertKafkaTls(String protocol) {
        String found = protocol == null ? "" : protocol.trim().toUpperCase(Locale.ROOT);
        if (!TLS_KAFKA_PROTOCOLS.contains(found)) {
            throw new IllegalStateException(ENFORCE + "=true: " + KAFKA_SECURITY_PROTOCOL
                    + " must be SASL_SSL (or SSL for Strimzi mutual TLS) because " + KAFKA_BOOTSTRAP_SERVERS
                    + " is set (found " + (found.isEmpty() ? "none" : found) + ")");
        }
    }

    /** The sslmode query parameter of a JDBC URL, or null when absent. */
    static String sslmode(String url) {
        if (url == null) {
            return null;
        }
        int query = url.indexOf('?');
        if (query < 0) {
            return null;
        }
        for (String parameter : url.substring(query + 1).split("&")) {
            int equals = parameter.indexOf('=');
            if (equals > 0 && parameter.substring(0, equals).equalsIgnoreCase("sslmode")) {
                return parameter.substring(equals + 1).trim().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }
}
