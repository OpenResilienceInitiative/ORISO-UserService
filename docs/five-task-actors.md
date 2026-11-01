# Five task actors — staged UserService source

This prepares the Scenario C caller boundary. It does not activate the five-actor model in production or complete the native account lifecycle.

The default `TASK_IDENTITY_MODEL` is `LEGACY`. Blank or unknown values fail startup. `FIVE_ACTORS` also deliberately fails startup until the native account, lifecycle and coordinated receiver cutover is implemented. There is no production preview switch that bypasses that gate. Isolated tests construct the staged broker explicitly.

## Credential contract

Each actor needs all three explicit environment bindings: `IDENTITY_<ACTOR>_CLIENT_ID`, `IDENTITY_<ACTOR>_CLIENT_SECRET` and `IDENTITY_<ACTOR>_SERVICE_SUBJECT`. All five actors must be configured before any actor can issue a grant. Client IDs, secrets and actual native service-account subjects must be distinct across actors and must not reuse configured legacy technical/admin or human application bindings. Diagnostic objects and authentication failures do not expose credentials or tokens.

| Actor | Client | Logical purposes | Exact receiver audiences |
| --- | --- | --- | --- |
| SETUP | backend-setup | CONFIG_WIZARD, INVITE_RESERVATIONS, CONSULTANT_IMPORT | tenantservice, agencyservice, consultingtypeservice, userservice |
| ACCOUNT_CREATE | backend-account-create | ACCOUNT_PROVISIONING | Native audience contract remains open; no issuance |
| ACCOUNT_ADMIN | backend-account-admin | ACCOUNT_MAINTENANCE, OTP, SESSION_EXCHANGE | Native audience contract remains open; no issuance |
| MAIL | backend-mail | SMTP_SYNC, SYSTEM_EMAIL_DELIVERY, NOTIFICATION_DISPATCH | tenantservice, agencyservice, consultingtypeservice, userservice |
| RUNTIME | backend-runtime | RUNTIME_POLICY, MATRIX_AGENCY, APPOINTMENT_SYNC, APPOINTMENT_CLEANUP | tenantservice, agencyservice, appointmentservice |

Purposes sharing an actor share its credential. They do not introduce additional service accounts. Role unions are the Scenario C contract in `TaskActor`; native account audiences are deliberately empty. The account actors refuse before any token endpoint request.

The broker uses the existing confidential `client_credentials` HTTP grant. It validates the signature through the configured JWK decoder, exact issuer, `azp`, native `sub`, complete realm-role union and audience union, live expiry and no client-role grants. It never tries a technical/admin/human fallback or keeps a token cache. Refresh-session fields are discarded by the existing confidential grant adapter.

## Prepared routine callers

`TaskAwareIdentityClientConfig` is the wiring adapter. It delegates legacy values to the existing `IdentityConfig` and obtains the broker lazily. `RoutineTaskAuthentication` selects one explicit purpose; grouped failures never invoke legacy authentication.

| Callers | Purpose |
| --- | --- |
| Tenant/agency onboarding creation, operator DPA content | CONFIG_WIZARD |
| Accept-time agency check, invite expiry/release queue and retry scheduler | INVITE_RESERVATIONS |
| Global SMTP settings lookup | SMTP_SYNC |
| Tenant system email delivery | SYSTEM_EMAIL_DELIVERY |
| Agency contact facts, DPA signature read-back for notices | NOTIFICATION_DISPATCH |
| Background tenant policy and DPA-gate reads | RUNTIME_POLICY |
| Agency Matrix credentials | MATRIX_AGENCY |
| Appointment consultant creation/update/patch, agency sync | APPOINTMENT_SYNC |
| Appointment consultant/asker deletion | APPOINTMENT_CLEANUP |

Existing caller-triggered policy reads/writes continue to use the human token. Reservation pairs, tenant and target checks, request bodies, context lifetimes and receiving proof formats remain in their existing paths. Allocation clients consume the explicitly offered reservation token; their human-triggered path is unchanged. A grouped queued-release authentication failure leaves the queue untouched instead of retrying without credentials.

`CONSULTANT_IMPORT` is mapped to SETUP, but activating its account-creation lifecycle is still gated. No native Keycloak account adapter, OTP/session exchange, protected-admin creation, pending-account cleanup, activation/recovery or saga/journal implementation is migrated by this package. Existing cross-service proof formats are preserved; their coordinated `ORISO_TASK_CONTEXT_KEY` wiring remains a separate caller/chart cutover.

## Verification and limits

The public Spring startup tests exercise the real wiring adapter and mode gate. Token tests use a local synthetic RSA issuer over HTTP and serve its public JWKS to the real decoder. Caller tests send verified actor tokens to local HTTP endpoints and check the selected client, tenant/agency target, reservation body and absence of fallback. These are source-boundary proofs, not native Keycloak FGAP, deployed receiver, external mail/Matrix/AppointmentService or Dev acceptance.

Run the focused boundary tests with Java 21:

```sh
MAVEN_OPTS='-Xmx1g -XX:ActiveProcessorCount=2' ./mvnw -B \
  -Dtest=FiveActorActivationPreflightTest,FiveActorConfigurationTest,FiveActorTokenBoundaryTest,FiveActorRoutineCallerBoundaryTest \
  -Dskip.integration-tests=true test
```

Full C remains unactivatable until the native account and lifecycle contracts, protected creation owner/flow, coordinated context-key wiring and remaining receiver/runtime acceptance gates are completed. This source package alone cannot close the full UserService implementation ticket.
