CREATE TABLE google_account (
  id                 smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  email              text NOT NULL,
  scopes             text[] NOT NULL,
  refresh_token_enc  bytea NOT NULL,
  status             text NOT NULL CHECK (status IN ('connected','needs_reauth')),
  tinyme_calendar_id text,
  connected_at       timestamptz NOT NULL,
  updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER google_account_updated_at BEFORE UPDATE ON google_account
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE oauth_states (
  state         text PRIMARY KEY,
  code_verifier text NOT NULL,
  expires_at    timestamptz NOT NULL
);
