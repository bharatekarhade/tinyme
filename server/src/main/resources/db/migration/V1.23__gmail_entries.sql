-- Spending confirmed from a card email is stored with source 'gmail'.
ALTER TABLE entries DROP CONSTRAINT entries_source_check;
ALTER TABLE entries ADD CONSTRAINT entries_source_check
  CHECK (source IN ('chat','job','import','app','health','gmail'));
