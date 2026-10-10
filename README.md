# fintechbankx-lendingpayments-payment-orchestration-bulk-orchestration

Bu repository, FinTechBankX DDD/EDA dönüşümünde **svc-pay-bulk-orchestration** servis yetkinliğinin kaynak kodunu, kontratlarını ve operasyonel guardrail'lerini içerir.

## Sorumluluk ve Sahiplik
| Alan | Değer |
|---|---|
| Organizasyon Modeli | Spotify Model (Tribe/Squad) |
| Tribe | Lending & Payments Tribe |
| Squad | Recurring and Bulk Payments Squad |
| Repo Kümesi (Capability) | payments |
| Service ID | svc-pay-bulk-orchestration |
| Bounded Context | payment_bulk_orchestration |
| Wave | 3 |
| Mimari Yaklaşım | DDD + Hexagonal + Event-Driven |

## Sorumluluk Sınırları
- Bu repo kendi bounded context domain modelinin tek yetkili sahibidir.
- Domain kuralları altyapıdan bağımsız tutulur; entegrasyonlar port/adapter katmanında yönetilir.
- API/Event kontratları geriye dönük uyumluluk kontrolleri ile korunur.
- Güvenlik guardrail'leri (mTLS, token doğrulama, idempotency, log hijyeni) CI/CD ile zorlanır.

## Kapsam
### In Scope
- payment_bulk_orchestration bağlamına ait uygulama kodu, testler ve otomasyon.
- Bu servise ait OpenAPI/AsyncAPI veya şema artefaktları.
- Bu servisin çalışma zamanı operasyonları (gözlemlenebilirlik, release, rollback).

### Out of Scope
- Diğer bounded context'lerin iş kuralları ve veri sahipliği.
- Paylaşımlı DB anti-pattern'i; cross-context doğrudan tablo erişimi.
- Platform dışı gizli bilgi/anahtar yönetimi (merkezi policy dışında local hardcode).

## Mühendislik Standartları
- **TDD öncelikli** geliştirme, birim test + entegrasyon testi.
- **Clean Architecture**: Domain katmanı framework bağımsız.
- **12-Factor** ve environment-driven configuration.
- **FAPI odaklı güvenlik** (OIDC/OAuth2, mTLS, DPoP gereksinimleri ilgili servislerde).
- **PII güvenliği**: loglarda maskeleme, secret'ların source/env içine yazılmaması.

## Branching ve Release Akışı
- Uzun ömürlü branch'ler: `main`, `dev`, `staging`, `local`.
- Feature branch kuralı: `codex/<kisa-aciklama>`.
- Release yaklaşımı: PR + required status checks + tag tabanlı sürümleme.

## Dokümantasyon ve Referanslar
- [Enterprise Architecture Hub](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture)
- [Secure Microservices Architecture](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/architecture/overview/SECURE_MICROSERVICES_ARCHITECTURE.md)
- [Service Data Ownership Matrix](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/SERVICE_DATA_OWNERSHIP_MATRIX.md)
- [Service API Contracts Index](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/SERVICE_API_CONTRACTS_INDEX.md)
- [Transformation Plan](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/MICROSERVICES_TRANSFORMATION_PLAN.md)
- [Capability Map (PUML)](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/puml/service-mesh/enterprise-capability-map.puml)
- [Bu Repo Dokümantasyonu](./docs)

## Güvenlik ve Uyumluluk Notları
- Gerçek secret değerleri repo veya `.env` içinde tutulmaz.
- Secret üretim/rotasyon olayları merkezi log/SIEM'e taşınır.
- CI pipeline, anonimlik ve local-path sızıntısı kontrollerini bloklayıcı olarak çalıştırır.

## Katkı
- Katkı süreci için `CONTRIBUTING.md` ve squad runbook'ları izlenmelidir.
- PR'larda mimari kararlar ADR veya backlog referansı ile ilişkilendirilmelidir.

## Cell-Based Architecture

This repository participates in the FinTechBankX cell-based resilience program.

- Plan: \
- Backlog: \

<!-- cell-architecture-start -->
## Cell-Based Architecture

This repository participates in the FinTechBankX cell-based resilience program.

- Plan: docs/architecture/CELL_BASED_ARCHITECTURE_IMPLEMENTATION_PLAN.md
- Backlog: docs/project-management/CELL_ARCHITECTURE_BACKLOG_BOARD.md
<!-- cell-architecture-end -->

