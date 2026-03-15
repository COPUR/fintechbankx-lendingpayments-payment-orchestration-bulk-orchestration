# Test Suite: Corporate Bulk Payments
**Scope:** Corporate Bulk Payments (Bulk)
**Actors:** Corporate TPP (ERP), Corporate PSU, ASPSP

## 1. Prerequisites
* Corporate Consent with `bulk-payment` permission authorized by required signatories.
* A valid bulk file (CSV/XML) adhering to the schema.

## 2. Test Cases

### Suite A: File Upload & Validation
| ID | Test Case Description | Input Data | Expected Result | Type |
|----|-----------------------|------------|-----------------|------|
| **TC-BLK-001** | Upload Valid Bulk File | Valid CSV (50 records) | `202 Accepted`, `FileId` returned | Functional |
| **TC-BLK-002** | Upload Empty File | Empty CSV | `400 Bad Request`, Error: `Empty Payload` | Negative |
| **TC-BLK-003** | Upload Malformed File | CSV with missing columns | `400 Bad Request`, Error: `Schema Validation Failed` | Negative |
| **TC-BLK-004** | Check File Status (Processing) | `FileId` (Immediately after upload) | `200 OK`, Status: `AwaitingUpload` or `Processing` | Functional |
| **TC-BLK-005** | Check File Status (Completed) | `FileId` (After processing time) | `200 OK`, Status: `Completed`, Report Available | Functional |

### Suite B: Execution & Reporting
| ID | Test Case Description | Input Data | Expected Result | Type |
|----|-----------------------|------------|-----------------|------|
| **TC-BLK-006** | Retrieve File Report | `FileId` (Completed) | `200 OK`, JSON/CSV report detailing success/failure per line item | Functional |
| **TC-BLK-007** | Partial Failure | File with 1 invalid IBAN out of 10 | `200 OK`, Status: `PartiallyAccepted`, Report shows 1 failure | Edge Case |
| **TC-BLK-008** | Idempotency on Upload | Re-upload same file with same `x-idempotency-key` | `202 Accepted` (Returns original `FileId`, no double processing) | Reliability |
