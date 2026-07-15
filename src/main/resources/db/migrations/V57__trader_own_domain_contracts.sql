-- TRADER/OWN domain foundations. These tables describe facts and immutable projections;
-- existing practice and tracked-ledger write paths are connected in later migrations.

CREATE TABLE account_objective_revision (
  id                         TEXT PRIMARY KEY,
  portfolio_account_id       TEXT NOT NULL REFERENCES portfolio_account(id) ON DELETE CASCADE,
  revision_no                INTEGER NOT NULL CHECK (revision_no > 0),
  objective                  TEXT NOT NULL CHECK (objective IN
    ('INCOME','ACCUMULATE','HEDGE','DIRECTIONAL','CAPITAL_PRESERVATION')),
  direction                  TEXT CHECK (direction IS NULL OR direction IN
    ('BULLISH','BEARISH','NEUTRAL','NON_DIRECTIONAL')),
  target_exposure_cents      BIGINT,
  assignment_preference      TEXT NOT NULL CHECK (assignment_preference IN
    ('AVOID','ACCEPT','PREFER_BELOW_BASIS','SEEK')),
  created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (portfolio_account_id, revision_no)
);

ALTER TABLE portfolio_account ADD CONSTRAINT uq_portfolio_account_user_id UNIQUE(user_id,id);

