-- Daily exchange rates, fetched once per (day, from, to) and kept, so a payment's conversion never changes later.
CREATE TABLE exchange_rates (
  day        date    NOT NULL,
  base       text    NOT NULL CHECK (base ~ '^[A-Z]{3}$'),
  quote      text    NOT NULL CHECK (quote ~ '^[A-Z]{3}$'),
  rate       numeric NOT NULL CHECK (rate > 0),
  rate_day   date    NOT NULL,           -- the market day the provider used (weekends fall back to Friday)
  source     text    NOT NULL,
  fetched_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  PRIMARY KEY (day, base, quote)
);
