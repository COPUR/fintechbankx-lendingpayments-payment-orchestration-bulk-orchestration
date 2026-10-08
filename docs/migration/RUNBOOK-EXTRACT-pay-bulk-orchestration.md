# Runbook: extract bulk payments from the monolith (svc-pay-bulk-orchestration)

Template: adr-runbooks `docs/transformation-outputs/migration-runbook-template.md`.

## Document Control

- Runbook ID: `RUNBOOK-EXTRACT-pay-bulk-orchestration`
- Version: `v1.1` (Proposed)
- Owner Squad: Lending & Payments, bulk payments (service owner of `svc-pay-bulk-orchestration`)
- Change Window: to be agreed with the owner squad and the gateway owners; uploads are frozen during cutover
- Risk Tier: `High` (TPP-facing payment initiation API, new consent rules, first deployment of this service)

Status: **Proposed**. Nothing here has been executed against a shared environment.

## 1. Objective

Move the corporate bulk payment API (`POST /open-finance/v1/file-payments`, `GET .../{fileId}`,
`GET .../{fileId}/report`) from the monolith (`open-finance-context`, package `bulkpayments`) to
`svc-pay-bulk-orchestration` in namespace `payments`, service account `payment-bulk-orchestration-service`.

In scope: the upload, validation, bounded-batch processing, status and report; the consent check against
consent-authorization-service; the events `evt.pay.bulk.accepted.v1` and `evt.pay.bulk.rejected.v1`.

Out of scope: handing items to svc-pay-initiation-settlement (not built; files end in `Validated`, nothing is
paid, `evt.pay.bulk.completed.v1` is not emitted), data migration (none, see section 2).

Data: no backfill. The monolith kept files, items, reports and idempotency keys in memory only, with no table
and no migration, so there is nothing to copy.

## 2. Preconditions

