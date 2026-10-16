-- Job postings found in job-alert emails (LinkedIn "Jobs for you"), each with the owner's status.
CREATE TABLE job_leads (
  id                uuid PRIMARY KEY DEFAULT uuidv7(),
  -- linkedin:<job id>, or company|title when the email has no job link. Unique even after delete,
  -- so a deleted job that shows up in tomorrow's alert again stays gone.
  dedupe_key        text NOT NULL UNIQUE,
  title             text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
  company           text NOT NULL CHECK (char_length(company) BETWEEN 1 AND 200),
  location          text CHECK (location IS NULL OR char_length(location) <= 200),
  url               text CHECK (url IS NULL OR char_length(url) <= 1000),
  gmail_message_id  text NOT NULL,
  status            text NOT NULL DEFAULT 'new'
                    CHECK (status IN ('new','interested','applied','interviewing','offer','rejected','not_interested')),
  found_at          timestamptz NOT NULL DEFAULT statement_timestamp(),
  status_changed_at timestamptz,
  deleted_at        timestamptz
);
CREATE INDEX job_leads_list_idx ON job_leads (found_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX job_leads_status_idx ON job_leads (status, found_at DESC, id DESC) WHERE deleted_at IS NULL;
