# Local Development

This setup runs UserService locally on `localhost:8082`, uses the remote ORISO dev database/API
services where needed, and allows the frontend on `localhost:9001` to call it directly.

## 1. Java

Use Java 17:

```bash
/usr/libexec/java_home -V
```

The local run script example auto-detects Java 17 on macOS when `JAVA_HOME` is not already set.

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

Fill all `CHANGE_ME` values.

Important values for the mixed local setup:

```env
SERVER_SERVLET_CONTEXT_PATH=/service
AGENCY_SERVICE_API_URL=https://api.oriso-dev.site/service/agencies
REGISTRATION_CORS_ALLOWED_ORIGINS=http://localhost:9001,http://127.0.0.1:9001
REGISTRATION_CORS_ALLOWED_PATHS=/**
```

`AGENCY_SERVICE_API_URL` must include `/service/agencies` for the agency Matrix service-account
endpoint used while creating an initial enquiry chat room.

## 4. Run

```bash
./run-local-remote-db.sh
```

Expected local base URL:

```text
http://localhost:8082/service
```

## 5. Useful Checks

Check whether UserService is listening:

```bash
lsof -nP -iTCP:8082 -sTCP:LISTEN
```

Stop the service with `Ctrl+C` in the terminal running the app.

## Frontend Pairing

Use the frontend local setup with:

```env
REACT_APP_USER_SERVICE_ORIGIN=http://localhost:8082
```

If that frontend variable is removed or commented, the frontend falls back to the broad remote API
instead of calling this local UserService.
