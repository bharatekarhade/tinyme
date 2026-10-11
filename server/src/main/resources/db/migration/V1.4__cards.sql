CREATE TABLE cards (
  id           uuid PRIMARY KEY DEFAULT uuidv7(),
  slug         text NOT NULL,
  title        text NOT NULL,
  spec          jsonb NOT NULL,
  status        text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','active')),
  system        boolean NOT NULL DEFAULT false,
  position      int NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  activated_at  timestamptz,
  deleted_at    timestamptz,
  CONSTRAINT cards_status_activation_ck
    CHECK ((status = 'active') = (activated_at IS NOT NULL)),
  CONSTRAINT cards_position_nonnegative_ck CHECK (position >= 0)
);

CREATE UNIQUE INDEX cards_slug_live_uidx
  ON cards (slug) WHERE deleted_at IS NULL;

CREATE TRIGGER cards_updated_at BEFORE UPDATE ON cards
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
