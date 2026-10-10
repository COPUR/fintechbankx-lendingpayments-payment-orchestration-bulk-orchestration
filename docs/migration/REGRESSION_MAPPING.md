# Regression mapping: monolith bulk payments -> svc-pay-bulk-orchestration

Status: **Proposed** (not merged, not deployed).

- Matrix row: **LP-09** (`docs/alignment/monolith-to-repo-alignment.csv` in the enterprise-architecture repository;
  the row's text was not available locally and is cited from the review, not re-read).
- Parity: no LP-09 harness run exists yet; cutover (runbook section 3, step 8) does not start until one passes with
  0 regressions and every difference observed is listed below. Record the run id `<run>-<sha7>` here and in the
  runbook's evidence links.

Source: `enterprise-loan-management-system`, module `open-finance-context`, package
`com.enterprise.openfinance.bulkpayments` (controller `BulkPaymentsController`,
service `BulkPaymentService`). The monolith kept every bulk file in memory.

## Endpoints

| Monolith (method, path / class.method) | New service | Same behaviour | Intentional differences |
|---|---|---|---|
| `POST /open-finance/v1/file-payments` / `BulkPaymentsController.uploadFile` | `POST /open-finance/v1/file-payments` | Request body `Data{ConsentId, FileName, FileContent, FileHash, IntegrityMode}` plus a new required `Currency`, headers, 202 + `Location`, `X-OF-Idempotency` HIT/MISS, validation messages (Empty Payload, Payload Too Large, Integrity Failure, Schema Validation Failed) | TPP comes from the access token's `azp` (a `tpp_id` claim is ignored); a different `x-fapi-financial-id` is 403 (monolith trusted the header, default `UNKNOWN_TPP`). Consent must be `AUTHORIZED` and is read from the consent service (503 `CONSENT_SERVICE_UNAVAILABLE` when it is down). Idempotency is stored in Postgres per TPP and holds under concurrent uploads. A key is never reusable: after any time it answers with its original file (202 HIT), and another body under it is 409 (the monolith let an expired key start a new file). The replay is looked up before the remote consent read, so a retry of an accepted upload is still 202 HIT after its consent expired or was revoked, unless the file is already `Stopped` (its consent was found unusable): that retry is refused from local state with the 403 below, without calling the consent service. The monolith (`ffe83796`) checked the consent before the replay on every upload, so any retry under an expired consent was 403 `Consent expired`; it had no revocation, cancel or Stopped state. The difference that remains is an expired consent on a file that is not Stopped yet (the expiry is not stored locally): 202 HIT here, 403 in the monolith. Every consent the caller may not use (unknown, another TPP, not authorised, expired, out of scope) gets the same 403 body, `Consent not usable for this request`. Items go to their own table. `Currency` (ISO 4217) is required and never defaulted: missing is 400 `currency is required`, an unknown code or one without minor units (XAU) is 400 `Unsupported Currency`, an amount with more decimals than the currency allows (AED 2500.255, JPY 10.5) or with more than 15 integer digits, and a file total beyond 15 integer digits, is 400 `Schema Validation Failed` (the monolith accepted the first and rounded it; the second was a 500 at insert). The monolith had no currency at all. A consent authorises one file: the upload binds it in `bulk_consent_binding` (file id, file hash, item count, control sum, currency), because the consent read carries no file limits. |
| `GET /open-finance/v1/file-payments/{fileId}` / `getFileStatus` | `GET /open-finance/v1/file-payments/{fileId}` | 200 / 404, ETag + 304 | Another TPP's file id is 404 `NOT_FOUND` "Bulk file not found", the same body as an unknown id (ADR-025 item 5, adr-runbooks a7fe0c2); the monolith answered 403 `Consent participant mismatch`, which told a TPP the id exists. `Status` values are `Processing`, `Validated`, `Rejected` and `Stopped`. The consent is read again before each processing batch; if it is no longer usable (revoked, expired, gone, out of scope) the file becomes `Stopped`, its remaining items are never released and `Payments.BulkFile.Rejected.v1` is published with `reason` `CONSENT_NOT_USABLE`; its report then shows every item `Rejected` with `ErrorMessage` `Consent not usable` and `AcceptedCount` 0. The consent is read with no database transaction open, before the batch claims the file (`FOR UPDATE SKIP LOCKED`). A consent-service outage only delays the batch. The monolith never re-checked the consent after upload. The monolith reported `Completed` or `PartiallyAccepted` after a few polls, although no payment was made. Now a file whose items are all validated and at least one accepted is `Validated` until a hand-off to initiation-settlement exists (open question). |
| | | | The GET is read-only. The monolith advanced processing on every poll (`statusPollsToComplete`); now a scheduled processor moves files forward in bounded batches. The ETag is derived from `processedCount` instead of `pollCount`. |
| `GET /open-finance/v1/file-payments/{fileId}/report` / `getFileReport` | `GET /open-finance/v1/file-payments/{fileId}/report` | Same body, ETag + 304, 404 | Another TPP's file id is 404 "Bulk file not found", like an unknown id (monolith: 403). Built from the stored items. Item `Amount` is written at the currency's minor units (JPY `1500`, KWD `1.234`) and each item has `Currency`; the monolith rounded to two decimals with HALF_UP. Only terminal reports are cached, so a cached report never goes stale. |
| `BulkPaymentsController.validateSecurityHeaders` (prefix check of `Authorization`) | Spring Security resource server | `DPoP` and `X-FAPI-Interaction-ID` headers still required | The JWT is now validated (signature, issuer, `aud` = `svc-pay-bulk-orchestration`, realm roles). A missing or invalid token is 401 (monolith: 400 or accepted). DPoP is enforced: `Authorization: DPoP`, a verified proof (htm, htu, iat, ath, single-use jti) whose key matches `cnf.jkt`; a Bearer token or a bad proof is 401 (monolith only checked that the header was present). |
| `InMemoryBulkConsentAdapter` | `HttpBulkConsentAdapter`: `GET /api/v1/consents/{id}` on consent-authorization-service (client credentials) | Consent checks for participant and expiry | The required scope is `INITIATEBULKPAYMENTS`, the consent service's name (the monolith checked a made-up `bulk-payment` scope that the consent service never issues). The `usable` flag decides; a consent that is not usable is refused (403). A consent-service outage or a response without `usable` is 503. |
| (none) | Events `Payments.BulkFile.Accepted.v1` and `Payments.BulkFile.Rejected.v1` on the aggregate topic `evt.pay.bulk.v1` through the transactional outbox | - | New. The monolith published no bulk events. `Payments.BulkFile.Completed.v1` is in the contract but not emitted: no item reaches initiation-settlement yet. |

## Status codes

| Case | Monolith | New |
|---|---|---|
| No / invalid token | 400 (missing header) or accepted | 401 |
| Another TPP's file id (status, report) | 403 `Consent participant mismatch` | 404 `NOT_FOUND` "Bulk file not found", identical to an unknown id (ADR-025 item 5) |
| `x-fapi-financial-id` header naming a TPP other than the token's | 403 only when the header named another TPP | 403 |
| Retry (same key and body) of an upload whose file is `Stopped` | n/a (no Stopped state; any retry under an expired consent was 403 `Consent expired`) | 403 "Consent not usable for this request", decided from local state |
| Retry (same key and body) after the consent expired, file not `Stopped` | 403 `Consent expired` (consent checked before the replay) | 202 HIT (replay ahead of the remote consent read; review 5459741793 minor 1) |
| A consent id in the upload body the caller may not use (another TPP's included) | 403 with a message per cause | 403 "Consent not usable for this request" (one body; ADR-025 a7fe0c2 is silent on request-body consent ids, so 403 is kept) |
| Consent service unavailable | n/a (in memory) | 503 |
| Malformed JSON body | 500 | 400 |
| A second file on a consent that already authorised a file (also two concurrent uploads) | accepted (one consent could carry any number of files) | 409 `CONSENT_ALREADY_USED`; a replay of the first upload with its idempotency key is still 202 HIT |
| Missing or unsupported `Currency`, or an amount finer than its minor units | n/a (no currency) | 400 |
| Bearer token, or missing / invalid / replayed DPoP proof | accepted when the DPoP header was non-empty | 401 `INVALID_DPOP_PROOF` |
| Any route outside `/open-finance/v1/file-payments/**` | 404 | 401/403 (denied by default) |

## Not applicable

- Credit API: bulk payments have no credit / limit call in the monolith or here.

## Evidence

The scenarios from the seed's uncompiled `integrationTest` and `functionalTest` sources are ported to
`open-finance-bootstrap/src/test/java/com/enterprise/openfinance/bulkpayments/BulkOrchestrationServiceIT.java`,
which runs against Postgres when `TEST_DB_URL` is set (28 tests, plus 3 in `DatabaseMigrationIT`).

Tests added for the review rounds (head `d1b3872`, local only):

| Behaviour | Test |
|---|---|
| Replay before the consent check | `BulkOrchestrationServiceIT.aRetryOfAnAcceptedUploadIsReplayedAfterItsConsentExpired` |
| Keys never expire (`V13` drops `expires_at`) | `BulkOrchestrationServiceIT.anIdempotencyKeyIsNeverReusableEvenDaysLater`, `theIdempotencyTableKeepsKeysForGood` |
| Stopped file report | the IT stop test asserts `Rejected` / `Consent not usable` / `AcceptedCount` 0 |
| Consent read outside the batch transaction; replicas skip a locked file | `BulkPaymentServiceTest.theConsentIsReadBeforeTheBatchTransactionAndTheBatchRunsInsideOne`, `BulkOrchestrationServiceIT.twoProcessorsNeverClaimTheSameFile` |
| Relay sends with no transaction open; one relayer | `OutboxRelayTest.noTransactionIsOpenWhileASendBlocksAndOutcomesAreRecordedInShortTransactions`, `PostgresSessionRelayLockTest`, `BulkOrchestrationServiceIT.whileASendBlocksNoTransactionIsOpenAndAnotherReplicaRelaysNothing`, `KafkaProducerConfigValidityTest.sendBlocksForLessThanTheRelaySendTimeout` |
| Parked counter and gauge (`outbox_parked_events_total{exception}`, `outbox_parked_rows`) | `OutboxRelayTest`, `OutboxMetricsPrometheusTest`, `BulkOrchestrationServiceIT.anOperatorParkIsCountedOnceByTheRelay` |
| Runtime role is DML-only; Flyway as owner | `BulkOrchestrationServiceIT.theRuntimeRoleCannotRunDdlOrDeleteFilesItemsOrBindings`, `DatabaseMigrationIT`, `FlywayRoleConfigurationTest` |
| Consent provider view (consent-auth #13 at `fdaf8e7`) | `ConsentServiceViewContractTest` |
