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
`outbox_pending_events`). The catalog PR for `evt.pay.bulk.file-accepted.v1`,
`evt.pay.bulk.file-completed.v1` and `evt.pay.bulk.file-rejected.v1` (AsyncAPI
`api/asyncapi/svc-pay-bulk-orchestration.yaml`) is **pending**. After it merges and the platform creates the topics
(the service never auto-creates them), set `OUTBOX_RELAY_ENABLED=true`. Watch `outbox_parked_events`:
a row that fails `max-attempts` (10) times is PARKED and does not block later rows.
This service consumes no topics. If a consumer is added it dead-letters to `evt.pay.bulk.dlq.v1`.

## 5. Mesh and consent prerequisites
The chart ships a NetworkPolicy and no Istio policy. The mesh owners must ALLOW:
- inbound to this service on 8080 from `cluster.local/ns/istio-ingress/sa/istio-ingressgateway`;
- inbound to the consent service from `cluster.local/ns/payments/sa/payment-bulk-orchestration-service`.

**Gap:** the consent service currently requires a `DPoP` Authorization scheme and has no
service-to-service read. This service calls `GET /open-finance/v1/consents/{id}` with a Bearer client-credentials
token. Until the consent owner adds that path, uploads fail closed with 503.

## 6. Cutover and rollback
Cutover is routing only: point `/open-finance/v1/file-payments/**` at this service at the ingress.
To roll back, route back to the monolith. No data has to flow back because the monolith keeps nothing.
Files accepted by the new service stay readable here.
