# Migration Granularity Notes

- Repository: `fintechbankx-payments-bulk-orchestration-service`
- Source monorepo: `enterprise-loan-management-system`
- Sync date: `2026-03-15`
- Sync branch: `chore/granular-source-sync-20260313`

## Applied Rules

- capability extraction: `bulkpayments` from `open-finance-context`
- dir: `infra/terraform/services/bulk-payments-service` -> `infra/terraform/bulk-payments-service`
- file: `docs/architecture/open-finance/capabilities/hld/corporate-treasury-and-bulk-payments-hld.md` -> `docs/hld/corporate-treasury-and-bulk-payments-hld.md`
- file: `docs/architecture/open-finance/capabilities/test-suites/corporate-bulk-payments-test-suite.md` -> `docs/test-suites/corporate-bulk-payments-test-suite.md`

## Notes

- This is an extraction seed for bounded-context split migration.
- Follow-up refactoring may be needed to remove residual cross-context coupling.
- Build artifacts and local machine files are excluded by policy.

