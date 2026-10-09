----------------------------------------------------
    -- entries: drop the search_fts index and create new search column with data and update search_fts
----------------------------------------------------
DROP INDEX entries_fts;
ALTER TABLE entries DROP COLUMN search;
ALTER TABLE entries ADD COLUMN search tsvector GENERATED ALWAYS AS(
        to_tsvector('simple', kind || ' ' || coalesce(text,'')) || jsonb_to_tsvector('simple', data, '["string"]')
    ) STORED;
CREATE INDEX entries_fts ON entries USING gin (search);