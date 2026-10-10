ALTER TABLE account ADD COLUMN email text NOT NULL;
CREATE UNIQUE INDEX account_email_lower_uidx ON account (lower(email));
