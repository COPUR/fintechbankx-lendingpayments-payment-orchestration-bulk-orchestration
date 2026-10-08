# Regression mapping: monolith bulk payments -> svc-pay-bulk-orchestration

Status: **Proposed** (not merged, not deployed).

Source: `enterprise-loan-management-system`, module `open-finance-context`, package
`com.enterprise.openfinance.bulkpayments` (controller `BulkPaymentsController`,
service `BulkPaymentService`). The monolith kept every bulk file in memory.

## Endpoints

| Monolith (method, path / class.method) | New service | Same behaviour | Intentional differences |
|---|---|---|---|
| `POST /open-finance/v1/file-payments` / `BulkPaymentsController.uploadFile` | `POST /open-finance/v1/file-payments` | Request body `Data{ConsentId, FileName, FileContent, FileHash, IntegrityMode}`, headers, 202 + `Location`, `X-OF-Idempotency` HIT/MISS, validation messages (Empty Payload, Payload Too Large, Integrity Failure, Schema Validation Failed) | TPP comes from the access token (`tpp_id`, else `azp`); a different `x-fapi-financial-id` is 403 (monolith trusted the header, default `UNKNOWN_TPP`). Consent must be `AUTHORIZED` and is read from the consent service (503 `CONSENT_SERVICE_UNAVAILABLE` when it is down). Idempotency is stored in Postgres per TPP and holds under concurrent uploads. Items go to their own table. |
| `GET /open-finance/v1/file-payments/{fileId}` / `getFileStatus` | `GET /open-finance/v1/file-payments/{fileId}` | 200 / 404 / 403 for another TPP, ETag + 304 | The GET is read-only. The monolith advanced processing on every poll (`statusPollsToComplete`); now a scheduled processor moves files forward in bounded batches. The ETag is derived from `processedCount` instead of `pollCount`. |
| `GET /open-finance/v1/file-payments/{fileId}/report` / `getFileReport` | `GET /open-finance/v1/file-payments/{fileId}/report` | Same body, ETag + 304, 404 / 403 | Built from the stored items. Only terminal reports are cached, so a cached report never goes stale. |
| `BulkPaymentsController.validateSecurityHeaders` (prefix check of `Authorization`) | Spring Security resource server | `DPoP` and `X-FAPI-Interaction-ID` headers still required | The JWT is now validated (signature, issuer, `aud` = `svc-pay-bulk-orchestration`, realm roles). A missing or invalid token is 401 (monolith: 400 or accepted). DPoP is enforced: `Authorization: DPoP`, a verified proof (htm, htu, iat, ath, single-use jti) whose key matches `cnf.jkt`; a Bearer token or a bad proof is 401 (monolith only checked that the header was present). |
| `InMemoryBulkConsentAdapter` | `HttpBulkConsentAdapter` (client credentials) | Consent checks for participant, expiry and `bulk-payment` scope | Revoked or not-authorised consents are refused (403). |
| (none) | Events `evt.pay.bulk.{accepted,completed,rejected}.v1` through the transactional outbox | - | New. The monolith published no bulk events. |

## Status codes

| Case | Monolith | New |
|---|---|---|
| No / invalid token | 400 (missing header) or accepted | 401 |
| Token for another TPP, or header/token mismatch | 403 only when the header named another TPP | 403 |
| Consent service unavailable | n/a (in memory) | 503 |
| Malformed JSON body | 500 | 400 |
| Bearer token, or missing / invalid / replayed DPoP proof | accepted when the DPoP header was non-empty | 401 `INVALID_DPOP_PROOF` |
| Any route outside `/open-finance/v1/file-payments/**` | 404 | 401/403 (denied by default) |

## Not applicable

- Currency: the bulk CSV carries no currency in either version (open question in the README).
- Credit API: bulk payments have no credit / limit call in the monolith or here.

## Evidence

The scenarios from the seed's uncompiled `integrationTest` and `functionalTest` sources are ported to
`open-finance-bootstrap/src/test/java/com/enterprise/openfinance/bulkpayments/BulkOrchestrationServiceIT.java`,
which runs against Postgres when `TEST_DB_URL` is set.
