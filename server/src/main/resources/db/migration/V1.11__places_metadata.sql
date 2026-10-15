ALTER TABLE places ADD COLUMN category text;
ALTER TABLE places ADD COLUMN url text;
ALTER TABLE places ADD COLUMN source text NOT NULL DEFAULT 'chat'
    CONSTRAINT places_source_known CHECK (source IN ('chat', 'app', 'import'));
ALTER TABLE places ADD COLUMN visited_at timestamptz;
ALTER TABLE places ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE places ADD CONSTRAINT places_name_length CHECK (length(name) BETWEEN 1 AND 200);

CREATE TRIGGER places_updated_at
    BEFORE UPDATE ON places
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
