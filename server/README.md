# Tinyme server

Java 25 and Spring Boot 4.1, built with Maven. Dependency and plugin
versions are configured in `pom.xml`. Java packages use `com.tinyme`.

The Maven wrapper downloads Maven 3.9.11 on first use; no separate Maven
installation is required. On Windows, use `mvnw.cmd` instead of `./mvnw`.

Install JDK 25 and set `JAVA_HOME` to its installation directory, then run from `server/`:

```sh
./mvnw verify
./mvnw spring-boot:run
```

The server listens on port 8080 by default.

`spring-boot:run` reads `../.env` automatically when run from `server/`. Database settings
default to localhost:5432 and use the `POSTGRES_*` keys. See
[`../deploy/README.md`](../deploy/README.md) for starting Dockerized Postgres with
the localhost-only port override. For local blob storage, set
`TINYME_BLOBS_PATH=./blobs` in `.env`.

## Tests

`./mvnw verify` requires Docker: the application test starts an isolated
PostgreSQL 18 + pgvector container and checks that Flyway applied the baseline.
GitHub Actions runs this check. Docker image builds use `package -DskipTests`
because Testcontainers requires a Docker daemon outside the image build.
