-- DPoP proof replay guard shared by all replicas (SHA-256 of the proof jti).
-- Rows expire once the proof could no longer pass the iat window; a scheduled job purges them.
CREATE TABLE dpop_proof_jti (
    jti_hash   CHAR(64)    PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_dpop_proof_jti_expires ON dpop_proof_jti (expires_at);
