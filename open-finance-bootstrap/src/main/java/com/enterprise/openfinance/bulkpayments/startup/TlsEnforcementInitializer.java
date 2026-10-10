package com.enterprise.openfinance.bulkpayments.startup;

import org.apache.kafka.clients.CommonClientConfigs;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Startup TLS assertion (governance round 3, item 2; hardened in round 6, guardrail 4a).
 * The application refuses to start, before any bean is created or any connection is
 * opened, unless
 * <ul>
 *   <li>every datasource URL a pool can use ({@code spring.datasource.url}, required;
 *   {@code spring.datasource.hikari.jdbc-url} and {@code spring.flyway.url} when set)
 *   carries exactly one {@code sslmode=verify-full}, read the way PgJDBC reads the query:
 *   keys are case-sensitive ({@code SSLMODE} is ignored by the driver, which then falls
 *   back to {@code prefer}), the last of several {@code sslmode} wins, a key is the text
 *   before the first {@code '='}, and values are percent-decoded. The keys
 *   {@code sslfactory}, {@code sslfactoryarg}, {@code sslhostnameverifier},
 *   {@code sslpasswordcallback} and {@code service} are refused: they bypass certificate
 *   or host name verification, or load TLS settings from pg_service.conf; and</li>
 *   <li>when a Kafka client is configured ({@code spring.kafka.bootstrap-servers} has a
 *   value), the producer's effective {@code security.protocol}, bound through
 *   {@link KafkaProperties} so that {@code spring.kafka.properties.*} and
 *   {@code spring.kafka.producer.properties.*} overrides count, is a TLS protocol:
 *   {@code SASL_SSL} (MSK, IAM over TLS) or {@code SSL} (Strimzi mutual TLS). This service
 *   has no consumer.</li>
 * </ul>
 * One switch, {@code fintechbankx.tls.enforce}, true in application.yml; only the local
 * profile and explicit test configuration set it false. The Helm chart cannot set it, name
 * a profile or override these settings (its values guard refuses every such name).
 * Registered in META-INF/spring.factories, so the service, the "migrate" Job and every
 * {@code @SpringBootTest} of this module run it. The failure message names every offending
 * setting and the value found for the mode or protocol only; it never repeats a datasource
 * URL, which may carry credentials.
 */
