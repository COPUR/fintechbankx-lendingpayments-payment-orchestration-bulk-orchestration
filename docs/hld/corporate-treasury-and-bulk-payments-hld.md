# Use Case 05 & 08: Corporate Treasury & Bulk Payments

## 1. High-Level Design (HLD)

### Architecture Overview

Corporate services are volume-heavy and combine asynchronous file processing with API-driven control and monitoring.

* **Pattern:** Asynchronous batch orchestration + event sourcing for lifecycle traceability.
* **Decoupling:** `BulkFileHandler` accepts and validates envelope metadata quickly (`202 Accepted`), then parsing/execution runs asynchronously.
* **Distributed Principle:** Partition execution by `corporate_id`/`file_id` to avoid shard hotspots and support parallel processing.

### Components

1. **Secure File Gateway (SFG):** Multipart ingestion, mTLS termination, malware scanning.
2. **Validation Engine:** Schema (PAIN.001/CSV/JSON) and business-rule validation.
3. **Approval Orchestrator:** Enforces maker-checker and multi-signatory policies.
4. **Batch Processor:** Splits file into payment instructions and dispatches workers.
5. **Treasury Adapter:** Integration with ERP/H2H and payment rails.
6. **Webhook/Callback Service:** Sends status updates and completion reports to corporate TPP.
7. **Kafka Event Bus + Redis:** Workflow events and short-lived workflow state cache.

---

## 2. Functional Requirements

1. **Multi-Authorization:** Hold resource in `PendingAuthorization` until required signatures are complete.
2. **Sweeping Visibility:** Return real-time liquidity view across physical and virtual accounts.
3. **Bulk Integrity Modes:** Support client-configured `PARTIAL_REJECTION` or `FULL_REJECTION`.
4. **Virtual Account Mapping:** Reconcile transactions to vIBAN structures.
5. **Idempotent Upload:** Repeated file upload with same key/hash must not duplicate processing.

## 3. Service Level Implementation (NFRs)

* **Throughput:** Support 100 concurrent uploads of up to 10MB each.
* **API Acknowledgement:** < 500ms.
* **Validation SLA:** < 60 seconds for standard 10MB files.
* **Availability:** 99.99% for upload and status APIs.
* **Resilience:** Resumable/chunked upload and restartable workers.
* **Security:** OAuth 2.1 + FAPI 2.0 + DPoP + mTLS for all API traffic.

---

## 4. API Signatures

### Upload Bulk Payment File

```http
POST /open-banking/v1/payment-consents/{ConsentId}/file
Authorization: DPoP <access-token>
DPoP: <dpop-proof-jwt>
X-FAPI-Interaction-ID: <UUID>
X-Idempotency-Key: <UUID>
Content-Type: multipart/form-data; boundary=---boundary
```

### Get Bulk File Status Report

```http
GET /open-banking/v1/file-payments/{FilePaymentId}/report
Authorization: DPoP <access-token>
DPoP: <dpop-proof-jwt>
X-FAPI-Interaction-ID: <UUID>
```

### Get Corporate Accounts (Including Virtual Accounts)

```http
GET /open-banking/v1/corporate/accounts?includeVirtual=true
Authorization: DPoP <access-token>
DPoP: <dpop-proof-jwt>
X-FAPI-Interaction-ID: <UUID>
```

---

## 5. Database Design (Project-Aligned Persistence)

**System of Record:** PostgreSQL  
**Cache:** Redis  
**Analytics:** MongoDB silver copy

**Table: `corporate_bulk.bulk_files`**

* **PK:** `file_id`
* **Fields:** `corporate_id`, `file_hash`, `status`, `total_amount`, `total_count`, `rejection_mode`, `created_at`

**Table: `corporate_bulk.bulk_items`**

* **PK:** `item_id`
* **FK:** `file_id`
* **Fields:** `instruction_id`, `payee_ref`, `amount`, `currency`, `status`, `error_message`
* **Indexing:** `(file_id, status)`, `(corporate_id, created_at)` through join/materialized views.

**Table: `corporate_bulk.corporate_approvals`**

* **PK:** `approval_id`
* **FK:** `resource_id` (file or payment)
* **Fields:** `required_signatures`, `collected_signatures`, `status`, `approved_at`

**Audit Invariant**

* Every status change writes immutable audit entries with actor, timestamp, and interaction ID.

---

## 6. Postman Collection Structure

* **Folder:** `Corporate - Bulk`
* `POST /payment-consents/{ConsentId}/file`
* `GET /file-payments/{id}/report` (poll until `COMPLETED`)
* `GET /file-payments/{id}/report` (validate error details)

* **Folder:** `Corporate - Approval`
* `POST /bulk/{id}/approvals`
* `GET /bulk/{id}/approvals`
