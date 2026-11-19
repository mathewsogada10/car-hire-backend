CREATE SEQUENCE change_revision;
CREATE TABLE records (
  id uuid PRIMARY KEY,
  kind varchar(32) NOT NULL,
  revision bigint NOT NULL DEFAULT nextval('change_revision'),
  data jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz
);
CREATE INDEX records_kind_revision ON records(kind, revision);
CREATE INDEX records_revision ON records(revision);
CREATE INDEX records_data ON records USING gin(data);
CREATE UNIQUE INDEX vehicle_registration ON records ((data->>'registration')) WHERE kind='vehicles' AND deleted_at IS NULL;
CREATE TABLE operations (id uuid PRIMARY KEY, request_hash varchar(64) NOT NULL, result jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE audit_events (id uuid PRIMARY KEY, actor text NOT NULL, action text NOT NULL, entity_id uuid, before_hash text, after_hash text, reason text, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE export_batches (id uuid PRIMARY KEY, state text NOT NULL, manifest jsonb NOT NULL, snapshot jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE import_batches (id uuid PRIMARY KEY, source_id uuid NOT NULL UNIQUE, package_hash text NOT NULL, result jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE purge_batches (id uuid PRIMARY KEY, export_id uuid NOT NULL REFERENCES export_batches(id), preview_hash text NOT NULL, outcome jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE attachments (id uuid PRIMARY KEY, parent_id uuid NOT NULL REFERENCES records(id), filename text NOT NULL, mime text NOT NULL, size bigint NOT NULL, sha256 varchar(64) NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE file_cleanup (id uuid PRIMARY KEY, attempts integer NOT NULL DEFAULT 0, last_error text);
CREATE FUNCTION prevent_evidence_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Evidence is append-only'; END $$;
CREATE TRIGGER audit_immutable BEFORE UPDATE OR DELETE ON audit_events FOR EACH ROW EXECUTE FUNCTION prevent_evidence_mutation();
CREATE TRIGGER purge_immutable BEFORE UPDATE OR DELETE ON purge_batches FOR EACH ROW EXECUTE FUNCTION prevent_evidence_mutation();
