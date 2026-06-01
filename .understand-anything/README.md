# ORISO UserService Knowledge Graph

Developer map for the ORISO UserService repository. This folder is generated for onboarding and architecture exploration.

## Navigation

- [Architecture Summary](./ARCHITECTURE.md)
- [Developer Onboarding](./ONBOARDING.md)
- [ORISO Ecosystem Connections](./ORISO-ECOSYSTEM.md)
- [Findings And Maintenance Risks](./FINDINGS.md)
- [Dependency Audit Notes](./DEPENDENCY-AUDIT.md)
- [Visuals](./visuals/)
- [Raw Knowledge Graph](./knowledge-graph.json)

## Graph Files

- `knowledge-graph.json` is the dashboard data file.
- `meta.json` records analysis time, git commit, file count, and graph stats.
- `fingerprints.json` records file hashes for future incremental updates.
- `config.json` enables Understand-Anything auto-update behavior for this repository.

## Open The Dashboard

Run this from any terminal:

```bash
ORISO_USERSERVICE="$HOME/Developer/freelance/Germany/Oriso-frank-client/ORISO/ORISO-UserService"
cd ~/.understand-anything/repo/understand-anything-plugin/packages/dashboard
GRAPH_DIR="$ORISO_USERSERVICE" pnpm exec vite --host 127.0.0.1
```

Open the full `Dashboard URL` printed in the terminal, including the `?token=...` value.

## Auto Update

Auto-update is enabled in `config.json`. In an agent environment, the matching command is:

```bash
/understand . --auto-update
```

If the graph looks stale or your environment does not run the auto-update hook, rebuild it manually:

```bash
/understand . --full
```

