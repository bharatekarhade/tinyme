ALTER TABLE scanned_messages
  ADD COLUMN attempt_count smallint NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 2);
