ALTER TABLE plans ADD COLUMN google_event_id text;
ALTER TABLE plans ADD COLUMN google_etag text;
ALTER TABLE plans ADD COLUMN google_sync_at timestamptz;
CREATE UNIQUE INDEX plans_google_event_uidx ON plans (google_event_id) WHERE google_event_id IS NOT NULL;

CREATE TABLE calendar_sync_checkpoints (
  calendar_id text PRIMARY KEY,
  sync_token text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

CREATE TABLE calendar_sync_runs (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  status text NOT NULL CHECK (status IN ('running','succeeded','failed')),
  started_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  finished_at timestamptz,
  error_code text,
  details jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX calendar_sync_runs_started_idx ON calendar_sync_runs (started_at DESC);
