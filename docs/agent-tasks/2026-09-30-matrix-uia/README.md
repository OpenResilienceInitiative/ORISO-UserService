# UserService #1224 — root repair and Red-Green evidence

Ordinary second-device login no longer invalidates the first device's confirmation.
The HTTP tests force delayed and overlapping operations; real Synapse tests verify
replacement cross-signing and backup metadata after deleting the old backup.
The MVC response also keeps credentials out of framework body logs.

| Boundary | Original/mutated behavior | Repaired behavior |
| --- | --- | --- |
| Delayed A confirmation after B login | HTTP403 | HTTP200 |
| Overlapping device logins, separate instances | A login fails | Both logins and confirmations work |
| Restart and cache expiry | Original A confirmation HTTP403 | Still valid |
| Missing root, null/empty/blank | Incorrectly permits password replacement | Fails without changing account password |
| Account isolation | Constant-password test mutation permits cross-account authentication | Rejected; own credentials work |
| Real Synapse existing-identity reset | Actual UIA HTTP401 M_FORBIDDEN after B login | Old backup deleted, new identity/backup visible on both devices |
| MVC TRACE response logs | Synthetic password/token appears | Wire JSON unchanged, credentials absent from logs |

The account-isolation mutation is explicitly separate: original UUID generation
already isolated accounts. First-time setup is positive coverage and bypasses UIA
by design in Synapse1.158.0; it is not presented as an original-code RED.

Final combined verification: **90 tests, zero failures/errors/skips**. Package,
Spotless and CI-script syntax checks pass. Read [final verification](07-final-verification.txt)
and the preceding numbered artifacts for per-slice evidence. All accounts and
secrets are public disposable fixtures; logs/request bodies are not committed.

Independent Standards and Spec/security reviews found no remaining actionable
finding after the MVC response correction. Human review/merge, normal deployment
and Dev browser acceptance remain open. Tests cover server backup metadata;
browser recovery-key screens, local secret storage and encrypted message restore
are not claimed. See [operation and migration details](../../../documentation/matrix-browser-uia.md).