General (template):
1. Source and target repositories exist and are accessible.
2. The target repo has `main/dev/staging` protections enabled.
3. Required CI checks are green (`./gradlew clean check` with `TEST_DB_URL`, the deployability gates).
4. OpenAPI (`api/openapi/bulk-orchestration-service.yaml`) and AsyncAPI
   (`api/asyncapi/svc-pay-bulk-orchestration.yaml`) are reviewed; the AsyncAPI catalog copy (asyncapi-catalog
   PR #13) is merged.
5. Rollback tags exist for the monolith release and for this service's first release.

Cross-repo prerequisites, in this order (each must be done before the next starts):

1. **consent-auth**: `GET /api/v1/consents/{id}` (ConsentServiceView with `usable`, scopes such as
   `INITIATEBULKPAYMENTS`) is merged and deployed by the consent owner, and its service-caller allow-list
   (`openfinance.consent.service-callers`) contains this service's client.
2. **Keycloak** (realm `fintechbankx`): confidential client `svc-pay-bulk-orchestration`, client credentials,
   realm role `service`, audiences `svc-pay-bulk-orchestration` and `svc-of-consent-authorization`. Its secret
   goes into `<env>/payment-bulk-orchestration-service/oidc-client` under `client_secret`.
3. **Mesh team** (request explicitly; the chart ships no Istio policy):
   - (a) gateway route `/open-finance/v1/file-payments/**` to this service. The route must pass
     `Authorization: DPoP ...` and the `DPoP` header unchanged and set `X-Forwarded-Proto/Host/Prefix` to the
     public URL (the DPoP `htu` is checked against it);
   - (b) ServiceEntry / egress for the datastores `[aurora-postgresql, msk]` under the REGISTRY_ONLY outbound
     policy (without it the DB readiness check fails and the relay cannot reach the brokers), and Aurora egress
     on 5432 in the mesh NetworkPolicy for every pod labelled
     `app.kubernetes.io/name=payment-bulk-orchestration-service`: the API pods (`app.kubernetes.io/component=api`)
     **and** the Flyway pre-install/pre-upgrade hook pod (`app.kubernetes.io/component=db-migration`, no Istio
     sidecar). Without it the hook Job cannot reach Aurora and every install or upgrade stops before the pods
     roll;
   - (c) callee ALLOW rules: inbound to this service on 8080 from
     `cluster.local/ns/istio-ingress/sa/istio-ingressgateway`; inbound to consent-authorization-service from
     `cluster.local/ns/payments/sa/payment-bulk-orchestration-service`.
4. **Platform**: Aurora PostgreSQL, the KMS key, the secrets `<env>/payment-bulk-orchestration-service/db-app`
   (runtime role), `<env>/payment-bulk-orchestration-service/db-migration` (schema owner, Flyway Job only) and
   `<env>/payment-bulk-orchestration-service/oidc-client`, the IRSA role and the MSK policy, from `deploy/terraform`
   (plan reviewed before anyone applies). ESO may read only `<env>/<service account>/`; the chart refuses any other
   key and any ExternalSecret whose `app.kubernetes.io/name` is not the service account. Then the DBA bootstrap
   below. `config.DB_URL` is the Terraform output `jdbc_url`
   (`sslmode=verify-full&sslrootcert=/etc/ssl/rds/global-bundle.pem`; the chart refuses anything else and mounts
   ConfigMap `rds-ca-bundle`, which trust-manager must have published in `payments`); `migration.remoteSecretName`
   is the output `migration_db_secret_name`.
   Pending on the platform side, not worked around here: microservice-base (terraform-modules, ref=main) still
   names its runtime secret `<env>-<slug>/runtime` and uses `timestamp()` in tags (terraform-modules #11).

   **Database roles**

   | Role | Secret | Used by | Rights |
   |---|---|---|---|
   | `payment_bulk_owner` | `<env>/payment-bulk-orchestration-service/db-migration` | Helm pre-install/pre-upgrade Job `payment-bulk-orchestration-service-db-migration` (image with `migrate`, `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD`), deleted with its ExternalSecret when it succeeds | owns `sc_pay_bulk_orchestration` and every table (DDL) |
   | `payment_bulk_app` (`DB_USERNAME`) | `<env>/payment-bulk-orchestration-service/db-app` | the service pods (`SPRING_FLYWAY_ENABLED=false`) | `V11`: USAGE on the schema; `bulk_file`, `bulk_item` SELECT/INSERT/UPDATE; `bulk_idempotency`, `bulk_consent_binding` SELECT/INSERT; `outbox_event` SELECT/INSERT/UPDATE/DELETE plus USAGE on its `created_seq` sequence; `dpop_proof_jti` SELECT/INSERT/DELETE. Default privileges give it DML (never TRUNCATE) on tables the owner creates later. No DDL, no Flyway history (`BulkOrchestrationServiceIT.theRuntimeRoleCannotRunDdlOrDeleteFilesItemsOrBindings`) |

   DBA bootstrap, once per environment, as the RDS master user (`master_user_secret_arn`), before the first
   install (Flyway does not create the schema, `create-schemas: false`):

   ```sql
   CREATE ROLE payment_bulk_owner LOGIN PASSWORD '<from a password generator>';
   CREATE ROLE payment_bulk_app LOGIN PASSWORD '<from a password generator>';
   GRANT CONNECT ON DATABASE db_pay_bulk_orchestration_<env> TO payment_bulk_owner, payment_bulk_app;
   REVOKE CREATE ON SCHEMA public FROM PUBLIC;
   CREATE SCHEMA sc_pay_bulk_orchestration AUTHORIZATION payment_bulk_owner;
   REVOKE ALL ON SCHEMA sc_pay_bulk_orchestration FROM PUBLIC;
   ```

   Then put `{"username","password"}` of each role into its secret (`aws secretsmanager put-secret-value`). The
   first install's pre-install Job runs V1 to V11 as the owner; V11 grants the runtime role. Rollback: uninstall
   the chart, then `DROP SCHEMA sc_pay_bulk_orchestration CASCADE` and recreate it as above.
5. **Smoke upload** in the target environment (section 3, step 6).
6. **Route switch** (section 3, step 8).

Known gap that blocks cutover: **consent binding to the authorised file.** A bulk consent should authorise one
specific file (hash, number of transactions, control sum). This service binds each consent to one file
(`bulk_consent_binding`, second file 409 `CONSENT_ALREADY_USED`), but consent-auth's view exposes none of the
authorised file fields, so the uploaded file cannot be checked against what the PSU approved. Unless
consent-auth exposes those fields (or the owner squad and compliance accept the gap in writing), cutover does
not go ahead.

## 3. Change Plan

| Step | Action | Owner | Validation | Rollback Trigger |
| --- | --- | --- | --- | --- |
| 1 | Freeze scope; confirm the known gap is closed or formally accepted | Bulk squad, compliance | Signed scope checklist | Gap neither closed nor accepted |
| 2 | Contracts merged (OpenAPI, AsyncAPI catalog PR #13) | Bulk squad, contracts | Contract tests green (`OpenApiContractTest`, `AsyncApiContractTest`, `ConsentServiceViewContractTest`) | Contract mismatch |
| 3 | Cross-repo prerequisites 1 to 4 (section 2), in order | Consent owner, identity, mesh, platform | Each one confirmed in its own repo / ticket | Any prerequisite missing |
| 4 | Deploy with `helm upgrade --install payment-bulk-orchestration-service deploy/helm/payment-bulk-orchestration-service -n payments -f values-<env>.yaml`; Flyway runs as the schema owner in the pre-install hook Job before the pods start | Bulk squad | Job succeeded; `flyway_schema_history` at V11; pods ready as `payment_bulk_app` | Pods not ready, migration Job failed (it stays for inspection; fix and re-run the upgrade) |
| 5 | Relay stays off (`OUTBOX_RELAY_ENABLED=false`) until the platform has created `evt.pay.bulk.accepted.v1` and `evt.pay.bulk.rejected.v1` (the service never creates topics); then enable it | Bulk squad, platform | `outbox_pending_events` drains; `outbox_send_failures_total` flat | any increase of `outbox_parked_events_total` |
| 6 | Smoke upload through the gateway with a test TPP (DPoP token, `INITIATEBULKPAYMENTS` consent, `Currency`) | Bulk squad | 202, then `Validated`, report figures equal the file; Accepted event on Kafka | Any 5xx, 401 on a valid proof, 503 from the consent check |
| 7 | Freeze uploads on the monolith and drain it: wait until every monolith file is terminal or past its poll window | Bulk squad | No monolith file in a non-terminal state still being polled | Drain does not finish in the window |
| 8 | Switch the gateway route `/open-finance/v1/file-payments/**` to this service | Mesh team | Smoke repeated; SLO checks below | See rollback triggers below |

Rollback triggers after step 8 (any one, sustained for 5 minutes):
- HTTP 5xx above 1 % of requests;
- 503 `CONSENT_SERVICE_UNAVAILABLE` above 0.5 % of uploads;
- 401 `invalid_dpop_proof` above 5 % of requests (likely a gateway header or `htu` problem);
- any increase of `outbox_parked_events_total` (a row parked, by the relay or by an operator).

## 4. Observability Gate

1. Trace propagation: `x-fapi-interaction-id` is echoed, logged (`correlationId` in the log pattern) and carried
   on every event as `correlationId`; `traceparent` is forwarded on events.
2. Structured logs reach the central sink; no payee IBAN, customer id or file content is logged.
3. Metrics (Prometheus, tag `service=svc-pay-bulk-orchestration`): request rate, latency and status codes;
   `outbox_pending_events`, `outbox_oldest_pending_age_seconds`, `outbox_parked_rows` (rows parked now),
   `outbox_parked_events_total{exception}` (every park; `exception="OperatorPark"` for a park done with the SQL below),
   `outbox_send_failures_total{exception}`.
4. Alerts in place before step 8: the four rollback triggers above, plus `outbox_oldest_pending_age_seconds`
   above 300 s (pages the owner squad) and any increase of `outbox_send_failures_total`.

### Relay failure policy
ADR-021 decision 4 (adr-runbooks #10 at 421f7b5 and the ruling e6dd76a):
- Payload errors that can never succeed for the row (RecordTooLargeException, SerializationException,
  InvalidTopicException) park the row at once with `parked_reason = 'PAYLOAD_ERROR'` and the batch continues. A
  parked row keeps the rest of its bulk file blocked: later events of that file stay PENDING until the parked row is
  replayed, so a file's events never go out of order. Other files continue.
- Every other failure (retriable Kafka errors and timeouts, a missing topic or partition
  (`UnknownTopicOrPartitionException`, or a metadata `TimeoutException` while the topic does not exist; governance
  ruling: back off, alert, resume once the topic exists), TopicAuthorization, SASL/IAM authentication, a producer
  that cannot be built, anything unclassified) stops the batch without marking the row or anything after it. The
  relay backs off (5 s doubling to 5 min) and retries. Such a row is never parked automatically, however long the
  failure lasts; there is no time ceiling.
- `last_error`, logs and metric tags carry the exception class only, never record content or identifiers.
- `outbox_send_failures_total{exception="<class>"}` says where to look: IAM policy or topic ACL for
  `TopicAuthorizationException`/`SaslAuthenticationException`, brokers, egress or a topic not yet created for
  `TimeoutException`/`NetworkException`/`UnknownTopicOrPartitionException`.

Manual park (only when a row blocks the relay for a reason no fix will cure, decided by the owner squad). The
reason is required; the database refuses a park without one. The relay counts the park once on its next run
(`outbox_parked_events_total{exception="OperatorPark"}`):

```sql
UPDATE sc_pay_bulk_orchestration.outbox_event
SET status = 'PARKED', parked_at = now(), parked_reason = '<ticket>: <why this row cannot be published>'
WHERE event_id = '<event id>' AND status = 'PENDING';
```

Parked outbox events: find the cause in `parked_reason` and `last_error`, fix it (message size, topic), then replay:

```sql
SELECT event_id, created_seq, aggregate_id, topic, attempts, parked_reason, last_error, parked_at
FROM sc_pay_bulk_orchestration.outbox_event WHERE status = 'PARKED' ORDER BY created_seq;

-- Replaying the parked row unblocks the later events of its file.
UPDATE sc_pay_bulk_orchestration.outbox_event
SET status = 'PENDING', parked_at = NULL, parked_reason = NULL, park_counted = false, attempts = 0, last_error = NULL
WHERE event_id = '<event id>';
```

This service consumes no topics and declares no dead-letter topic of its own.

### Consent behaviour to watch
Consent reads go to `GET /api/v1/consents/{id}`
(`CONSENT_SERVICE_BASE_URL=http://consent-authorization-service.open-finance.svc.cluster.local:8080`) with this
service's client-credentials token. `usable` decides; the required scope is `INITIATEBULKPAYMENTS`. 404 means no
consent (403); 5xx, a timeout or a response without `usable` is 503 and the upload fails closed. The consent is
read again before each processing batch: a consent that stopped being usable stops the file (`Stopped`,
Rejected event with reason `CONSENT_NOT_USABLE`); a consent-service outage only delays processing.

## 5. Rollback Plan

1. Route `/open-finance/v1/file-payments/**` back to the monolith at the gateway.
2. Stop processing and publishing here: set `BULK_PROCESSING_ENABLED=false` and `OUTBOX_RELAY_ENABLED=false` (helm
   upgrade with the previous values), so no further batch is released and no further event is published.
3. Files uploaded to this service during the cutover are not visible through the monolith, which keeps nothing
   and cannot read this service's data. Tell the affected TPPs (list them from `bulk_file` where `created_at` is
   after the switch) which files must be uploaded again to the monolith.
4. Revert to the rollback tags if code is at fault; re-run the smoke test on the restored path.
5. Publish the incident and corrective actions within 24 h.

## 6. Acceptance Checklist

- [ ] Domain and application tests pass
- [ ] Integration tests pass against PostgreSQL (`TEST_DB_URL`); CI fails without a database
- [ ] Flyway runs as the schema owner in the hook Job; pods run as the DML-only runtime role (`DatabaseMigrationIT`, runtime-role IT)
- [ ] Contract compatibility pass (OpenAPI, AsyncAPI, consent provider contract)
- [ ] Security scan pass; DPoP enforced; Bearer tokens get 401
- [ ] SLO/SLA thresholds pass for one business day after step 8
- [ ] Known gap (consent binding to the authorised file) closed or accepted in writing
- [ ] Audit evidence stored

## 7. Evidence Links

- PR links: pending (nothing is merged)
- Pipeline runs: pending
- Dashboard snapshots: pending
- Incident/rollback references: none
