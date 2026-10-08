package com.enterprise.openfinance.bulkpayments;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka refuses to build a producer unless delivery.timeout.ms >= linger.ms + request.timeout.ms,
 * and the relay must wait longer than delivery.timeout.ms so a send is never abandoned while
 * the producer is still retrying it.
 */
class KafkaProducerConfigValidityTest {

    private static final int KAFKA_DEFAULT_REQUEST_TIMEOUT_MS = 30_000;

    private final Properties yaml = load();

    @Test
    void deliveryTimeoutCoversLingerPlusRequestTimeout() {
        long delivery = producerLong("delivery.timeout.ms", 120_000);
        long linger = producerLong("linger.ms", 5);
        long request = producerLong("request.timeout.ms", KAFKA_DEFAULT_REQUEST_TIMEOUT_MS);

        assertThat(delivery).isGreaterThanOrEqualTo(linger + request);
    }

    @Test
    void relayWaitsLongerThanTheProducerDeliveryTimeout() {
        long delivery = producerLong("delivery.timeout.ms", 120_000);
        Duration relayWait = DurationStyle.detectAndParse(
                yaml.getProperty("openfinance.bulkpayments.outbox.relay.send-timeout"));

        assertThat(relayWait.toMillis()).isGreaterThan(delivery);
    }

    private long producerLong(String key, long kafkaDefault) {
        String value = yaml.getProperty("spring.kafka.producer.properties." + key);
        return value == null ? kafkaDefault : Long.parseLong(value);
    }

    private static Properties load() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        return factory.getObject();
    }
}
