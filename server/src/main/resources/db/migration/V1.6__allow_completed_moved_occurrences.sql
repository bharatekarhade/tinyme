ALTER TABLE plan_occurrences DROP CONSTRAINT plan_occurrences_check1;
ALTER TABLE plan_occurrences ADD CONSTRAINT plan_occurrences_completed_at
  CHECK ((status = 'done' AND completed_at IS NOT NULL)
      OR (status = 'moved')
      OR (status = 'skipped' AND completed_at IS NULL));
