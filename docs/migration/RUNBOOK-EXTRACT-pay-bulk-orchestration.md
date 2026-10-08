# Runbook: extract bulk payments from the monolith (svc-pay-bulk-orchestration)

Status: **Proposed**. Nothing here has been executed against a shared environment.

## 1. Data: no backfill
The monolith (`open-finance-context`, package `bulkpayments`) kept files, items, reports and
idempotency keys in memory only. It had no table and no Flyway migration for them, so there is
nothing to copy. Files that are in flight in the monolith at cutover are lost when it restarts, as
they always were. Tell TPPs to re-poll or re-upload; the idempotency key is new in this service.

## 2. Infrastructure (platform squad, reviewed plan only)
1. `deploy/terraform`: copy `environments/<env>.tfvars.example`, then `terraform plan`. Review it before
   anyone applies. It creates Aurora PostgreSQL (`db_pay_bulk_orchestration_<env>`), a KMS key tagged
   `fintechbankx.io/secrets=true`, the app DB secret, `<env>/payment-bulk-orchestration-service/oidc-client`,
   the IRSA role and the inline MSK policy scoped to `evt.pay.bulk.*`.
   (TODO: switch to the msk-client-access module when it exists on main.)
2. Identity: create Keycloak client `svc-pay-bulk-orchestration` in realm `fintechbankx`
   (client credentials, audience mapper with the same value). Put its secret in the oidc-client secret
   under `client_secret`.
3. The consent service must allow this client. See section 5.

## 3. Deploy
`helm upgrade --install payment-bulk-orchestration-service deploy/helm/payment-bulk-orchestration-service -n payments -f values-<env>.yaml`
with `image.tag`, `config.DB_URL`, `config.KAFKA_BOOTSTRAP_SERVERS`, `config.OIDC_*`,
`externalSecret.remoteSecretName` and `externalSecret.serviceClientSecretName` set. A missing
required value fails the render. Flyway creates `sc_pay_bulk_orchestration` on first start.

## 4. Events: relay stays off until the topics exist
`OUTBOX_RELAY_ENABLED=false` by default. Events accumulate in `outbox_event` (gauge
`outbox_pending_events`). The catalog PR for `evt.pay.bulk.accepted.v1`,
`evt.pay.bulk.completed.v1` (contract only, not emitted yet) and `evt.pay.bulk.rejected.v1` (AsyncAPI
`api/asyncapi/svc-pay-bulk-orchestration.yaml`) is **pending**. After it merges and the platform creates the topics
(the service never auto-creates them), set `OUTBOX_RELAY_ENABLED=true`.

Relay failure policy (ADR-021 decision 4, adr-runbooks #10 at 421f7b5 and the ruling e6dd76a):
- Payload errors that can never succeed for the row (RecordTooLargeException, SerializationException,
  InvalidTopicException) park the row at once with `parked_reason = 'PAYLOAD_ERROR'` and the batch continues. A
  parked row keeps the rest of its bulk file blocked: later events of that file stay PENDING until the parked row is
  replayed, so a file's events never go out of order. Other files continue.
- Every other failure (retriable Kafka errors and timeouts, TopicAuthorization, SASL/IAM authentication, a producer
  that cannot be built, anything unclassified) stops the batch without marking the row or anything after it. The
  relay backs off (5 s doubling to 5 min) and retries. Such a row is never parked automatically, however long the
  failure lasts; there is no time ceiling.
- `last_error`, logs and metric tags carry the exception class only, never record content or identifiers.

Alerts (names agreed with platform):
- `outbox_oldest_pending_age_seconds{service="svc-pay-bulk-orchestration"}`: age of the oldest row waiting for the
  relay. This pages the owning squad (for example above 300 s), because non-payload failures never park.
- `outbox_send_failures_total{exception="<class>"}`: failed sends by exception class; the class says where to look
  (IAM policy or topic ACL for `TopicAuthorizationException`/`SaslAuthenticationException`, brokers or egress for
  `TimeoutException`/`NetworkException`).
- `outbox_parked_events`: any value above 0 needs an operator; that file's later events wait for the replay.
- `outbox_pending_events`: backlog.

Manual park (only when a row blocks the relay for a reason no fix will cure, decided by the owning squad). The
reason is required; the database refuses a park without one:

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
SET status = 'PENDING', parked_at = NULL, parked_reason = NULL, attempts = 0, last_error = NULL
WHERE event_id = '<event id>';
```
This service consumes no topics. If a consumer is added it dead-letters to `evt.pay.bulk.dlq.v1`.

## 5. Mesh and consent prerequisites
The chart ships a NetworkPolicy and no Istio policy. The mesh owners must ALLOW:
- inbound to this service on 8080 from `cluster.local/ns/istio-ingress/sa/istio-ingressgateway`;
- inbound to the consent service from `cluster.local/ns/payments/sa/payment-bulk-orchestration-service`.

Consent reads go to consent-authorization-service at `GET /api/v1/consents/{id}`
(`CONSENT_SERVICE_BASE_URL=http://consent-authorization-service.open-finance.svc.cluster.local:8080`). They use this
service's client-credentials Bearer token; Keycloak must add `aud` `svc-of-consent-authorization` to it. The
`usable` field decides. 404 means no consent. 5xx, a timeout or a response without `usable` gives 503, and the upload
fails closed. **Prerequisite:** that internal endpoint must be deployed by the consent owner. It is not in the local
consent repository checkout as of 2026-10-08.

## 6. Cutover and rollback
Cutover is routing only: point `/open-finance/v1/file-payments/**` at this service at the ingress.
To roll back, route back to the monolith. No data has to flow back because the monolith keeps nothing.
Files accepted by the new service stay readable here.
