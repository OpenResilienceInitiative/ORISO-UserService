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
  refusal is 403; nonempty whitespace-only or overlong aliases are 400. Null and
  empty aliases retain the existing no-change behavior. Legacy asker account PATCH display-name
  fields remain ignored so a combined language update keeps working during a
  backend-first rollout. New callers use the session-data endpoint for aliases.
- The existing alias storage bound is 255 characters. The explicit server check
  improves the previous storage failure; the OpenAPI request shape is unchanged.

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
