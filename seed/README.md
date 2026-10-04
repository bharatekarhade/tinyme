# Agent seed files

The future `bin/setup.sh` must upload local memory templates using this mapping:

| Local template | Agent path |
| --- | --- |
| `memories/profile.md` | `/me/profile.md` |
| `memories/preferences.md` | `/me/preferences.md` |
| `memories/rules.md` | `/me/rules.md` |
| `memories/routines.md` | `/me/routines.md` |

The prompt's relative `me/` paths assume the memory mount root is the agent's
working directory. Setup must preserve existing user memory when rerun.
The setup script and remote upload implementation do not exist yet.

Photo and place tools are commented out until handlers exist and the source of
place-search candidates is decided. Entries and people definitions remain as
implementation contracts; their handlers and ToolRegistry still need to be
implemented before using the agent end to end.
