# Deployment and well-architected notes (svc-pay-bulk-orchestration)

Status: **Proposed**.

| Pillar | What the code and chart do |
|---|---|
| Security | JWT resource server (issuer, signature, `aud`=`svc-pay-bulk-orchestration`); TPP ownership from the token; deny-by-default routes; non-root image (10001:10001); secrets via External Secrets from AWS Secrets Manager (KMS key, root-only policy); IRSA with no secret reads; TLS enforced on Aurora (`rds.force_ssl`) and verified by the pods (`sslmode=verify-full` against the platform RDS CA bundle, ConfigMap `rds-ca-bundle` mounted read-only at `/etc/ssl/rds`; the chart refuses any other `DB_URL`); Database roles: the pods hold a DML-only runtime role (`V11`), Flyway runs as the schema owner in a Helm hook Job; no chart NetworkPolicy by default (the mesh repo owns it; opt-in admits namespaces only). DPoP enforced on the TPP API (proof verified, jti replay guard in `dpop_proof_jti`, `cnf.jkt` match; `DPOP_REQUIRED`). |
| Reliability | Transactional outbox with relay (advisory lock; ADR-021 decision 4: only payload errors park a row, every other failure stops the batch and backs off, no attempt cap or time ceiling); consent reads fail closed (503); optimistic locking on `bulk_file`; `FOR UPDATE SKIP LOCKED` so replicas never process the same file; PDB minAvailable 2; probes on 8081. |
| Performance | Items in `bulk_item`, written over JDBC in chunks of 1000 and processed in batches of 500 (`BULK_PROCESSING_BATCH_SIZE`); HPA on CPU only; memory request = limit. |
| Operations | Scraped by the observability PodMonitor `fintechbankx-services` (pod label `fintechbankx.io/service-id`, `prometheus.io` annotations; no ServiceMonitor); Istio sidecar drains 55 s within a 65 s grace period; label `fintechbankx.io/squad: payments`; Prometheus on 8081 (`outbox_pending_events`, `outbox_parked_rows`, `outbox_oldest_pending_age_seconds`; counters `outbox_send_failures_total{exception}` and `outbox_parked_events_total{exception}`, operator parks counted once as `OperatorPark`); OTLP traces to `otel-collector.observability.svc.cluster.local:4318`; W3C `traceparent` stored with each outbox row and sent as a Kafka header. |
| Cost | Aurora Serverless v2; one schema per service; relay off until needed. |

Kafka profiles: `kafka-msk` (default; SASL/IAM through aws-msk-iam-auth 2.2.0) and `kafka-strimzi`
(mutual TLS, PEM from `KAFKA_TLS_CERT`, `KAFKA_TLS_KEY`, `KAFKA_TLS_CA`). Topics are never auto-created.