public class TlsEnforcementInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String ENFORCE = "fintechbankx.tls.enforce";
    static final String DATASOURCE_URL = "spring.datasource.url";
    static final String HIKARI_JDBC_URL = "spring.datasource.hikari.jdbc-url";
    static final String FLYWAY_URL = "spring.flyway.url";
    static final String KAFKA_BOOTSTRAP_SERVERS = "spring.kafka.bootstrap-servers";
    static final String KAFKA_SECURITY_PROTOCOL = "spring.kafka.security.protocol";
    static final String REQUIRED_SSLMODE = "verify-full";
    /** Kafka protocols with TLS in transit; PLAINTEXT and SASL_PLAINTEXT are refused. */
    static final Set<String> TLS_KAFKA_PROTOCOLS = Set.of("SASL_SSL", "SSL");
    /** PgJDBC keys that bypass certificate or host name verification, or read TLS settings from pg_service.conf. */
    static final Set<String> VERIFICATION_BYPASS_KEYS =
            Set.of("sslfactory", "sslfactoryarg", "sslhostnameverifier", "sslpasswordcallback", "service");

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Environment environment = context.getEnvironment();
        if (!environment.getProperty(ENFORCE, Boolean.class, true)) {
            return;
        }
        List<String> problems = new ArrayList<>();
        checkJdbcUrl(DATASOURCE_URL, environment.getProperty(DATASOURCE_URL), true, problems);
        checkJdbcUrl(HIKARI_JDBC_URL, environment.getProperty(HIKARI_JDBC_URL), false, problems);
        checkJdbcUrl(FLYWAY_URL, environment.getProperty(FLYWAY_URL), false, problems);
        String bootstrapServers = environment.getProperty(KAFKA_BOOTSTRAP_SERVERS);
        if (bootstrapServers != null && !bootstrapServers.isBlank()) {
            checkKafkaTls(environment, problems);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(ENFORCE + "=true: " + String.join("; ", problems)
                    + "; only the local profile or test configuration may set " + ENFORCE + "=false");
        }
    }

    /** One JDBC URL, parsed the way PgJDBC parses it; the problems name the setting, never the URL. */
    static void checkJdbcUrl(String setting, String url, boolean required, List<String> problems) {
        if (url == null || url.isBlank()) {
            if (required) {
                problems.add(setting + " must carry sslmode=" + REQUIRED_SSLMODE + " (found no sslmode: it is not set)");
            }
            return;
        }
        List<String> bypass = new ArrayList<>();
        List<String> upperCaseSslmode = new ArrayList<>();
        List<String> modes = new ArrayList<>();
        for (String[] parameter : queryParameters(url)) {
            String key = parameter[0];
            if (VERIFICATION_BYPASS_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                bypass.add(key);
            }
            if (key.equals("sslmode")) {
                modes.add(parameter[1]);
            } else if (key.equalsIgnoreCase("sslmode")) {
                upperCaseSslmode.add(key);
            }
        }
        if (!bypass.isEmpty()) {
            problems.add(setting + " must not set " + String.join(", ", bypass)
                    + " (it can bypass certificate or host name verification)");
        }
        if (modes.size() > 1) {
            problems.add(setting + " sets sslmode " + modes.size() + " times; PgJDBC takes the last one (found sslmode="
                    + modes.get(modes.size() - 1) + "), exactly one sslmode=" + REQUIRED_SSLMODE + " is required");
        } else if (modes.isEmpty() && !upperCaseSslmode.isEmpty()) {
            problems.add(setting + " spells " + upperCaseSslmode.get(0) + " in upper case; PgJDBC reads keys case-sensitively"
                    + " and falls back to sslmode=prefer (found no sslmode), sslmode=" + REQUIRED_SSLMODE + " is required");
        } else if (modes.isEmpty()) {
            problems.add(setting + " must carry sslmode=" + REQUIRED_SSLMODE + " (found no sslmode)");
        } else if (!REQUIRED_SSLMODE.equals(modes.get(0))) {
            problems.add(setting + " must carry sslmode=" + REQUIRED_SSLMODE + " (found sslmode=" + modes.get(0) + ")");
        }
    }

    /**
     * The producer's effective security.protocol: spring.kafka.properties.* and spring.kafka.producer.properties.*
     * override spring.kafka.security.protocol, so the properties are built the way the client receives them.
     */
    static void checkKafkaTls(Environment environment, List<String> problems) {
        KafkaProperties kafka = Binder.get(environment).bind("spring.kafka", KafkaProperties.class).orElseGet(KafkaProperties::new);
        Object protocol = kafka.buildProducerProperties(null).get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG);
        String found = protocol == null ? "" : protocol.toString().trim().toUpperCase(Locale.ROOT);
        if (!TLS_KAFKA_PROTOCOLS.contains(found)) {
            problems.add(KAFKA_SECURITY_PROTOCOL + " (the producer's effective security.protocol, spring.kafka.properties"
                    + " and spring.kafka.producer.properties included) must be SASL_SSL (or SSL for Strimzi mutual TLS) because "
                    + KAFKA_BOOTSTRAP_SERVERS + " is set (found " + (found.isEmpty() ? "none" : found) + ")");
        }
    }

    /** Every sslmode value of a JDBC URL, in order, read the way PgJDBC reads the query (case-sensitive key). */
    static List<String> sslmodes(String url) {
        List<String> modes = new ArrayList<>();
        for (String[] parameter : queryParameters(url)) {
            if (parameter[0].equals("sslmode")) {
                modes.add(parameter[1]);
            }
        }
        return modes;
    }

    /**
     * The query parameters as PgJDBC's Driver.parseURL reads them: the text after the first '?', split on '&',
     * key = text before the first '=' (not decoded), value = the rest, percent-decoded. A value that fails to
     * decode is kept as written (the driver would refuse the URL itself).
     */
    private static List<String[]> queryParameters(String url) {
        List<String[]> parameters = new ArrayList<>();
        if (url == null) {
            return parameters;
        }
        int query = url.indexOf('?');
        if (query < 0) {
            return parameters;
        }
        for (String token : url.substring(query + 1).split("&")) {
            if (token.isEmpty()) {
                continue;
            }
            int equals = token.indexOf('=');
            String key = equals < 0 ? token : token.substring(0, equals);
            String value = equals < 0 ? "" : token.substring(equals + 1);
            parameters.add(new String[] {key, decode(value).trim()});
        }
        return parameters;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return value;
        }
    }
}
