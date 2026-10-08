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
| upstream_dependencies | svc-of-consent-authorization (consent reads), Keycloak realm `fintechbankx`, Kafka (MSK or Strimzi) |
| published_events | `evt.pay.bulk.file-accepted.v1` (`Payments.BulkFile.Accepted.v1`), `evt.pay.bulk.file-completed.v1` (`Payments.BulkFile.Completed.v1`), `evt.pay.bulk.file-rejected.v1` (`Payments.BulkFile.Rejected.v1`) |
| consumed_events | none (any future consumer dead-letters to `evt.pay.bulk.dlq.v1`) |

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
(API on 8080, management on 8081).

- Security: TPP-facing API, so DPoP is enforced (`Authorization: DPoP`, verified proof with a single-use jti, proof key = token `cnf.jkt`, `aud` = `svc-pay-bulk-orchestration`). Set `DPOP_REQUIRED=false` only for local runs with plain tokens.
- Endpoints: `POST /open-finance/v1/file-payments`, `GET /open-finance/v1/file-payments/{fileId}`, `GET /open-finance/v1/file-payments/{fileId}/report`
- Events contract: [AsyncAPI](./api/asyncapi/svc-pay-bulk-orchestration.yaml)
- Deployment: [Helm chart](./deploy/helm/payment-bulk-orchestration-service), [Terraform](./deploy/terraform), [deployment notes](./docs/architecture/DEPLOYMENT_AND_WELL_ARCHITECTED.md)
- Migration: [runbook](./docs/migration/RUNBOOK-EXTRACT-pay-bulk-orchestration.md) (no backfill; catalog PR pending), [regression mapping](./docs/migration/REGRESSION_MAPPING.md)
- Mesh: the chart ships no Istio policy. The mesh owners must ALLOW `cluster.local/ns/istio-ingress/sa/istio-ingressgateway` to this service, and `cluster.local/ns/payments/sa/payment-bulk-orchestration-service` to the consent service.
