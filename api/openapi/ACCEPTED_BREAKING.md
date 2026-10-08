# Accepted breaking changes (OpenAPI)

| Date | Spec | Change | Why accepted | Approved by |
|---|---|---|---|---|
| 2026-10-08 | bulk-orchestration-service.yaml | Security scheme `bearerAuth` (http bearer) replaced by `dpopAuth` (http DPoP); Bearer tokens now get 401 | Platform contract: DPoP applies by caller, and this API is TPP-facing. The monolith already required a DPoP header; now the proof is verified. | Proposed; needs the owning squad and contracts review |
| 2026-10-08 | bulk-orchestration-service.yaml | `POST /file-payments` documents the body the service really reads (`ConsentId, FileName, FileContent, FileHash, Currency`, optional `IntegrityMode`) with the new required `Currency`; report schema matches the response (`TotalCount`/`AcceptedCount`/`RejectedCount`, `Items[]` with `Amount` and `Currency`) | The previous spec described the seed's draft (`FilePaymentId` in the body, `TotalItems`), not the implemented API. Currency is required because amounts cannot be validated or published without it and must never be defaulted. Clients without `Currency` now get 400. | Proposed; needs the owning squad and contracts review |
