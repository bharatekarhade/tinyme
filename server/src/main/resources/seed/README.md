# Agent startup setup

Seeds are packaged in the application JAR under `seed/`. Supply
`ANTHROPIC_API_KEY` in the root `.env` or process environment and start the
application. Setup is enabled by default; `TINYME_AGENT_SETUP_ENABLED=false`
disables it for offline work. Tests explicitly disable live setup.

After Flyway, the startup runner reconciles the environment and agent from YAML
and seeds `/me/<filename>` from `memories/*.md`. It does not invoke the model or
start an agent session. Canonical JSON with sorted map keys is hashed with SHA-256:
only a changed seed causes an update. Agent updates include the current remote
version for optimistic concurrency; environments have no version.

PostgreSQL settings store resource records under `env.default`, `agent.chat` and
`memory.main`. Config records contain `id` and `seedHash`; agents also contain
`version`. Legacy JSON-string IDs are adopted and reconciled without creating
duplicates. Each record is saved immediately, under a PostgreSQL advisory lock.

Optional `ANTHROPIC_ENVIRONMENT_ID`, `ANTHROPIC_AGENT_ID` and
`ANTHROPIC_MEMORY_STORE_ID` configuration values are used only if that setting
is absent. Saved database IDs always take precedence; conflicting configured
IDs produce a warning. The application never writes `.env`.

Missing or archived agents and environments are recreated. A missing or archived
memory store fails startup with recovery instructions. Set
`TINYME_ALLOW_NEW_MEMORY_STORE=true` explicitly to create a replacement seeded
store; this does not recover previous memories. Restore the original store/ID
instead if you need that data. Turn the replacement flag off after recovery.

Existing memory files are never overwritten. Seeding currently runs on each
startup and treats only memory-path conflicts as already seeded. Runtime session
creation must attach `memory.main` as a memory-store resource and use its
returned `mount_path` as the base for `me/` and `people/`.

Transient GET failures (network errors, 429, 5xx) get up to four attempts with
backoff. POSTs are never automatically retried. A lost creation response can
leave an unrecorded remote resource: recover its ID from the Anthropic Console
before retrying. If a setting already exists, repair it in the database;
configuration IDs cannot override saved settings.

Enabled setup failures fail startup. `ManagedAgentApi` is the single API layer;
future session/event calls should extend it rather than add another HTTP stack.
Photo/place tools remain disabled; entries/people handlers and ToolRegistry are
still required before the agent can be used end to end.

References: [Managed Agents](https://platform.claude.com/docs/en/managed-agents/quickstart)
and [memory stores](https://platform.claude.com/docs/en/managed-agents/memory).
