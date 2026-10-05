# Agent setup

Install Python 3 and PyYAML in a virtual environment, then fill in
`ANTHROPIC_API_KEY` in the project-root `.env`:

```sh
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install -r bin/requirements.txt
./bin/setup.sh --dry-run
./bin/setup.sh
```

Setup creates the cloud environment and chat agent from their YAML seeds,
creates a memory store, and saves `ANTHROPIC_ENVIRONMENT_ID`, `ANTHROPIC_AGENT_ID`
and `ANTHROPIC_MEMORY_STORE_ID` to `.env` after each successful creation.
Existing IDs are verified and reused; reruns do not update agent/environment
configuration. If a create request has an ambiguous network failure, check the
Console for the resource and set its ID before rerunning to avoid duplicates.
The script does not start a session or invoke the model.

Templates in `` are seeded at `/me/<filename>` inside the store.
Existing memory paths are preserved, including user edits. API keys are never
uploaded to memory. Runtime code must attach the saved memory store in the
session's resources and use the returned `mount_path` as the base for `me/` and
`people/` paths; it is not the sandbox working directory.

Photo and place tools remain commented out until handlers exist and the source
of place-search candidates is decided. Entries and people definitions remain
implementation contracts; their Java handlers and ToolRegistry are still needed.

API references: [Managed Agents](https://platform.claude.com/docs/en/managed-agents/quickstart)
and [memory stores](https://platform.claude.com/docs/en/managed-agents/memory).
