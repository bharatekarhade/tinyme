CREATE TABLE confirmations (
  id uuid PRIMARY KEY,
  session_id uuid NOT NULL REFERENCES agent_sessions(id) ON DELETE CASCADE,
  thread_id text,
  tool_call_id uuid NOT NULL REFERENCES tool_calls(id) ON DELETE CASCADE,
  tool text NOT NULL CHECK (tool = 'gmail_send'),
  payload jsonb NOT NULL,
  status text NOT NULL CHECK (status IN ('pending', 'approved', 'rejected', 'expired', 'failed')),
  created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  decided_at timestamptz,
  expires_at timestamptz NOT NULL
);

CREATE UNIQUE INDEX confirmations_one_pending ON confirmations ((status)) WHERE status = 'pending';
CREATE INDEX confirmations_pending_expiry ON confirmations (expires_at) WHERE status = 'pending';
