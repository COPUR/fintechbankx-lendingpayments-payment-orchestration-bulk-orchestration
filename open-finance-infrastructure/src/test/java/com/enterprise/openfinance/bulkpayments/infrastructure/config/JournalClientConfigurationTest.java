package com.enterprise.openfinance.bulkpayments.infrastructure.config;

import com.enterprise.openfinance.bulkpayments.domain.port.out.JournalEntryPort;
import com.enterprise.openfinance.bulkpayments.infrastructure.ledger.HttpJournalEntryAdapter;
import com.enterprise.openfinance.bulkpayments.infrastructure.ledger.InMemoryJournalEntryAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class JournalClientConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(ctx -> ctx.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(JournalClientConfiguration.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(OAuth2AuthorizedClientManager.class, () -> request -> null);

    @Test
    void httpIsTheDefaultAdapter() {
        context.withPropertyValues("journal.base-url=http://accounts-ledger-service.corebanking.svc.cluster.local:8080")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(JournalEntryPort.class);
                    assertThat(ctx.getBean(JournalEntryPort.class)).isInstanceOf(HttpJournalEntryAdapter.class);
                    assertThat(ctx).doesNotHaveBean(InMemoryJournalEntryAdapter.class);
                });
    }

    @Test
    void httpAdapterReadsItsTimeoutsAsDurations() {
        context.withPropertyValues("journal.adapter=http",
                        "journal.base-url=http://accounts-ledger-service.corebanking.svc.cluster.local:8080",
                        "journal.connect-timeout=PT2S", "journal.read-timeout=PT3S")
                .run(ctx -> assertThat(ctx.getBean(JournalEntryPort.class)).isInstanceOf(HttpJournalEntryAdapter.class));
    }

    @Test
    void inMemoryAdapterIsOptIn() {
        context.withPropertyValues("journal.adapter=in-memory")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(JournalEntryPort.class);
                    assertThat(ctx.getBean(JournalEntryPort.class)).isInstanceOf(InMemoryJournalEntryAdapter.class);
                });
    }

    @Test
    void anUnknownAdapterNameLeavesNoPort() {
        context.withPropertyValues("journal.adapter=mock")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(JournalEntryPort.class));
    }
}
