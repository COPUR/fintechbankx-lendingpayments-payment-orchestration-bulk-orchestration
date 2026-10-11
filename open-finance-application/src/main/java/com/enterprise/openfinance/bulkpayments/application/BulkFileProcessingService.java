package com.enterprise.openfinance.bulkpayments.application;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkConsentContext;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkItemResult;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkSettings;
import com.enterprise.openfinance.bulkpayments.domain.port.in.ProcessBulkFilesUseCase;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkConsentPort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkFileEventPort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkFilePort;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkItemPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Walks processing files in bounded batches. Each call claims one file
 * (other replicas skip it), marks at most {@code processingBatchSize} of its
 * items processed and records the batch on the aggregate, all in one short
 * transaction. A crash rolls the whole batch back; the next run repeats it.
 *
 * Processing an item today means releasing it: its validation outcome is
 * final and the file's events tell downstream services which items to execute.
 * Per-item hand-off to svc-pay-initiation-settlement is not built yet.
 *
 * Before each batch the consent is read again, outside any transaction (no
 * connection or row lock is held while the consent service answers); the
 * verdict is passed into the batch transaction. A consent that is no longer
 * usable (revoked, expired, gone, another TPP's or out of scope) stops the
 * file (STOPPED, Rejected event with reason CONSENT_NOT_USABLE). A
 * consent-service outage throws before the transaction starts, so nothing is
 * written and the batch is retried; the file is never stopped on an outage.
 * Candidates are read without a lock; a file another replica holds is skipped
 * and the next candidate is tried.
 */
@Service
public class BulkFileProcessingService implements ProcessBulkFilesUseCase {

    /** Files looked at per call; a file held by another replica is skipped for the next. */
    static final int CANDIDATES = 5;

    private final BulkFilePort filePort;
    private final BulkItemPort itemPort;
    private final BulkConsentPort consentPort;
    private final BulkFileEventPort eventPublisher;
    private final BulkSettings settings;
    private final Clock clock;
    private final TransactionOperations transactions;

    public BulkFileProcessingService(BulkFilePort filePort,
                                     BulkItemPort itemPort,
                                     BulkConsentPort consentPort,
                                     BulkFileEventPort eventPublisher,
                                     BulkSettings settings,
                                     Clock clock,
                                     TransactionOperations transactions) {
        this.filePort = filePort;
        this.itemPort = itemPort;
        this.consentPort = consentPort;
        this.eventPublisher = eventPublisher;
        this.settings = settings;
        this.clock = clock;
        this.transactions = transactions;
    }

    @Override
    public int processNextBatch() {
        for (BulkFile candidate : filePort.findProcessing(CANDIDATES)) {
            Instant now = Instant.now(clock);
            boolean consentUsable = consentStillAuthorises(candidate, now); // no transaction open
            Integer processed = transactions.execute(status -> filePort.claimProcessing(candidate.fileId())
                    .map(file -> processBatch(file, consentUsable, now))
                    .orElse(null));
            if (processed != null) {
                return processed;
            }
        }
        return 0;
    }

    private int processBatch(BulkFile file, boolean consentUsable, Instant now) {
        if (!consentUsable) {
            file.stopBecauseConsentIsNotUsable(now);
            filePort.save(file);
            eventPublisher.publish(file, file.pullDomainEvents());
            return 0;
        }
        int limit = Math.min(settings.processingBatchSize(), file.remainingItems());
        List<BulkItemResult> batch = itemPort.findUnprocessed(file.fileId(), limit);
        if (batch.isEmpty()) {
            throw new IllegalStateException("Bulk file has " + file.remainingItems()
                    + " items left to process but none is stored");
        }

        int marked = itemPort.markProcessed(file.fileId(),
                batch.stream().map(BulkItemResult::lineNumber).toList(), now);
        if (marked != batch.size()) {
            throw new IllegalStateException("Bulk file batch: marked " + marked + " of "
                    + batch.size() + " items; batch rolled back");
        }

        file.recordProcessedBatch(batch.size(), now);
        filePort.save(file);
        eventPublisher.publish(file, file.pullDomainEvents());
        return batch.size();
    }

    private boolean consentStillAuthorises(BulkFile file, Instant now) {
        return consentPort.findById(file.consentId())
                .filter(consent -> consent.belongsToTpp(file.tppId()))
                .filter(BulkConsentContext::isAuthorized)
                .filter(consent -> consent.isActive(now))
                .filter(BulkConsentContext::allowsBulkInitiation)
                .isPresent();
    }
}
