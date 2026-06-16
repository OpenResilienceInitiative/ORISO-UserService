# Understand-Anything Graph: ORISO-UserService

This directory contains the Understand-Anything graph and developer notes for `ORISO-UserService`.

## Graph

- Graph file: `.understand-anything/knowledge-graph.json`
- Generated at: `2026-06-11T18:45:43.355Z`
- Source commit: `3e6707fbb14400428ddfe44e0dc36648e4aba41c`
- Files analyzed: 1141
- Nodes: 4806
- Edges: 5842

## Repository Purpose

The UserService provides different functionalities from creating and updating user accounts and their sessions, providing session lists up to creating and editing Rocket.Chat groups.

## Existing Setup Status

Previous graph commit: `65bd6a263f0a362d086121bdafb20992f00949a5`; regenerated at latest dev commit `3e6707fbb14400428ddfe44e0dc36648e4aba41c`.

## Dashboard

From this repository root:

```sh
PLUGIN_ROOT="$HOME/.understand-anything-plugin"
test -d "$PLUGIN_ROOT/packages/dashboard" || PLUGIN_ROOT="$HOME/.understand-anything/repo/understand-anything-plugin"
cd "$PLUGIN_ROOT/packages/dashboard"
GRAPH_DIR="$(pwd)" npx vite --host 127.0.0.1
```

Use the tokenized URL printed by Vite. The dashboard reads `.understand-anything/knowledge-graph.json`.

## Main Files Scanned

- `.github/actions/docker-build-push/action.yml (config, yaml)`
- `.github/actions/maven-build/action.yml (config, yaml)`
- `.github/workflows/ci-feature-branch.yml (pipeline, yaml)`
- `.github/workflows/ci-main.yml (pipeline, yaml)`
- `.github/workflows/ci-pull-request.yml (pipeline, yaml)`
- `.gitignore (code, unknown)`
- `.mvn/wrapper/maven-wrapper.properties (config, properties)`
- `.swagger-codegen-ignore (code, unknown)`
- `api/appointmentservice.yaml (config, yaml)`
- `api/conversationservice.yaml (config, yaml)`
- `api/useradminservice.yaml (config, yaml)`
- `api/userservice.yaml (config, yaml)`
