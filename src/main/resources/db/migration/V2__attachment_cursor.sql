ALTER TABLE attachments ADD COLUMN revision bigint NOT NULL DEFAULT nextval('change_revision');
CREATE INDEX attachments_revision ON attachments(revision);
