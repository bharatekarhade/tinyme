-- V1.1__change_message_roles_to_upper_case.sql
ALTER TABLE messages DROP CONSTRAINT messages_role_check;
ALTER TABLE messages ADD CONSTRAINT messages_role_check CHECK (role IN ('USER','ASSISTANT'));