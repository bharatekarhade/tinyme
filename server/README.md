# Tinyme server

Java 25 and Spring Boot 4.1, built with Maven. Dependency and plugin
versions are configured in `pom.xml`. Java packages use `com.tinyme`.

## Package layout

`TinymeApplication` stays at the package root so Spring discovers all features.
Code is grouped by feature, then responsibility:

```text
com.tinyme
├── TinymeApplication
├── agent
│   ├── bootstrap   # Provisioning, startup runner, dev CLI runner
│   ├── client      # Anthropic HTTP client and event stream
│   ├── config      # Spring bean configuration
│   ├── entity      # JPA entities
│   ├── model       # Records passed between components
│   ├── repository  # Database access
│   └── service     # Resources, context, sessions, and turns
├── domain.entries
│   ├── entity
│   ├── model
│   ├── repository
│   └── service
└── tools
    ├── entity
    ├── handlers    # Tool implementations
    ├── model       # Tool records and interfaces
    ├── repository
    └── service     # Loading, registry, validation, and dispatch
```

Tests mirror these packages. Shared agent test configuration lives in
`src/test/java/com/tinyme/agent/support`.

## Running the server

The Maven wrapper downloads Maven 3.9.11 on first use; no separate Maven
installation is required. On Windows, use `mvnw.cmd` instead of `./mvnw`.

Install JDK 25 and set `JAVA_HOME` to its installation directory, then run from `server/`:

```sh
./mvnw verify
./mvnw spring-boot:run
```

The server listens on port 8080 by default.

The application reads `.env` from the working directory or its parent, supporting
IntelliJ from the project root and `spring-boot:run` from `server/`. Database settings
default to localhost:5432 and use the `POSTGRES_*` keys. See
[`../deploy/README.md`](../deploy/README.md) for starting Dockerized Postgres with
the localhost-only port override. For local blob storage, set
`TINYME_BLOBS_PATH=./blobs` in `.env`.

## Tests

`./mvnw verify` requires Docker: the application test starts an isolated
PostgreSQL 18 + pgvector container and checks that Flyway applied the baseline.
GitHub Actions runs this check. Docker image builds use `package -DskipTests`
because Testcontainers requires a Docker daemon outside the image build.

## Managed Agents setup

Set `ANTHROPIC_API_KEY` in the root `.env`. Setup is enabled by default and runs
after Flyway, loading packaged seeds from `src/main/resources/seed`. Changed
agent/environment seeds are updated; IDs, versions and hashes persist in
PostgreSQL settings. `.env` is never rewritten. Use
`TINYME_AGENT_SETUP_ENABLED=false` to disable live setup. See
[seed/README.md](src/main/resources/seed/README.md) for restart and recovery behavior.

## Send a message from the terminal

With PostgreSQL running and `ANTHROPIC_API_KEY` configured, run from `server/`:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments="had a coffee"
```

The `dev` profile runs agent setup, sends one message through `TurnRunner`, prints
the reply, and closes the application. It does not start the HTTP server. This
uses the real database and Anthropic API, so tool calls can save entries.

The timezone defaults to your computer's timezone. To override it, include
`--tinyme.dev.zone=Asia/Tokyo` in `spring-boot.run.arguments` along with the message.
Spring `--name=value` options are excluded from the message. A missing message or
a failed turn fails the command; application resources are still closed.
