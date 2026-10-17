package com.enterprise.openfinance.bulkpayments.infrastructure.config;

import com.enterprise.openfinance.bulkpayments.domain.exception.AccountsLedgerUnavailableException;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalEntry;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalLine;
import com.enterprise.openfinance.bulkpayments.domain.model.JournalSide;
import com.enterprise.openfinance.bulkpayments.domain.model.Money;
import com.enterprise.openfinance.bulkpayments.domain.port.out.JournalEntryPort;
import com.enterprise.openfinance.bulkpayments.infrastructure.ledger.HttpJournalEntryAdapter;
import com.enterprise.openfinance.bulkpayments.infrastructure.ledger.InMemoryJournalEntryAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalClientConfigurationTest {

    private final ApplicationContextRunner context = runnerWith(request -> null);

    private static ApplicationContextRunner runnerWith(OAuth2AuthorizedClientManager manager) {
        return new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(JournalClientConfiguration.class)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withBean(OAuth2AuthorizedClientManager.class, () -> manager);
    }

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
    void journalCallsAuthorizeWithTheAccountsLedgerRegistrationByDefault() {
        List<String> registrations = new ArrayList<>();
        runnerWith(request -> {
            registrations.add(request.getClientRegistrationId());
            return null;
        }).withPropertyValues("journal.base-url=http://accounts-ledger-service.corebanking.svc.cluster.local:8080")
                .run(ctx -> {
                    JournalEntry entry = new JournalEntry("BULK-FILE-1/ITEM-7", Currency.getInstance("AED"), null,
                            List.of(new JournalLine("acc-debit", JournalSide.DEBIT, Money.of("10.00", "AED")),
                                    new JournalLine("acc-credit", JournalSide.CREDIT, Money.of("10.00", "AED"))));
                    assertThatThrownBy(() -> ctx.getBean(JournalEntryPort.class).post(entry))
                            .isInstanceOf(AccountsLedgerUnavailableException.class);
                });
        assertThat(registrations).containsExactly("accounts-ledger");
    }

    @Test
    void anUnknownAdapterNameLeavesNoPort() {
        context.withPropertyValues("journal.adapter=mock")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(JournalEntryPort.class));
    }
}
