package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/** Row of sc_pay_bulk_orchestration.bulk_file. Mapped to and from the domain by {@link BulkFilePersistenceMapper}. */
@Entity
@Table(name = "bulk_file")
public class BulkFileJpaEntity {

    @Id
    @Column(name = "file_id", length = 64)
    private String fileId;

    @Column(name = "consent_id", nullable = false, length = 128, updatable = false)
    private String consentId;

    @Column(name = "tpp_id", nullable = false, length = 128, updatable = false)
    private String tppId;

    @Column(name = "idempotency_key", nullable = false, length = 160, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 512, updatable = false)
    private String requestHash;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "integrity_mode", nullable = false, length = 32, updatable = false)
    private String integrityMode;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "target_status", nullable = false, length = 32, updatable = false)
    private String targetStatus;

    @Column(name = "processed_count", nullable = false)
    private int processedCount;

    @Column(name = "total_count", nullable = false, updatable = false)
    private int totalCount;

    @Column(name = "accepted_count", nullable = false, updatable = false)
    private int acceptedCount;

    @Column(name = "rejected_count", nullable = false, updatable = false)
    private int rejectedCount;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal totalAmount;

    @Column(name = "accepted_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal acceptedAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected BulkFileJpaEntity() {
    }

    @SuppressWarnings("java:S107")
    BulkFileJpaEntity(String fileId, String consentId, String tppId, String idempotencyKey, String requestHash,
                      String fileName, String integrityMode, String targetStatus, int totalCount,
                      int acceptedCount, int rejectedCount, BigDecimal totalAmount, BigDecimal acceptedAmount,
                      Instant createdAt) {
        this.fileId = fileId;
        this.consentId = consentId;
        this.tppId = tppId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.fileName = fileName;
        this.integrityMode = integrityMode;
        this.targetStatus = targetStatus;
        this.totalCount = totalCount;
        this.acceptedCount = acceptedCount;
        this.rejectedCount = rejectedCount;
        this.totalAmount = totalAmount;
        this.acceptedAmount = acceptedAmount;
        this.createdAt = createdAt;
    }

    /** Copies the fields the aggregate may change after creation. */
    void applyProgress(String status, int processedCount, Instant processedAt) {
        this.status = status;
        this.processedCount = processedCount;
        this.processedAt = processedAt;
    }

    public String getFileId() { return fileId; }
    public String getConsentId() { return consentId; }
    public String getTppId() { return tppId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getFileName() { return fileName; }
    public String getIntegrityMode() { return integrityMode; }
    public String getStatus() { return status; }
    public String getTargetStatus() { return targetStatus; }
    public int getProcessedCount() { return processedCount; }
    public int getTotalCount() { return totalCount; }
    public int getAcceptedCount() { return acceptedCount; }
    public int getRejectedCount() { return rejectedCount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BigDecimal getAcceptedAmount() { return acceptedAmount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }
    public long getVersion() { return version; }
}
