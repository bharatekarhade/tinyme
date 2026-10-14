-- Kept in the system settings table so Delete all can signal other phones after truncating user data.
INSERT INTO settings (key, value) VALUES ('health.sync_epoch', '0'::jsonb)
ON CONFLICT (key) DO NOTHING;
