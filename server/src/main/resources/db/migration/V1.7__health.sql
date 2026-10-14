-- Apple Health: entries made from Health data, what is connected, the sync log and follow-up history.

ALTER TABLE entries DROP CONSTRAINT entries_source_check;
ALTER TABLE entries ADD CONSTRAINT entries_source_check
  CHECK (source IN ('chat','job','import','app','health'));

-- A stable id from the phone ("steps:2026-10-14" or a HealthKit UUID), so sending the same thing twice changes nothing.
ALTER TABLE entries ADD COLUMN external_id text;
CREATE UNIQUE INDEX entries_external_uidx ON entries (source, external_id)
  WHERE external_id IS NOT NULL AND deleted_at IS NULL;
ALTER TABLE entries ADD CONSTRAINT entries_health_has_external_id
  CHECK (source <> 'health' OR external_id IS NOT NULL);

CREATE TABLE health_settings (
  id            smallint PRIMARY KEY DEFAULT 1,
  groups        text[] NOT NULL DEFAULT '{}',        -- activity, workouts, body, sleep_heart
  write_enabled boolean NOT NULL DEFAULT false,
  nudges_off    text[] NOT NULL DEFAULT '{}',        -- workouts, sleep, weight, steps
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT health_settings_single_row CHECK (id = 1),
  CONSTRAINT health_settings_groups_known
    CHECK (groups <@ ARRAY['activity','workouts','body','sleep_heart']::text[]),
  CONSTRAINT health_settings_nudges_known
    CHECK (nudges_off <@ ARRAY['workouts','sleep','weight','steps']::text[])
);

CREATE TABLE health_sync_log (
  kind          text PRIMARY KEY REFERENCES entry_kinds(kind) ON UPDATE CASCADE,   -- e.g. steps
  last_sync_at  timestamptz NOT NULL,
  last_day      date,                                -- newest day with data
  items_total   bigint NOT NULL DEFAULT 0,
  CONSTRAINT health_sync_log_items_total_not_negative CHECK (items_total >= 0)
);

CREATE TABLE nudge_log (
  nudge_key     text NOT NULL,                       -- e.g. no_workout_streak
  day           date NOT NULL,
  shown_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT nudge_log_pkey PRIMARY KEY (nudge_key, day)
);
