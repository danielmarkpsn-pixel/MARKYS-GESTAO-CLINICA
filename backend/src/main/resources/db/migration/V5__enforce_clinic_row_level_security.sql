-- Enforce tenant isolation inside PostgreSQL, not only in application queries.
-- The request filter sets app.current_clinic_id transaction-locally after validating
-- the user's active membership. FORCE ROW LEVEL SECURITY also applies to table owners.
DO $$
DECLARE
    table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'patients',
        'professionals',
        'clinic_rooms',
        'queue_entries',
        'triage_records',
        'encounters',
        'clinical_notes'
    ]
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I
             USING (clinic_id = NULLIF(current_setting(''app.current_clinic_id'', true), '''')::uuid)
             WITH CHECK (clinic_id = NULLIF(current_setting(''app.current_clinic_id'', true), '''')::uuid)',
            'tenant_isolation_' || table_name,
            table_name
        );
    END LOOP;
END $$;
