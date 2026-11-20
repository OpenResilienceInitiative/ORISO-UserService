# Asker identity settings

The account-wide Platform/Träger flags govern custom session aliases and collection
of an email address. Notification-channel flags are separate and do not grant
permission to collect an address.

- `featureDisplayNameEditable=false` permits approved generated pseudonyms, but
  refuses arbitrary free text. The alias belongs to one session's
  `session_data.displayName`; login usernames, passwords and Matrix identities
  never change. Existing aliases survive unchanged echoes and unrelated saves.
- `featureAskerEmailEnabled=false` blocks a new or different address at registered
  account creation, profile updates and email-OTP enrollment. Reusing the exact
  existing address and deleting an address/second factor remain available.
- Mutation checks fetch the current restricted tenant projection without the
  tenant cache, using the stored session/user tenant identity. Only in configured
  single-tenant mode may a legacy missing stored ID resolve through the fresh
  configured-single-tenant endpoint. Its response still requires a positive ID
  and settings. Multi-tenant missing IDs, technical/negative IDs, a missing
  projection, upstream failure or a mismatched explicit tenant fail closed with
  503. An omitted individual legacy flag keeps its enabled opt-out default. Policy
  refusal is 403. Session updates reject nonempty whitespace-only or overlong
  aliases with 400; null and empty aliases retain the existing no-change behavior. Legacy asker account PATCH display-name
  fields remain ignored so a combined language update keeps working during a
  backend-first rollout. New callers use the session-data endpoint for aliases.
- The existing alias storage bound is 255 characters. The explicit server check
  improves the previous storage failure; the OpenAPI request shape is unchanged.

## Optional signup alias

Ordinary registration accepts optional `displayName`. The server strips surrounding
Unicode whitespace and treats a blank value as omitted, so the UI falls back to
the immutable Username. Nonblank values use the same generated-name membership,
custom-typing permission and 255-character storage bound as session updates.
Validation runs before identity provisioning. The trusted creator writes onto its
actual first Session before registration completes, with a fresh owner/tenant
recheck inside the data transaction; it does not require the new user to be
logged in. Existing registration compensation handles an incomplete operation.
External session-data updates retain their authenticated owner check.

Self-help signup joins a shared group and creates no personal Session. Its legacy
`displayName` input stays ignored; it never renames a shared room or stores an
account-wide/member alias. Direct-link registration likewise opens no Session
until the link starts the conversation, so it ignores a signup alias instead of
creating a global pending name. Existing live-room alias selection uses the
session update contract.

## Conversation email collection

The three default-true collection controls are distinct from delivery channels:

- `featureAskerEmailCollectionAgencyCounsellingEnabled`
- `featureAskerEmailCollectionLiveChatEnabled`
- `featureAskerEmailCollectionSelfHelpEnabled`

The existing account master caps every type. Registration checks the verified
invite purpose (`SELF_HELP`) or its actual ordinary first Session purpose
(`AGENCY_COUNSELLING`) before effects; a client-provided type never grants access.
The secondary global check remains before first-session creation.

Account/profile and email-OTP writes keep one global account address. With all
three collection flags absent/true they preserve the legacy master-only rule.
When any type is disabled, a new/different address requires at least one allowed
actual owned personal Session or joined self-help UserChat. Persisted modality
wins; legacy ordinary/anonymous registration discriminators resolve only their
existing Agency/Live meaning. Archived owned contexts count. Unknown/internal
contexts, shared-room Session rows, other users and explicit foreign/invalid
personal Session tenant IDs do not grant permission. No discoverable-group list,
request-header tenant or caller-supplied type provides authority. Fresh settings
are fetched before the short read-only context transaction.

Existing GET userData exposes read-only optional Boolean
`isAskerEmailCollectionAllowed` for askers. Expected policy/context unavailability
projects false without breaking the rest of that GET; mutations still refuse
with 503. Consultant responses omit the capability. Existing-address reuse is
trimmed and case-insensitive; deletion and second-factor removal remain possible.
OTP completion rechecks both fresh policy and current ownership before activation
or identity email effects. When permission is closed/unavailable, only verified
provider metadata proving the same stored address permits completion.

## Generated names

`identity/pseudonym-vocabulary.json` contains the curated Frontend tables. Accepted
forms are the full localized adjective/animal/name combination (including the
correct grammatical group), or the registration function's short animal/name
handle with its four-digit suffix. Unicode canonically equivalent full names are
accepted. This validates membership in the approved set; it does not attest that
a browser chose a combination randomly.

The parity command executes the actual reviewed Frontend username function,
including umlaut spelling, truncation and suffix formatting, and checks both the
dictionary and all generated handle bases:

```sh
node scripts/contracts/sync-pseudonym-vocabulary.mjs <Frontend-checkout> --check
```

When the reviewed Frontend generator changes, run the same command without
`--check`, review both resulting resources, then run `GeneratedPseudonymsTest`.
This requires Node 24. Avatar generation and selection remain independent.

## Email OTP

Before starting email verification, the guard resolves the actual asker by the
authenticated user ID. Before completing it with collection disabled, the
Keycloak adapter reads only `MAIL_OTP_CM` credential metadata email targets. Every
pending/active target must equal the existing account address. This prevents a
policy change during enrollment from introducing a new address before OTP
activation or an identity-provider email mutation. OTP secret data is neither
read nor logged. Unknown/mixed targets are refused; unavailable metadata is 503.
Consultant/admin identities without an asker row retain their existing behavior.
