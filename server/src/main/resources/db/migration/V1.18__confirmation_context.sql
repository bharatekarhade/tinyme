ALTER TABLE confirmations DROP CONSTRAINT confirmations_status_check;
ALTER TABLE confirmations ADD CONSTRAINT confirmations_status_check
  CHECK (status IN ('pending', 'approved', 'sent', 'responded', 'rejected', 'expired', 'failed'));
ALTER TABLE confirmations ADD COLUMN context_notified_at timestamptz;
