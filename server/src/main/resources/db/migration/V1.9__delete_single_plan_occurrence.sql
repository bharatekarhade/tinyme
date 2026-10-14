ALTER TABLE plan_occurrences DROP CONSTRAINT plan_occurrences_status_check;
ALTER TABLE plan_occurrences ADD CONSTRAINT plan_occurrences_status_check
  CHECK (status IN ('done', 'skipped', 'moved', 'deleted'));

ALTER TABLE plan_occurrences DROP CONSTRAINT plan_occurrences_completed_at;
ALTER TABLE plan_occurrences ADD CONSTRAINT plan_occurrences_completed_at
  CHECK ((status = 'done' AND completed_at IS NOT NULL)
      OR status = 'moved'
      OR (status IN ('skipped', 'deleted') AND completed_at IS NULL));
