# Deployment and well-architected notes (svc-pay-bulk-orchestration)

Status: **Proposed**.

| Pillar | What the code and chart do |
|---|---|
| Security | JWT resource server (issuer, signature, `aud`=`svc-pay-bulk-orchestration`); TPP ownership from the token; deny-by-default routes; non-root image (10001:10001); secrets via External Secrets from AWS Secrets Manager (KMS key, root-only policy); IRSA with no secret reads; TLS enforced on Aurora (`rds.force_ssl`) and verified by the pods (`sslmode=verify-full` against the platform RDS CA bundle, ConfigMap `rds-ca-bundle` mounted read-only at `/etc/ssl/rds`; the chart refuses any other `DB_URL`); NetworkPolicy (8080 from ingress and caller namespaces, 8081 from observability). DPoP enforced on the TPP API (proof verified, jti replay guard in `dpop_proof_jti`, `cnf.jkt` match; `DPOP_REQUIRED`). |
| Reliability | Transactional outbox with relay (advisory lock, PARKED after 10 attempts); consent reads fail closed (503); optimistic locking on `bulk_file`; `FOR UPDATE SKIP LOCKED` so replicas never process the same file; PDB minAvailable 2; probes on 8081. |
| Performance | Items in `bulk_item`, written over JDBC in chunks of 1000 and processed in batches of 500 (`BULK_PROCESSING_BATCH_SIZE`); HPA on CPU only; memory request = limit. |
| Operations | Prometheus on 8081 (`outbox_pending_events`, `outbox_parked_events`, `outbox_oldest_pending_age_seconds`); OTLP traces to `otel-collector.observability.svc.cluster.local:4318`; W3C `traceparent` stored with each outbox row and sent as a Kafka header. |
| Cost | Aurora Serverless v2; one schema per service; relay off until needed. |

Kafka profiles: `kafka-msk` (default; SASL/IAM through aws-msk-iam-auth 2.2.0) and `kafka-strimzi`
(mutual TLS, PEM from `KAFKA_TLS_CERT`, `KAFKA_TLS_KEY`, `KAFKA_TLS_CA`). Topics are never auto-created.
