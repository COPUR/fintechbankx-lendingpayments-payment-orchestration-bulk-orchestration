-- Platform contract "Database roles". Flyway runs as the schema owner (the
-- migration Job's db-migration secret), which owns sc_pay_bulk_orchestration
-- (created by the DBA bootstrap) and every table in it. The service connects
-- as the runtime role (${runtime_role}, from DB_USERNAME) and gets USAGE on
-- the schema plus only the DML the code issues:
--   bulk_file             SELECT, INSERT, UPDATE           (aggregate; optimistic lock, FOR UPDATE SKIP LOCKED; never deleted)
--   bulk_item             SELECT, INSERT, UPDATE           (items written once, processed_at set; never deleted)
--   bulk_idempotency      SELECT, INSERT                   (insert ... on conflict do nothing; never reusable, never purged)
--   bulk_consent_binding  SELECT, INSERT                   (single-use binding, insert ... on conflict do nothing)
--   outbox_event          SELECT, INSERT, UPDATE, DELETE   (relay marks and parks rows, purges published ones)
--                         + USAGE on the created_seq identity sequence
--   dpop_proof_jti        SELECT, INSERT, DELETE           (replay guard insert ... do nothing, expiry purge)
-- Not being the owner, it can neither CREATE, ALTER, DROP nor TRUNCATE, and it
-- cannot read flyway_schema_history.
--
-- Default privileges: tables and sequences the owner creates in this schema
-- later get DML (SELECT, INSERT, UPDATE, DELETE) and sequence USAGE for the
-- runtime role, never TRUNCATE, REFERENCES or TRIGGER. A later migration that
-- adds an append-only table narrows it with REVOKE.
--
-- Local single-user runs (no owner credentials) migrate as the runtime role
-- itself; then there is nothing to separate and this migration only says so,
-- because revoking the owner's own privileges would break later migrations.

DO $$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    IF runtime_role = current_user THEN
        RAISE NOTICE 'runtime role % is the schema owner (single-user run): privileges not separated', runtime_role;
        RETURN;
    END IF;

    EXECUTE format('REVOKE ALL ON SCHEMA %I FROM %I', current_schema(), runtime_role);
    EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC', current_schema());
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', current_schema(), runtime_role);
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM %I', current_schema(), runtime_role);
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM PUBLIC', current_schema());
    EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM %I', current_schema(), runtime_role);

    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON TABLE bulk_file, bulk_item TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT ON TABLE bulk_idempotency, bulk_consent_binding TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE outbox_event TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT, DELETE ON TABLE dpop_proof_jti TO %I', runtime_role);
    EXECUTE format('GRANT USAGE ON SEQUENCE %s TO %I',
                   pg_get_serial_sequence(format('%I.outbox_event', current_schema()), 'created_seq'), runtime_role);

    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
                   current_user, current_schema(), runtime_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT USAGE ON SEQUENCES TO %I',
                   current_user, current_schema(), runtime_role);
END
$$;
