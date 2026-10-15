-- The neighbourhood and city the phone reads off the map ("Jingumae, Shibuya"), so "places in Kyoto" can match it.
ALTER TABLE places ADD COLUMN area text;
ALTER TABLE places ADD CONSTRAINT places_area_length CHECK (area IS NULL OR length(area) <= 120);
