-- Configurable reception catalog, scoped to each clinic.
CREATE TABLE service_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_types_clinic_id_id UNIQUE (clinic_id, id)
);
CREATE UNIQUE INDEX uq_service_types_clinic_name
    ON service_types (clinic_id, lower(name));

CREATE TABLE exam_catalog (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    category VARCHAR(60) NOT NULL
        CHECK (category IN ('RAIO_X', 'LABORATORIO', 'AUDIOMETRIA', 'COMPLEMENTAR', 'OUTROS')),
    name VARCHAR(140) NOT NULL,
    room_id UUID NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_exam_catalog_clinic_id_id UNIQUE (clinic_id, id),
    CONSTRAINT fk_exam_catalog_room
        FOREIGN KEY (clinic_id, room_id)
        REFERENCES clinic_rooms (clinic_id, id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uq_exam_catalog_clinic_category_name
    ON exam_catalog (clinic_id, category, lower(name));
CREATE INDEX ix_exam_catalog_clinic_active_category
    ON exam_catalog (clinic_id, active, category, sort_order);

ALTER TABLE service_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE service_types FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_service_types ON service_types
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid);

ALTER TABLE exam_catalog ENABLE ROW LEVEL SECURITY;
ALTER TABLE exam_catalog FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_exam_catalog ON exam_catalog
    USING (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid)
    WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id', true), '')::uuid);