## Service metadata (naming standard)

| Tag | Value |
|---|---|
| bounded_context | payment_bulk_orchestration (capability `bulkpayments`) |
| owning_squad | Recurring and Bulk Payments Squad |
| owning_tribe | Lending & Payments Tribe |
| review_cadence | quarterly |
| data_owner | Recurring and Bulk Payments Squad (schema `sc_pay_bulk_orchestration`) |
| upstream_dependencies | svc-of-consent-authorization (consent reads: `GET /api/v1/consents/{id}`, client credentials, `usable` decides), Keycloak realm `fintechbankx`, Kafka (MSK or Strimzi) |
| published_events | `evt.pay.bulk.v1`, one topic per aggregate (ADR-019), keyed by the file id, event named by the `eventType` record header: `Payments.BulkFile.Accepted.v1`, `Payments.BulkFile.Rejected.v1`, `Payments.BulkFile.Completed.v1` (contract only: not emitted until items are handed to initiation-settlement) |
| consumed_events | none (producer only; no dead-letter topic of its own) |

Runtime names: service id `svc-pay-bulk-orchestration`, `spring.application.name` `app.pay.bulk-orchestration`,
Helm release, service account and image `payment-bulk-orchestration-service` in namespace `payments`,
label `fintechbankx.io/app: app-pay-bulk-orchestration`, database `db_pay_bulk_orchestration_<env>`.

## Scope cleanup (residue removed from the extraction seed)

| Removed | Owner |
|---|---|
| Consent models, `ConsentController`, `DistributedConsentService`, Redis consent cache | fintechbankx-openfinance-consent-auth-service |
| `OpenFinanceAccountController` and account data | fintechbankx-openfinance-retail-data-personal-financial / corporate-data-business-financial |
| `OpenFinanceLoanController` | fintechbankx-lendingpayments-loan-lifecycle-core |
| CBUAE participant directory adapter and port | fintechbankx-openfinance-payee-metadata-banking-metadata |
| Analytics (Mongo), CQRS projections, `PostgreSQLEventStore`, monitoring | fintechbankx-platform-observability-sre-operations / platform event streaming |
| Keycloak `FAPIAuthenticator`, PCI guard | fintechbankx-platform-identity-iam-keycloak-ldap / platform mesh security |
| `application/saga` | payment initiation and settlement (svc-pay-initiation-settlement) |
| `infra/terraform/bulk-payments-service` (pointed at a missing module path) | replaced by `deploy/terraform` |
| In-memory file, report and idempotency adapters | replaced by Postgres adapters |

## Run, test and deploy

```bash
./gradlew --no-daemon clean check            # unit + ArchUnit + coverage gate; Postgres ITs skip
TEST_DB_URL=jdbc:postgresql://localhost:5432/<db> TEST_DB_USERNAME=<user> TEST_DB_PASSWORD=<pw> \
  ./gradlew --no-daemon clean check          # also runs the Postgres ITs (CI=true without a DB fails)
```

Local boot without Kafka: set `DB_URL`, `DB_USERNAME`, `SPRING_DATASOURCE_PASSWORD`,
`CONSENT_ADAPTER=in-memory` and keep `OUTBOX_RELAY_ENABLED=false` (and `DPOP_REQUIRED=false` to call it with plain Bearer tokens), then
`java -jar open-finance-bootstrap/build/libs/payment-bulk-orchestration-service.jar`
(API on 8080, management on 8081). The schema `sc_pay_bulk_orchestration` must exist (Flyway does not create
it); single-user, Flyway runs in-process as that user and V11 only logs that privileges are not separated.

Database roles: the pods connect as the DML-only runtime role (`DB_USERNAME`, secret `db-app`) with
`SPRING_FLYWAY_ENABLED=false`; migrations run as the schema owner in a Helm pre-install/pre-upgrade Job
(`java -jar ... migrate`, secret `db-migration`). See the runbook, section 2.

Deployed, the pods verify Aurora's certificate: `DB_URL` must carry
`sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem` (Terraform output
`jdbc_url`), and the chart mounts the platform ConfigMap `rds-ca-bundle` there. Local
runs and tests keep their own URLs.