CREATE TABLE portfolio_structure (
  id                         TEXT PRIMARY KEY,
  user_id                    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  portfolio_account_id       TEXT NOT NULL REFERENCES portfolio_account(id) ON DELETE CASCADE,
  symbol                     TEXT NOT NULL CHECK (btrim(symbol) <> ''),
  label                      TEXT,
  status                     TEXT NOT NULL CHECK (status IN ('OPEN','CLOSED','RETIRED')),
  current_revision_id        TEXT,
  created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_portfolio_structure_account ON portfolio_structure(portfolio_account_id,status,symbol);
ALTER TABLE portfolio_structure ADD CONSTRAINT fk_portfolio_structure_owned_account
  FOREIGN KEY (user_id,portfolio_account_id) REFERENCES portfolio_account(user_id,id) ON DELETE CASCADE;

CREATE TABLE portfolio_structure_revision (
  id                         TEXT PRIMARY KEY,
  structure_id               TEXT NOT NULL REFERENCES portfolio_structure(id) ON DELETE CASCADE,
  revision_no                INTEGER NOT NULL CHECK (revision_no > 0),
  prior_revision_id          TEXT REFERENCES portfolio_structure_revision(id) ON DELETE RESTRICT,
  position_state             TEXT NOT NULL CHECK (position_state IN
    ('PENDING','OPEN','PARTIALLY_CLOSED','ASSIGNED','EXERCISED','EXPIRED','CLOSED')),
  action_role                TEXT NOT NULL CHECK (action_role IN
    ('ENTRY','ADJUST','ROLL','PARTIAL_CLOSE','CLOSE','ASSIGNMENT','EXERCISE','EXPIRATION')),
  transaction_id             TEXT REFERENCES portfolio_transaction(id) ON DELETE RESTRICT,
  created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (structure_id, revision_no),
  UNIQUE (structure_id, id)
);

ALTER TABLE portfolio_structure ADD CONSTRAINT fk_portfolio_structure_current_revision
  FOREIGN KEY (id,current_revision_id)
  REFERENCES portfolio_structure_revision(structure_id,id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE portfolio_structure_member (
  revision_id                TEXT NOT NULL REFERENCES portfolio_structure_revision(id) ON DELETE CASCADE,
  leg_no                     INTEGER NOT NULL CHECK (leg_no >= 0),
  lot_id                     TEXT NOT NULL REFERENCES portfolio_lot(id) ON DELETE RESTRICT,
  allocated_quantity         BIGINT NOT NULL CHECK (allocated_quantity > 0),
  leg_role                   TEXT NOT NULL CHECK (leg_role IN
    ('UNDERLYING','LONG_CALL','SHORT_CALL','LONG_PUT','SHORT_PUT','CUSTOM')),
  PRIMARY KEY (revision_id,leg_no),
  UNIQUE (revision_id,lot_id,leg_role)
);
CREATE INDEX idx_portfolio_structure_member_lot ON portfolio_structure_member(lot_id);

CREATE TABLE portfolio_import_pending (
  id                         TEXT PRIMARY KEY,
  user_id                    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source_system              TEXT NOT NULL CHECK (btrim(source_system) <> ''),
  source_account_fingerprint TEXT NOT NULL CHECK (btrim(source_account_fingerprint) <> ''),
  external_ref               TEXT NOT NULL CHECK (btrim(external_ref) <> ''),
  broker                     TEXT,
  occurred_at                TIMESTAMPTZ NOT NULL,
  package_net_cents          BIGINT NOT NULL,
  fees_cents                 BIGINT NOT NULL DEFAULT 0 CHECK (fees_cents >= 0),
  plan_id                    TEXT REFERENCES plans(id) ON DELETE SET NULL,
  status                     TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RESOLVED','REJECTED')),
  created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at                TIMESTAMPTZ,
  UNIQUE (user_id,source_system,source_account_fingerprint,external_ref),
  CHECK ((status='PENDING' AND resolved_at IS NULL)
      OR (status IN ('RESOLVED','REJECTED') AND resolved_at IS NOT NULL))
);
CREATE INDEX idx_portfolio_import_pending_user ON portfolio_import_pending(user_id,status,occurred_at DESC);

CREATE TABLE portfolio_import_pending_leg (
  pending_id                 TEXT NOT NULL REFERENCES portfolio_import_pending(id) ON DELETE CASCADE,
  leg_no                     INTEGER NOT NULL CHECK (leg_no >= 0),
  instrument_type            TEXT NOT NULL CHECK (instrument_type IN ('STOCK','OPTION')),
  action                     TEXT NOT NULL CHECK (action IN ('BUY','SELL')),
  position_effect            TEXT NOT NULL CHECK (position_effect IN ('OPEN','CLOSE')),
  symbol                     TEXT NOT NULL CHECK (btrim(symbol) <> ''),
  option_type                TEXT CHECK (option_type IS NULL OR option_type IN ('CALL','PUT')),
  strike                     NUMERIC(19,6),
  expiration                 DATE,
  quantity                   BIGINT NOT NULL CHECK (quantity > 0),
  multiplier                 INTEGER NOT NULL CHECK (multiplier > 0),
  reported_price             NUMERIC(19,6),
  reported_price_authority   TEXT NOT NULL DEFAULT 'MISSING' CHECK (reported_price_authority IN
    ('MISSING','BROKER_REPORTED','USER_ALLOCATED')),
  PRIMARY KEY (pending_id,leg_no),
  CHECK ((instrument_type='STOCK' AND option_type IS NULL AND strike IS NULL AND expiration IS NULL AND multiplier=1)
      OR (instrument_type='OPTION' AND option_type IS NOT NULL AND strike IS NOT NULL AND expiration IS NOT NULL)),
  CHECK ((reported_price_authority='MISSING' AND reported_price IS NULL)
      OR (reported_price_authority<>'MISSING' AND reported_price IS NOT NULL AND reported_price>=0))
);

CREATE TABLE campaign (
  id                         TEXT PRIMARY KEY,
  user_id                    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  symbol                     TEXT,
  title                      TEXT NOT NULL CHECK (btrim(title) <> ''),
  status                     TEXT NOT NULL CHECK (status IN ('ACTIVE','CLOSED','ARCHIVED')),
  account_objective_revision_id TEXT REFERENCES account_objective_revision(id) ON DELETE SET NULL,
  created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_campaign_user_status ON campaign(user_id,status,updated_at DESC);

CREATE TABLE campaign_structure_member (
  campaign_id                TEXT NOT NULL REFERENCES campaign(id) ON DELETE CASCADE,
  structure_id               TEXT NOT NULL REFERENCES portfolio_structure(id) ON DELETE RESTRICT,
  attached_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (campaign_id,structure_id)
);

CREATE TABLE campaign_transaction_member (
  campaign_id                TEXT NOT NULL REFERENCES campaign(id) ON DELETE CASCADE,
  transaction_id             TEXT NOT NULL REFERENCES portfolio_transaction(id) ON DELETE RESTRICT,
  explicit_interest          INTEGER NOT NULL DEFAULT 0 CHECK (explicit_interest IN (0,1)),
  attached_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (campaign_id,transaction_id)
);

CREATE TABLE campaign_pending_member (
  campaign_id                TEXT NOT NULL REFERENCES campaign(id) ON DELETE CASCADE,
  pending_id                 TEXT NOT NULL REFERENCES portfolio_import_pending(id) ON DELETE RESTRICT,
  attached_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (campaign_id,pending_id)
);

CREATE TABLE campaign_practice_trade_member (
  campaign_id                TEXT NOT NULL REFERENCES campaign(id) ON DELETE CASCADE,
  trade_id                   TEXT NOT NULL REFERENCES trades(id) ON DELETE RESTRICT,
  attached_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (campaign_id,trade_id)
);

CREATE TABLE campaign_plan_member (
  campaign_id                TEXT NOT NULL REFERENCES campaign(id) ON DELETE CASCADE,
  plan_id                    TEXT NOT NULL REFERENCES plans(id) ON DELETE RESTRICT,
  attached_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (campaign_id,plan_id)
);

CREATE TABLE position_receipt (
  id                         TEXT PRIMARY KEY,
  user_id                    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind                       TEXT NOT NULL CHECK (kind IN ('DECISION','ADOPTION','TRANSFORMATION','RESOLUTION')),
  authority                  TEXT NOT NULL CHECK (authority IN
    ('SYSTEM_OBSERVED','BROKER_REPORTED','USER_ALLOCATED')),
  analysis_artifact_state    TEXT NOT NULL DEFAULT 'FROZEN' CHECK (analysis_artifact_state='FROZEN'),
  execution_lane             TEXT NOT NULL CHECK (execution_lane IN ('NONE','PRACTICE','REAL')),
  position_state             TEXT NOT NULL CHECK (position_state IN
    ('PENDING','OPEN','PARTIALLY_CLOSED','ASSIGNED','EXERCISED','EXPIRED','CLOSED')),
  plan_id                    TEXT REFERENCES plans(id) ON DELETE SET NULL,
  plan_context_rev           INTEGER,
  account_objective_revision_id TEXT REFERENCES account_objective_revision(id) ON DELETE SET NULL,
  portfolio_account_id       TEXT REFERENCES portfolio_account(id) ON DELETE SET NULL,
  structure_revision_id      TEXT REFERENCES portfolio_structure_revision(id) ON DELETE SET NULL,
  practice_trade_id          TEXT REFERENCES trades(id) ON DELETE SET NULL,
  decision_id                TEXT REFERENCES plan_decision(id) ON DELETE SET NULL,
  transaction_id             TEXT REFERENCES portfolio_transaction(id) ON DELETE SET NULL,
  marks_as_of                TIMESTAMPTZ NOT NULL,
  evidence_level             TEXT NOT NULL CHECK (evidence_level IN
    ('OBSERVED_LIVE','OBSERVED_DELAYED','OBSERVED_EOD','MODELED','SIMULATED','DEMO_FIXTURE','UNKNOWN')),
  model_version              TEXT NOT NULL,
  created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((plan_id IS NULL AND plan_context_rev IS NULL)
      OR (plan_id IS NOT NULL AND plan_context_rev IS NOT NULL)),
  FOREIGN KEY (plan_id,plan_context_rev)
    REFERENCES plan_context_revision(plan_id,rev) ON DELETE SET NULL
);
CREATE INDEX idx_position_receipt_plan ON position_receipt(plan_id,created_at DESC);
CREATE INDEX idx_position_receipt_structure ON position_receipt(structure_revision_id,created_at DESC);

CREATE TABLE position_receipt_leg (
  receipt_id                 TEXT NOT NULL REFERENCES position_receipt(id) ON DELETE CASCADE,
  leg_no                     INTEGER NOT NULL CHECK (leg_no >= 0),
  instrument_type            TEXT NOT NULL CHECK (instrument_type IN ('STOCK','OPTION')),
  action                     TEXT NOT NULL CHECK (action IN ('BUY','SELL')),
  symbol                     TEXT NOT NULL,
  option_type                TEXT CHECK (option_type IS NULL OR option_type IN ('CALL','PUT')),
  strike                     NUMERIC(19,6),
  expiration                 DATE,
  quantity                   BIGINT NOT NULL CHECK (quantity > 0),
  multiplier                 INTEGER NOT NULL CHECK (multiplier > 0),
  bid                        NUMERIC(19,6),
  ask                        NUMERIC(19,6),
  mid                        NUMERIC(19,6),
  fill_price                 NUMERIC(19,6),
  price_authority            TEXT NOT NULL CHECK (price_authority IN
    ('OBSERVED','BROKER_REPORTED','USER_REPORTED','MODELED')),
  PRIMARY KEY (receipt_id,leg_no),
  CHECK ((instrument_type='STOCK' AND option_type IS NULL AND strike IS NULL AND expiration IS NULL AND multiplier=1)
      OR (instrument_type='OPTION' AND option_type IS NOT NULL AND strike IS NOT NULL AND expiration IS NOT NULL))
);

CREATE TABLE position_receipt_metric (
  receipt_id                 TEXT NOT NULL REFERENCES position_receipt(id) ON DELETE CASCADE,
  metric_key                 TEXT NOT NULL,
  value_cents                BIGINT,
  value_number               DOUBLE PRECISION,
  value_text                 TEXT,
  PRIMARY KEY (receipt_id,metric_key),
  CHECK (((value_cents IS NOT NULL)::int + (value_number IS NOT NULL)::int
      + (value_text IS NOT NULL)::int)=1)
);

CREATE TABLE plan_portfolio_action (
  id                         TEXT PRIMARY KEY,
  plan_id                    TEXT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
  structure_revision_id      TEXT NOT NULL REFERENCES portfolio_structure_revision(id) ON DELETE RESTRICT,
  transaction_id             TEXT NOT NULL REFERENCES portfolio_transaction(id) ON DELETE RESTRICT,
  receipt_id                 TEXT NOT NULL REFERENCES position_receipt(id) ON DELETE RESTRICT,
  role                       TEXT NOT NULL CHECK (role IN
    ('ENTRY','ADJUST','ROLL','PARTIAL_CLOSE','CLOSE','ASSIGNMENT','EXERCISE','EXPIRATION')),
  created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (plan_id,receipt_id),
  UNIQUE (receipt_id)
);
CREATE INDEX idx_plan_portfolio_action_plan ON plan_portfolio_action(plan_id,created_at DESC);

CREATE TABLE portfolio_import_resolution (
  pending_id                 TEXT PRIMARY KEY REFERENCES portfolio_import_pending(id) ON DELETE RESTRICT,
  portfolio_account_id       TEXT NOT NULL REFERENCES portfolio_account(id) ON DELETE RESTRICT,
  transaction_id             TEXT NOT NULL UNIQUE REFERENCES portfolio_transaction(id) ON DELETE RESTRICT,
  receipt_id                 TEXT NOT NULL UNIQUE REFERENCES position_receipt(id) ON DELETE RESTRICT,
  authority                  TEXT NOT NULL CHECK (authority IN ('BROKER_REPORTED','USER_ALLOCATED')),
  tax_basis_status           TEXT NOT NULL CHECK (tax_basis_status IN ('AUTHORITATIVE','PROVISIONAL')),
  package_total_cents        BIGINT NOT NULL,
  allocated_total_cents      BIGINT NOT NULL,
  resolver_user_id           TEXT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  resolved_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (package_total_cents=allocated_total_cents),
  CHECK ((authority='BROKER_REPORTED' AND tax_basis_status='AUTHORITATIVE')
      OR (authority='USER_ALLOCATED' AND tax_basis_status='PROVISIONAL'))
);

-- Every Plan action row must describe the same transaction, structure revision, and Plan as its
-- frozen receipt. This is deferred so the artifact set can be assembled in any order in one tx.
CREATE FUNCTION enforce_plan_action_artifact_agreement() RETURNS trigger AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM plan_portfolio_action a
    JOIN position_receipt r ON r.id=a.receipt_id
    JOIN portfolio_structure_revision sr ON sr.id=a.structure_revision_id
    WHERE r.plan_id IS DISTINCT FROM a.plan_id
       OR r.structure_revision_id IS DISTINCT FROM a.structure_revision_id
       OR r.transaction_id IS DISTINCT FROM a.transaction_id
       OR r.execution_lane<>'REAL'
       OR sr.transaction_id IS DISTINCT FROM a.transaction_id
       OR sr.action_role IS DISTINCT FROM a.role
  ) THEN
    RAISE EXCEPTION 'Plan action artifacts do not describe the same tracked action';
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_plan_action_artifact_agreement
AFTER INSERT OR UPDATE ON plan_portfolio_action
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION enforce_plan_action_artifact_agreement();

-- Resolution must reconcile to the immutable pending package fact and to the transaction and
-- receipt it creates. A pending row becomes RESOLVED in the same transaction, never beforehand.
CREATE FUNCTION enforce_import_resolution_agreement() RETURNS trigger AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM portfolio_import_resolution x
    JOIN portfolio_import_pending p ON p.id=x.pending_id
    JOIN portfolio_transaction t ON t.id=x.transaction_id
    JOIN position_receipt r ON r.id=x.receipt_id
    WHERE x.package_total_cents IS DISTINCT FROM p.package_net_cents
       OR x.allocated_total_cents IS DISTINCT FROM t.cash_effect_cents
       OR x.portfolio_account_id IS DISTINCT FROM t.portfolio_account_id
       OR p.status<>'RESOLVED' OR p.resolved_at IS NULL
       OR r.kind<>'RESOLUTION'
       OR r.authority IS DISTINCT FROM x.authority
       OR r.execution_lane<>'REAL'
       OR r.portfolio_account_id IS DISTINCT FROM x.portfolio_account_id
       OR r.transaction_id IS DISTINCT FROM x.transaction_id
  ) THEN
    RAISE EXCEPTION 'pending import resolution does not reconcile to its package, transaction, and receipt';
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_import_resolution_agreement
AFTER INSERT OR UPDATE ON portfolio_import_resolution
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION enforce_import_resolution_agreement();

CREATE CONSTRAINT TRIGGER trg_import_pending_resolution_agreement
AFTER UPDATE ON portfolio_import_pending
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION enforce_import_resolution_agreement();

-- Current structure allocations may never claim more units than remain in the authoritative lot.
CREATE FUNCTION enforce_current_structure_lot_allocations() RETURNS trigger AS $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM portfolio_structure_member m
    JOIN portfolio_structure_revision r ON r.id=m.revision_id
    JOIN portfolio_structure s ON s.id=r.structure_id AND s.current_revision_id=r.id
    JOIN portfolio_lot l ON l.id=m.lot_id
    GROUP BY m.lot_id,l.remaining_quantity
    HAVING SUM(m.allocated_quantity)>l.remaining_quantity
  ) THEN
    RAISE EXCEPTION 'current structure allocations exceed the remaining lot quantity';
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_structure_member_allocation
AFTER INSERT OR UPDATE OR DELETE ON portfolio_structure_member
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION enforce_current_structure_lot_allocations();

CREATE CONSTRAINT TRIGGER trg_structure_current_revision_allocation
AFTER INSERT OR UPDATE OR DELETE ON portfolio_structure
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION enforce_current_structure_lot_allocations();

CREATE CONSTRAINT TRIGGER trg_structure_lot_remaining_allocation
AFTER UPDATE OR DELETE ON portfolio_lot
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION enforce_current_structure_lot_allocations();
