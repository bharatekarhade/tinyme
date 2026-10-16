ALTER TABLE confirmations DROP CONSTRAINT confirmations_tool_check;
ALTER TABLE confirmations ADD CONSTRAINT confirmations_tool_check
  CHECK (tool IN ('gmail_send', 'calendar_respond'));