- Security: TPP-facing API, so DPoP is enforced (`Authorization: DPoP`, verified proof with a single-use jti, proof key = token `cnf.jkt`, `aud` = `svc-pay-bulk-orchestration`). Set `DPOP_REQUIRED=false` only for local runs with plain tokens.
- Endpoints: `POST /open-finance/v1/file-payments`, `GET /open-finance/v1/file-payments/{fileId}`, `GET /open-finance/v1/file-payments/{fileId}/report` ([OpenAPI](./api/openapi/bulk-orchestration-service.yaml))
- Money: the upload carries a required ISO 4217 `Currency` (the CSV `instruction_id,payee_iban,amount` has none). Amounts are kept and published at the currency's minor units and never rounded; a finer amount is 400.
- Events contract: [AsyncAPI](./api/asyncapi/svc-pay-bulk-orchestration.yaml)
- Event contract gate (ADR-019 section 5): `npm install --no-save yaml@2.9.1 && ASYNCAPI_DIR=api/asyncapi BASE_REF=origin/main ./scripts/ci/asyncapi-breaking.sh`, plus `npx -y @asyncapi/cli@2.13.0 validate api/asyncapi/svc-pay-bulk-orchestration.yaml`; both run in `ci/test`. `scripts/ci/asyncapi-breaking.mjs` (sha256 `de255737fe6b54ffbadce8e030e18eec48d0137e0abd43c790fe201a10015eb1`), `scripts/ci/asyncapi-breaking.sh` (sha256 `5b39d588673c5f7ab55fcfa548fffd70b96ab9b11ea82bd2b61468dadb430c77`) and `scripts/ci/lib/asyncapi-model.mjs` (sha256 `212df6ca092e1ed5519664d7848c9de5fdb5d137f34faa3d31a25e3fe29b3847`) are copies of the AsyncAPI catalog's files at commit 44837cc; `ci/test` fails when `sha256sum` of a copy differs (step "AsyncAPI gate scripts match the catalog copy"). Do not edit them: copy again and update the commit and the sums here and in the workflow. `api/asyncapi/common/event-envelope.yaml` is the catalog's file at the same commit. An accepted-breaking file would sit next to the spec in `api/asyncapi/`. The spec is pre-release (not on `main` yet), stays at 1.0.0 and has no waiver
- Deployment: [Helm chart](./deploy/helm/payment-bulk-orchestration-service), [Terraform](./deploy/terraform), [deployment notes](./docs/architecture/DEPLOYMENT_AND_WELL_ARCHITECTED.md)
- Chart values guard (guardrail 4a): `deploy/helm/payment-bulk-orchestration-service/templates/_fbx-guard.tpl` (sha256 `0dae4352a5872385177228fb29416bccf486a6231f63764576add00adf7bb7e2`) is a verbatim copy of the platform chart's guard block, cicd-templates `charts/fintechbankx-service/templates/_helpers.tpl` at commit 2caa48f (`fbx.datasourceOverrideName`, `fbx.isJvmOptionsName`, `fbx.validateJvmOptions`, `fbx.validateDatabaseTls`, `fbx.validateJdbcUrl`); `deploy/helm` fails when its `sha256sum` differs (step "Vendored guard matches the platform copy"). Do not edit it: copy again from the newer platform commit and update the commit and the sum here, in the file header and in the workflow. This repo's own rules (indexed config forms, every `spring.profiles.*` form, `fintechbankx.tls.*`, the Kafka protocol names, `fintechbankx`/`kafka` in JVM options) sit in `templates/_helpers.tpl` (`bulk.guardValues`). The Kafka profile is never a value: `kafkaStrimzi.enabled` renders `SPRING_PROFILES_ACTIVE` (kafka-msk or kafka-strimzi) and `KAFKA_SECURITY_PROTOCOL` (SASL_SSL or SSL), so the `local` profile (the only packaged configuration that switches the startup TLS assertion off) cannot be activated through the chart
- Migration: [runbook](./docs/migration/RUNBOOK-EXTRACT-pay-bulk-orchestration.md) (no backfill; catalog PR pending), [regression mapping](./docs/migration/REGRESSION_MAPPING.md)
- Mesh: the chart ships no Istio policy. The mesh owners must ALLOW `cluster.local/ns/istio-ingress/sa/istio-ingressgateway` to this service, and `cluster.local/ns/payments/sa/payment-bulk-orchestration-service` to the consent service.
