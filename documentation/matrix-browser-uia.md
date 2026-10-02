# Browser Matrix authentication across devices

UserService #1224 failed when a second browser login changed the account-wide
Matrix password. The first browser retained a working access token but its
password confirmation for replacing cross-signing keys became invalid. During
encryption reset that failure could occur after the old backup was removed.

Browser device login now derives an account credential with HMAC-SHA256 from
the required Matrix registration shared secret and the full authoritative
Matrix user ID. The input has a fixed versioned domain; device IDs, login time
and cache expiry are excluded. Separate service instances therefore give the
same account the same credential. Different accounts get different credentials.
This credential is unrelated to the user's chat recovery key and does not
derive or decrypt message encryption keys.

The service still uses standard device-bound password login and updates the
account with `logout_devices=false`. The credential is returned for browser
memory with `Cache-Control: no-store`. The application does not persist it in
MariaDB, browser storage or logs. Existing secret-safe request DTOs remain in use; the browser response also uses
a DTO without a secret-bearing `toString` so MVC DEBUG/TRACE body logging cannot
expose either credential.

## Migration and rotation

The first login after deployment replaces the old random password. Existing
access tokens remain valid, but an already-open browser may need the credential
refresh implemented by frontend PR #1504 before confirming a reset.

Keep the registration shared secret consistent across replicas. Intentionally
changing that secret or the derivation version changes account credentials.
Treat either as a coordinated migration: roll replicas together, avoid mixed
secrets/versions accepting logins, and refresh browser credentials. Ordinary
browser login no longer rotates the credential. Startup configuration validation
requires the secret; browser login also fails closed if it is absent.

## Regression gates

`MatrixBrowserDeviceConcurrencyTest` forces delayed HTTP operations against a
stateful server, including independent backend instances. It checks issued
credentials against current account state rather than an expected hash.

`MatrixBrowserDeviceSynapseIT` uses a disposable, real Synapse v1.158.0 with
synthetic accounts. It exercises actual device-bound tokens, password UIA and
cross-signing key replacement after another device logs in. Docker is required;
the test cannot silently skip. Required PR and feature-branch integration CI
discovers the test and requires its report.

These tests cover the server authentication cause. They do not replace the Dev
browser check of the reset dialog, recovery-key download and messaging. Network
failures at other reset steps can still leave a partially completed SDK reset.
