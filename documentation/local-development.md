# Local Development

This setup runs UserService locally on `localhost:8082`, uses the remote ORISO dev database/API
services where needed, and allows the frontend on `localhost:9001` to call it directly.

## 1. Java

Use Java 21, which is the version compiled by `pom.xml` and used by CI:

```bash
/usr/libexec/java_home -v 21
```

The local run script example selects Java 21 on macOS when `JAVA_HOME` is not already set.

## 2. Create `run-local-remote-db.sh`

Copy the example script:

```bash
cp run-local-remote-db.sh.example run-local-remote-db.sh
chmod +x run-local-remote-db.sh
```

`run-local-remote-db.sh` is ignored by git because it may contain local secrets.

## 3. Create `config.env`

Copy the sample config:

```bash
cp config.env.example config.env
```

Fill all `CHANGE_ME` values and replace the sample Matrix settings with those for your approved test environment.

Important values for the mixed local setup:

```env
SERVER_SERVLET_CONTEXT_PATH=/service
MATRIX_API_URL=CHANGE_ME
MATRIX_SERVER_NAME=CHANGE_ME
MATRIX_REGISTRATION_SHARED_SECRET=CHANGE_ME
AGENCY_SERVICE_API_URL=https://api.oriso-dev.site/service
TENANT_SERVICE_API_URL=https://api.oriso-dev.site/service
REGISTRATION_CORS_ALLOWED_ORIGINS=http://localhost:9001,http://127.0.0.1:9001
REGISTRATION_CORS_ALLOWED_PATHS=/**
```

Use the API URL and configured Synapse server name for the same approved test environment.
The server name is the Matrix ID domain. Obtain the shared secret through the configured
credential store and keep it in the ignored local config.

`AGENCY_SERVICE_API_URL` must point at the AgencyService base path. The generated client appends
`/agencies`, `/agencies/{agencyIds}`, and `/internal/agencies/{id}/matrix-service-account` itself.

If TenantService is running locally on `localhost:8081`, override TenantService calls with:

```env
TENANT_SERVICE_API_URL=http://localhost:8081/service
```

The `/service` suffix is required because the local TenantService uses
`SERVER_SERVLET_CONTEXT_PATH=/service`.

If login or tenant lookup fails with a Java `PKIX path building failed` /
`unable to find valid certification path to requested target` error, create/import the dev CA into
a local truststore and uncomment the `JAVA_TOOL_OPTIONS` line in `config.env`:

```env
JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$HOME/.oriso/dev-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"
```

## 4. Run

```bash
./run-local-remote-db.sh
```

Expected local base URL:

```text
http://localhost:8082/service
```

## 5. Useful Checks

From a dedicated checkout with Java 21 and Python 3, use the same unit gate as
the GitHub Maven build. Pass one test class for a quick local check.

```bash
python3 scripts/ci/verify-maven-unit.py --test AccountInviteAccessPolicyTemplateKindTest
python3 scripts/ci/verify-maven-unit.py
```

The final JSON line shows the checkout commit, whether it has local changes,
the selected tests, the Java version and the outcome. A failure identifies
the failed step. GitHub writes the same report to the check summary.

Check whether UserService is listening:

```bash
lsof -nP -iTCP:8082 -sTCP:LISTEN
```

Stop the service with `Ctrl+C` in the terminal running the app.

### Run one required integration test locally

From the repository root, use Java 21 and Python 3 to run a named test from the
H2 assignment. Start in a terminal without the remote application configuration.

```bash
scripts/ci/run-required-integration-tests.sh --test AgencyDeletionCascadeAuthorizationIT
```

The command cleans this checkout’s Maven output and ends with a JSON result
containing the Git revision, whether it has local changes, the test selection
and the failure phase. The unit and integration starters share a per-worktree lock.
Use separate worktrees for concurrent Maven runs.
GitHub runs the full integration assignment through the same script.

## Frontend Pairing

Use the frontend local setup with:

```env
REACT_APP_USER_SERVICE_ORIGIN=http://localhost:8082
REACT_APP_TENANT_SERVICE_ORIGIN=http://localhost:8081
REACT_APP_LOCAL_TENANT_ID=1
```

If that frontend variable is removed or commented, the frontend falls back to the broad remote API
instead of calling this local UserService.
