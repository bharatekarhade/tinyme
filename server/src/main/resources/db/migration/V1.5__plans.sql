CREATE TABLE plans (
  id              uuid PRIMARY KEY DEFAULT uuidv7(),
  kind            text NOT NULL CHECK (kind IN ('event','task')),
  title           text NOT NULL CHECK (length(title) BETWEEN 1 AND 120),
  notes           text,
  place           text,
  zone            text NOT NULL,
  start_day       date NOT NULL,
  start_time      time,
  end_time        time,
  end_day         date,
  repeat_freq     text CHECK (repeat_freq IN ('daily','weekly','monthly','yearly')),
  repeat_interval int NOT NULL DEFAULT 1 CHECK (repeat_interval BETWEEN 1 AND 52),
  repeat_days     smallint,
  repeat_until    date,
  habit_kind      text,
  remind_minutes  int CHECK (remind_minutes BETWEEN 0 AND 10080),
  status          text NOT NULL DEFAULT 'active' CHECK (status IN ('active','done','cancelled')),
  completed_at    timestamptz,
  source          text NOT NULL DEFAULT 'chat' CHECK (source IN ('chat','app','google')),
  external_id     text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  deleted_at      timestamptz,
  CHECK (end_day IS NULL OR end_day >= start_day),
  CHECK (end_time IS NULL OR start_time IS NOT NULL),
  CHECK (end_day IS NOT NULL OR end_time IS NULL OR end_time > start_time),
  CHECK (end_time IS NULL OR start_time IS NULL OR end_time > start_time OR end_day > start_day),
  CHECK (kind = 'event' OR (end_time IS NULL AND end_day IS NULL)),
  CHECK (repeat_until IS NULL OR repeat_until >= start_day),
  CHECK (repeat_days IS NULL OR repeat_days BETWEEN 1 AND 127),
  CHECK (repeat_freq IS NULL OR end_day IS NULL),
  CHECK (repeat_freq IS NOT NULL OR (repeat_days IS NULL AND repeat_until IS NULL)),
  CHECK (repeat_freq = 'weekly' OR repeat_days IS NULL),
  CHECK (status <> 'done' OR repeat_freq IS NULL),
  CHECK ((status = 'done') = (completed_at IS NOT NULL))
);

CREATE INDEX plans_live_start ON plans (start_day) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX plans_external_uidx ON plans (source, external_id) WHERE external_id IS NOT NULL;
CREATE TRIGGER plans_updated_at BEFORE UPDATE ON plans
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE plan_people (
  plan_id   uuid NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
  person_id uuid NOT NULL REFERENCES people(id) ON DELETE CASCADE,
  PRIMARY KEY (plan_id, person_id)
);

CREATE TABLE plan_occurrences (
  plan_id       uuid NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
  day           date NOT NULL,
  status        text NOT NULL CHECK (status IN ('done','skipped','moved')),
  moved_day     date,
  moved_time    time,
  completed_at  timestamptz,
  PRIMARY KEY (plan_id, day),
  CHECK ((status = 'moved') = (moved_day IS NOT NULL)),
  CHECK ((status = 'done') = (completed_at IS NOT NULL)),
  CHECK (status = 'moved' OR moved_time IS NULL)
);

CREATE INDEX plan_occurrences_moved_day ON plan_occurrences (moved_day) WHERE status = 'moved';

ALTER TABLE people ADD COLUMN birthday_month smallint CHECK (birthday_month BETWEEN 1 AND 12);
ALTER TABLE people ADD COLUMN birthday_day smallint CHECK (birthday_day BETWEEN 1 AND 31);
ALTER TABLE people ADD COLUMN birthday_year smallint CHECK (birthday_year BETWEEN 1900 AND 2100);
ALTER TABLE people ADD CONSTRAINT people_birthday_pair
  CHECK ((birthday_month IS NULL) = (birthday_day IS NULL));
ALTER TABLE people ADD CONSTRAINT people_birthday_year_requires_date
  CHECK (birthday_year IS NULL OR birthday_month IS NOT NULL);
ALTER TABLE people ADD CONSTRAINT people_birthday_day_in_month
  CHECK (birthday_month IS NULL OR birthday_day <= CASE birthday_month
    WHEN 2 THEN 29 WHEN 4 THEN 30 WHEN 6 THEN 30 WHEN 9 THEN 30 WHEN 11 THEN 30 ELSE 31 END);
ALTER TABLE people ADD CONSTRAINT people_birthday_leap_year
  CHECK (birthday_month IS DISTINCT FROM 2 OR birthday_day IS DISTINCT FROM 29 OR birthday_year IS NULL
    OR (birthday_year % 4 = 0 AND (birthday_year % 100 <> 0 OR birthday_year % 400 = 0)));
