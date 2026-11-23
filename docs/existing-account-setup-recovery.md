# Existing-account setup recovery

Directly created tenant admins, agency admins and counsellors receive a single-use
`EXISTING_ACCOUNT_SETUP` link. Creation persists the account before it prepares mail. A failed
request can therefore mean **account created, setup delivery incomplete**; do not create another
account with the same address to retry it.

## Definitive mail failure or an unused/expired old link

1. In the Admin invite board, locate the setup row by the created identity ID and verify its
   recipient, role and tenant against the saved account. The scoped API response includes
   `setupRecoveryReason`; the UI may show only its delivery/status columns.
2. If the row is `DRAFT`, `EMAIL_SENT` or `EXPIRED` with provisioning `PENDING` or `FAILED`, and no
   password setup is running, an authorised administrator can use **Resend** on that setup row.
   The existing `POST /useradmin/account-invites/{inviteId}/resend` selects the row under the
   caller's scope and ignores ordinary invitation-template fields for this purpose. The protected
   operator API `POST /useradmin/accounts/{identityId}/setup-link` with exactly
   `{"targetRole":"TENANT_ADMIN"}` (or `AGENCY_ADMIN`/`COUNSELLOR`) is an alternative. The server rechecks the
   saved account and Keycloak, atomically supersedes the old link and sends a new canonical mail.
   The response never contains the raw token. An expired old token was already unusable; a
   superseded old token remains unusable. The private salted initial-password verifier and exact
   bound identity key survive expiry only so this authorised handover can reuse the verifier without
   recovering the initial password. The old row loses both values during the handover.
3. Confirm a `SENT` delivery audit for the new row. This proves SMTP handover only; actual mailbox
   receipt and successful first login need separate checks.

## `IN_PROGRESS` or `SETUP_OUTCOME_INDETERMINATE`

Do **not** retry the link or call reissue. The password update may have succeeded even when its
response or the following MariaDB finalisation failed. There is no safe timeout that proves it did
not. Reissue and ordinary revoke are intentionally blocked in this state.

1. Restrict further setup actions for this identity. Check the Keycloak password-update audit and
   any still-running request; verify the exact saved identity, tenant, role and email. Do not inspect,
   repeat or log either password. If the outcome remains uncertain, leave the claim held and
   escalate to the identity operator.
2. If the permanent password definitely took effect, finish the account-invite row as `ACCEPTED` /
   `COMPLETED` with the bound `provisioned_user_id` as `accepted_by_user_id`, mark the mail-link
   verification complete, and clear `active_setup_identity_key` **and**
   `initial_password_verifier`. For a counsellor, clear
   `consultant.password_change_required` for **that same ID** only. Keep the native 2FA gate; it
   becomes active through the ordinary 2FA activation event, never through this recovery.
3. If the permanent password definitely did **not** take effect and no update is still running,
   a privileged operator may guardedly change this exact `IN_PROGRESS` row to `DRAFT`/`FAILED`
   while retaining its private verifier and active identity key. `DRAFT` makes the old token
   unusable. Then immediately use the authenticated reissue endpoint above; its transaction
   supersedes that row, clears the old verifier and issues a fresh link carrying a copied salted
   verifier. Do not release a row with a changed email, role, tenant or initial credential. If the
   verifier has already been cleared by revocation or completion, reissue deliberately
   refuses; it cannot invent or recover the initial password. A new temporary credential and
   verified setup issuance require a separate privileged recovery operation. Do not reset the
   Keycloak credential or make an old token active as a shortcut.
4. Have a second operator review the identity ID, Keycloak evidence and guarded database change.
   Record the outcome under the issue without passwords, raw tokens or full mail bodies. Test the
   normal login and mandatory 2FA separately.

The last two cases require a protected operator action; the application does not infer Keycloak
success from an exception and does not automatically replay an indeterminate reset. This recovery
path has not been exercised on Dev until an operator performs and records it. The private verifier
is cleared on successful setup, supersession and revocation; an expired but un-reissued setup row
retains it until an authorised resend or revoke. There is no invented automatic retention deadline.

The ordinary Admin/App **forgot-password** route is not a replacement for a setup link.
It can issue a separate one-time reset to a known account address and set a non-temporary Keycloak
password, but it does not consume or close an existing-account setup row. For counsellors it also
does not clear the application's `password_change_required` gate. Using it as an automatic setup
recovery can leave an old setup token active. Setup confirmation now checks that Keycloak still
requires `UPDATE_PASSWORD`, so an attempted old link after a successful ordinary reset is revoked
before another password change. Use the protected
resend above for a definitive unused or expired link. A claimed indeterminate setup remains blocked
until the operator establishes the remote outcome; neither forgot-password nor automatic resend
proves that outcome.
