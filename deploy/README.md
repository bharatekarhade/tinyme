# Local deployment

From the repository root:

```sh
cp .env.example .env
docker compose --env-file .env -f deploy/docker-compose.yml up --build -d
docker compose --env-file .env -f deploy/docker-compose.yml logs -f api
docker compose --env-file .env -f deploy/docker-compose.yml down
```

Every deployment configuration key is documented in the root `.env.example`.
Edit `.env` before starting; it is ignored by Git.

To run the API with `spring-boot:run` against Dockerized Postgres, start only the database
with its localhost-only port override:

```sh
docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.dev.yml up -d postgres
cd server
./mvnw spring-boot:run
```

When run from `server/`, the API automatically reads the repository root `.env`.
Explicit `SPRING_DATASOURCE_*` environment variables override the `POSTGRES_*`
fallbacks. Set `TINYME_BLOBS_PATH` in `.env` to a writable local directory
(for example `./blobs`) when using `spring-boot:run`.

The API listens on http://localhost:8080. Postgres is available to the API at
`postgres:5432` on the Compose network. The database, username, and password default
to `tinyme`; override them with `POSTGRES_DB`, `POSTGRES_USER`, and
`POSTGRES_PASSWORD`. Set `API_PORT` to change the published API port.

The `postgres_data` volume persists the database; `blobs` persists files at
`/app/blobs` in the API container by default. `TINYME_BLOBS_PATH` changes both the
volume mount directory and the application's `tinyme.blobs.path` setting.
The vector extension is enabled when the database volume is first initialized.
`docker compose down` preserves both volumes; adding `-v` deletes their contents.
