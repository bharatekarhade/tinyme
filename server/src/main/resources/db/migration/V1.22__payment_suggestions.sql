-- Card and bank payment notices found by the mail scan wait here until the owner confirms them.
ALTER TABLE suggestions DROP CONSTRAINT suggestions_type_check;
ALTER TABLE suggestions ADD CONSTRAINT suggestions_type_check
  CHECK (type IN ('event','task','reminder','payment'));
-- The expense entry a confirmed payment became.
ALTER TABLE suggestions ADD COLUMN entry_id uuid REFERENCES entries(id) ON DELETE SET NULL;
-- A follow-up email about the same charge (same card, reference and direction) updates it instead of adding one.
CREATE INDEX suggestions_payment_reference_idx
  ON suggestions (lower(coalesce(payload ->> 'card', '')), (payload ->> 'reference'), (payload ->> 'direction'))
  WHERE type = 'payment';
