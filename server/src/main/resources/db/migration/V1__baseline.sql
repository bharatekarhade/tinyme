CREATE EXTENSION IF NOT EXISTS vector;        -- also provisioned by Docker initialization
CREATE EXTENSION IF NOT EXISTS pg_trgm;       -- fuzzy / Japanese text matching
CREATE EXTENSION IF NOT EXISTS cube;          -- required by earthdistance
CREATE EXTENSION IF NOT EXISTS earthdistance; -- "places near me"

-- Production databases must provision these extensions or grant the migration
-- role permission to install them. Keep cube and earthdistance in a trusted schema.

CREATE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ── system ──────────────────────────────────────────────
CREATE TABLE settings (
                          key         text PRIMARY KEY,                 -- 'agent.chat', 'env.default', 'memory.main', 'user.tz'
                          value       jsonb NOT NULL,
                          updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE account (                          -- single row
                         id             smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
                         password_hash  text NOT NULL,
                         display_name   text,
                         created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
                                id           uuid PRIMARY KEY DEFAULT uuidv7(),
                                token_hash   bytea NOT NULL UNIQUE,           -- sha256(token)
                                device_name  text,
                                expires_at   timestamptz NOT NULL,
                                revoked_at   timestamptz,
                                created_at   timestamptz NOT NULL DEFAULT now()
);

-- ── agent bridge ────────────────────────────────────────
CREATE TABLE agent_sessions (
                                id                    uuid PRIMARY KEY DEFAULT uuidv7(),
                                anthropic_session_id  text NOT NULL UNIQUE,
                                agent                 text NOT NULL,          -- 'chat' (later: 'extractor', 'reporter')
                                agent_version         int  NOT NULL,
                                kind                  text NOT NULL CHECK (kind IN ('chat','job')),
                                local_day             date,
                                memory_mount_path     text,
                                status                text NOT NULL DEFAULT 'active' CHECK (status IN ('active','ended','deleted')),
                                created_at            timestamptz NOT NULL DEFAULT now(),
                                ended_at              timestamptz,
                                CHECK (kind <> 'chat' OR local_day IS NOT NULL)
);
CREATE UNIQUE INDEX one_active_chat_session_per_day
    ON agent_sessions (local_day) WHERE kind = 'chat' AND status = 'active';

CREATE TABLE messages (
                          id             uuid PRIMARY KEY DEFAULT uuidv7(),
                          session_id     uuid NOT NULL REFERENCES agent_sessions(id) ON DELETE CASCADE,
                          client_msg_id  uuid UNIQUE,                   -- X6: same message sent twice
                          role           text NOT NULL CHECK (role IN ('user','assistant')),
                          content        text NOT NULL,
                          actions        jsonb NOT NULL DEFAULT '[]',   -- action chips under the message
                          attachments    jsonb NOT NULL DEFAULT '[]',
                          created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX messages_session_idx ON messages (session_id, created_at);

CREATE TABLE tool_calls (
                            id                  uuid PRIMARY KEY DEFAULT uuidv7(),
                            session_id          uuid NOT NULL REFERENCES agent_sessions(id) ON DELETE CASCADE,
                            anthropic_event_id  text NOT NULL UNIQUE,     -- X6: replayed events run once
                            tool                text NOT NULL,
                            input               jsonb NOT NULL,
                            result              jsonb,
                            is_error            boolean NOT NULL DEFAULT false,
                            status              text NOT NULL CHECK (status IN ('running','waiting_device','waiting_confirmation','done','failed')),
                            started_at          timestamptz NOT NULL DEFAULT now(),
                            finished_at         timestamptz
);

-- ── life data ───────────────────────────────────────────
CREATE TABLE entry_kinds (                      -- filled automatically by entries_add
                             kind         text PRIMARY KEY CHECK (kind ~ '^[a-z][a-z0-9_]{1,31}$'),
  description  text,
  data_hints   jsonb NOT NULL DEFAULT '{}',     -- {"type":"string","hours":"number"}
  merged_into  text REFERENCES entry_kinds(kind) CHECK (merged_into <> kind),
  use_count    int  NOT NULL DEFAULT 0,
  created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE entries (
                         id          uuid PRIMARY KEY DEFAULT uuidv7(),
                         ts          timestamptz NOT NULL,             -- when it happened (L4 "last night")
                         local_day   date NOT NULL,                    -- your calendar day (X4, X5)
                         kind        text NOT NULL REFERENCES entry_kinds(kind) ON UPDATE CASCADE,
                         quantity    numeric NOT NULL DEFAULT 1 CHECK (quantity > 0),   -- "2 coffees" (L2, L6)
                         text        text,                             -- your words (J1, R1)
                         data        jsonb NOT NULL DEFAULT '{}',      -- {"type":"coffee"}, {"hours":6.5}
                         tags        text[] NOT NULL DEFAULT '{}',
                         source      text NOT NULL DEFAULT 'chat' CHECK (source IN ('chat','job','import','app')),
                         created_at  timestamptz NOT NULL DEFAULT now(),
                         updated_at  timestamptz NOT NULL DEFAULT now(),   -- L7 corrections
                         deleted_at  timestamptz,                          -- L8 delete, X7 undo
                         search      tsvector GENERATED ALWAYS AS
                             (to_tsvector('simple', kind || ' ' || coalesce(text,''))) STORED
);
CREATE INDEX entries_kind_day  ON entries (kind, local_day);
CREATE INDEX entries_ts        ON entries (ts DESC)         WHERE deleted_at IS NULL;
CREATE INDEX entries_data_gin  ON entries USING gin (data jsonb_path_ops);
CREATE INDEX entries_fts       ON entries USING gin (search);
CREATE INDEX entries_trgm      ON entries USING gin (text gin_trgm_ops);

CREATE TABLE people (
                        id            uuid PRIMARY KEY DEFAULT uuidv7(),
                        slug          text NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9-]{1,48}$'),
  display_name  text NOT NULL,                  -- P1
  aliases       text[] NOT NULL DEFAULT '{}',   -- P3 "Ken", X8
  relationship  text,                           -- P4 "friend from work" vs "cousin"
  memory_path   text NOT NULL,                  -- people/<slug>.md (P2, P6, P8)
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  deleted_at    timestamptz                     -- P9
);
CREATE INDEX people_name_trgm ON people USING gin (display_name gin_trgm_ops);
CREATE INDEX people_aliases   ON people USING gin (aliases);

CREATE TABLE entry_people (
                              entry_id   uuid REFERENCES entries(id) ON DELETE CASCADE,
                              person_id  uuid REFERENCES people(id)  ON DELETE CASCADE,
                              PRIMARY KEY (entry_id, person_id)
);

CREATE TABLE blobs (
                       id            uuid PRIMARY KEY DEFAULT uuidv7(),
                       key           text NOT NULL UNIQUE,           -- photos/2026/10/<uuid>.jpg — never a path or URL
                       type          text NOT NULL CHECK (type IN ('photo','thumb','export')),
                       content_type  text NOT NULL,
                       size_bytes    bigint NOT NULL CHECK (size_bytes >= 0),
                       sha256        text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
                       status        text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','ready','deleted')),
                       expires_at    timestamptz,                    -- pending uploads expire
                       created_at    timestamptz NOT NULL DEFAULT now(),
                       completed_at  timestamptz
);

CREATE TABLE photos (
                        id             uuid PRIMARY KEY DEFAULT uuidv7(),
                        blob_id        uuid NOT NULL REFERENCES blobs(id),
                        thumb_blob_id  uuid REFERENCES blobs(id),
                        caption        text,                          -- F1, F8 (null for F2)
                        taken_at       timestamptz,                   -- F7
                        lat            double precision CHECK (lat BETWEEN -90 AND 90),              -- from EXIF; not searched in Phase 1
                        lng            double precision CHECK (lng BETWEEN -180 AND 180),
                        entry_id       uuid REFERENCES entries(id) ON DELETE SET NULL,   -- F5
                        created_at     timestamptz NOT NULL DEFAULT now(),
                        deleted_at     timestamptz,                   -- F10
                        search         tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(caption,''))) STORED,
                        CHECK ((lat IS NULL) = (lng IS NULL))
);
CREATE INDEX photos_taken ON photos (taken_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX photos_fts   ON photos USING gin (search);

CREATE TABLE photo_people (
                              photo_id   uuid REFERENCES photos(id) ON DELETE CASCADE,
                              person_id  uuid REFERENCES people(id) ON DELETE CASCADE,
                              PRIMARY KEY (photo_id, person_id)
);

CREATE TABLE places (
                        id                 uuid PRIMARY KEY DEFAULT uuidv7(),
                        name               text NOT NULL,             -- V1 "Blue Bottle Kitasando"
                        address            text,
                        lat                double precision NOT NULL CHECK (lat BETWEEN -90 AND 90), -- coordinates come from the phone (MapKit)
                        lng                double precision NOT NULL CHECK (lng BETWEEN -180 AND 180),
                        note               text,                      -- V4 "for their pour-over"
                        provider_place_id  text,               -- V10 no duplicates
                        created_at         timestamptz NOT NULL DEFAULT now(),
                        deleted_at         timestamptz                -- V9 remove from list
);
CREATE UNIQUE INDEX places_provider_live
    ON places (provider_place_id) WHERE deleted_at IS NULL;
CREATE INDEX places_geo ON places USING gist (ll_to_earth(lat, lng)) WHERE deleted_at IS NULL;

-- ── phone actions (endpoints exist in Phase 1; producers arrive in Phase 3) ──
CREATE TABLE pending_actions (
                                 id            uuid PRIMARY KEY DEFAULT uuidv7(),
                                 tool_call_id  uuid REFERENCES tool_calls(id) ON DELETE SET NULL,
                                 type          text NOT NULL,
                                 payload       jsonb NOT NULL,
                                 origin        text NOT NULL CHECK (origin IN ('chat','job')),
                                 status        text NOT NULL DEFAULT 'pending'
                                     CHECK (status IN ('pending','delivered','succeeded','failed','expired')),
                                 result        jsonb,
                                 created_at    timestamptz NOT NULL DEFAULT now(),
                                 delivered_at  timestamptz,
                                 completed_at  timestamptz,
                                 expires_at    timestamptz NOT NULL
);
CREATE INDEX pending_actions_open ON pending_actions (created_at) WHERE status IN ('pending','delivered');

-- Full foreign-key indexes also cover soft-deleted rows.
CREATE INDEX tool_calls_session_idx ON tool_calls (session_id);
CREATE INDEX entry_kinds_merged_into_idx ON entry_kinds (merged_into);
CREATE INDEX entry_people_person_idx ON entry_people (person_id);
CREATE INDEX photos_blob_idx ON photos (blob_id);
CREATE INDEX photos_thumb_blob_idx ON photos (thumb_blob_id);
CREATE INDEX photos_entry_idx ON photos (entry_id);
CREATE INDEX photo_people_person_idx ON photo_people (person_id);
CREATE INDEX pending_actions_tool_call_idx ON pending_actions (tool_call_id);

CREATE TRIGGER settings_updated_at BEFORE UPDATE ON settings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER entries_updated_at BEFORE UPDATE ON entries
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER people_updated_at BEFORE UPDATE ON people
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
