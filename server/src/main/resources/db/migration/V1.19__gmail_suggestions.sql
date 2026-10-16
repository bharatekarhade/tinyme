CREATE TABLE mail_scan_state (
  id smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  history_id text NOT NULL,
  initial_page_token text,
  initial_complete boolean NOT NULL DEFAULT false,
  history_page_token text,
  pending_history_id text,
  pending_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
  updated_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

CREATE TABLE scanned_messages (
  gmail_message_id text PRIMARY KEY,
  scanned_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  outcome text NOT NULL CHECK (outcome IN ('skipped','no_suggestion','suggested','rejected','failed'))
);

CREATE TABLE suggestions (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  source text NOT NULL DEFAULT 'gmail' CHECK (source = 'gmail'),
  gmail_message_id text NOT NULL,
  type text NOT NULL CHECK (type IN ('event','task','reminder')),
  payload jsonb NOT NULL,
  confidence numeric NOT NULL CHECK (confidence BETWEEN 0 AND 1),
  evidence text NOT NULL CHECK (char_length(evidence) <= 200),
  status text NOT NULL DEFAULT 'new' CHECK (status IN ('new','accepted','dismissed','expired')),
  plan_id uuid REFERENCES plans(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT statement_timestamp()
);
CREATE INDEX suggestions_status_created_idx ON suggestions (status, created_at DESC);
CREATE UNIQUE INDEX suggestions_message_payload_uidx ON suggestions (gmail_message_id, payload);

CREATE TABLE mail_scan_runs (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  status text NOT NULL CHECK (status IN ('running','succeeded','partial','failed')),
  started_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  finished_at timestamptz,
  scanned integer NOT NULL DEFAULT 0,
  suggested integer NOT NULL DEFAULT 0,
  rejected integer NOT NULL DEFAULT 0,
  error_code text
);
CREATE INDEX mail_scan_runs_started_idx ON mail_scan_runs (started_at DESC);
