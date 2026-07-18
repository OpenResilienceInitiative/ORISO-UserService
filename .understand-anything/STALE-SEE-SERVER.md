# ⚠️ STALE — do not read this graph

The `knowledge-graph.json` in this local `.understand-anything/` folder is a
**stale local checkout** (June 2026) and is NOT the source of truth. Reading it
will report an out-of-date picture of the platform.

**Source of truth = the Understand-Anything server**, not this Mac clone:
- Live boards: https://understand.oriso.org (all 10 repos + cross-repo super-graph)
- Server: `oriso-understand-dev-1` (49.13.11.37), `/opt/oriso-understand/<Repo>/.understand-anything/`
- The server graphs are rebuilt from current `dev`/`main` by the committed pipeline
  in `ORISO-Docs/tools/understand-anything/` (nightly cron + `ua-generate.mjs`).

These local files are git-tracked and left untouched on purpose (they sit on
active feature branches). Just do not treat them as current.

— note added 2026-07-16
