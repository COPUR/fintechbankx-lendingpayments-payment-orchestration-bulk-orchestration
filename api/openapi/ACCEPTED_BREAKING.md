# Accepted breaking changes (OpenAPI)

| Date | Spec | Change | Why accepted | Approved by |
|---|---|---|---|---|
| 2026-10-08 | bulk-orchestration-service.yaml | Security scheme `bearerAuth` (http bearer) replaced by `dpopAuth` (http DPoP); Bearer tokens now get 401 | Platform contract: DPoP applies by caller, and this API is TPP-facing. The monolith already required a DPoP header; now the proof is verified. | Proposed; needs the owning squad and contracts review |
