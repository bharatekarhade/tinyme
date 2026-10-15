ALTER TABLE tool_calls
  ADD COLUMN agent text,
  ADD COLUMN thread_id text;
