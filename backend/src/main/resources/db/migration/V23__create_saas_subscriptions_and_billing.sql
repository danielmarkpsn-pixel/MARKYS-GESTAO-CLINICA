-- SaaS subscription and billing ledger for MARKYS clinics.
CREATE TABLE subscription_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    monthly_price NUMERIC(12,2) NOT NULL CHECK (monthly_price >= 0),
    billing_interval VARCHAR(16) NOT NULL DEFAULT 'MONTHLY' CHECK (billing_interval='MONTHLY'),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE clinic_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    plan_id UUID NOT NULL REFERENCES subscription_plans(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
      CHECK (status IN ('PENDING','TRIALING','ACTIVE','PAST_DUE','SUSPENDED','CANCELLED','EXPIRED')),
    payment_method VARCHAR(20)
      CHECK (payment_method IS NULL OR payment_method IN ('CREDIT_CARD','PIX','BOLETO')),
    started_at TIMESTAMPTZ,
    current_period_start DATE,
    current_period_end DATE,
    next_due_date DATE,
    cancelled_at TIMESTAMPTZ,
    external_customer_id VARCHAR(160),
    external_subscription_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_clinic_subscription_one_current UNIQUE (clinic_id, id)
);
CREATE INDEX ix_clinic_subscriptions_current ON clinic_subscriptions(clinic_id,status,updated_at DESC);

CREATE TABLE subscription_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    subscription_id UUID NOT NULL,
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL DEFAULT 'BRL' CHECK (currency='BRL'),
    payment_method VARCHAR(20) NOT NULL CHECK (payment_method IN ('CREDIT_CARD','PIX','BOLETO')),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
      CHECK (status IN ('PENDING','PROCESSING','PAID','OVERDUE','CANCELLED','REFUNDED','FAILED')),
    due_date DATE NOT NULL,
    paid_at TIMESTAMPTZ,
    external_payment_id VARCHAR(160),
    external_reference VARCHAR(120) NOT NULL,
    checkout_url TEXT,
    boleto_url TEXT,
    boleto_barcode VARCHAR(120),
    pix_copy_paste TEXT,
    pix_qr_code TEXT,
    provider_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_subscription_payment_reference UNIQUE (external_reference),
    CONSTRAINT uq_subscription_payment_external UNIQUE (clinic_id,external_payment_id),
    CONSTRAINT fk_subscription_payment_subscription FOREIGN KEY (clinic_id,subscription_id)
      REFERENCES clinic_subscriptions(clinic_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_subscription_payments_due ON subscription_payments(clinic_id,status,due_date DESC);

CREATE TABLE subscription_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    subscription_id UUID NOT NULL,
    payment_id UUID,
    event_type VARCHAR(40) NOT NULL,
    detail VARCHAR(1000),
    actor_membership_id UUID,
    provider_event_id VARCHAR(180),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_subscription_event_subscription FOREIGN KEY (clinic_id,subscription_id)
      REFERENCES clinic_subscriptions(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_event_payment FOREIGN KEY (clinic_id,payment_id)
      REFERENCES subscription_payments(clinic_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_event_actor FOREIGN KEY (clinic_id,actor_membership_id)
      REFERENCES clinic_memberships(clinic_id,id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uq_subscription_provider_event ON subscription_events(provider_event_id)
  WHERE provider_event_id IS NOT NULL;

ALTER TABLE clinic_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinic_subscriptions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_clinic_subscriptions ON clinic_subscriptions
  USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
  WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);

ALTER TABLE subscription_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscription_payments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_subscription_payments ON subscription_payments
  USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
  WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);

ALTER TABLE subscription_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscription_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_subscription_events ON subscription_events
  USING (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid)
  WITH CHECK (clinic_id = NULLIF(current_setting('app.current_clinic_id',true),'')::uuid);
