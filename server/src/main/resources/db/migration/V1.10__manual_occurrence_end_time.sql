ALTER TABLE plan_occurrences ADD COLUMN moved_end_time time;
ALTER TABLE plan_occurrences ADD CONSTRAINT plan_occurrences_moved_end_time
  CHECK (moved_end_time IS NULL
      OR (status = 'moved' AND moved_time IS NOT NULL AND moved_end_time > moved_time));
