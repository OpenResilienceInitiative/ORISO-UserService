# Standing additional-access preference

The advice seeker can ask to approve every future additional-access request in this conversation. This is a separate saved choice, not an approval of the request currently on screen. Turning it off restores the centre's existing policy; it never overrides a centre that already requires approval.

For developers — HTTP contract:

```text
GET /users/sessions/{sessionId}/case-handover/consent-preference
PUT /users/sessions/{sessionId}/case-handover/consent-preference
Both routes also accept /service/users/... .
PUT body: {"alwaysAskBeforeAdditionalAccess": true|false}
200 response: {"sessionId": 123, "alwaysAskBeforeAdditionalAccess": true|false}
Missing/null choice: 400; missing session: 404; non-owner, wrong tenant, non-asker or unsupported modality: 403.
Multi-tenant installations require a positive current tenant matching the user/session; legacy tenant1 session rows retain their existing compatibility. Single-tenant installations retain their existing unpartitioned tenant behavior with owner/scope checks.
Security authority: USER_DEFAULT; authenticated session-owning registered advice seeker only.
Scope: AGENCY_COUNSELLING; legacy REGISTERED resolves to agency counselling and ANONYMOUS to LIVE_CHAT.
Stored on Session, NOT NULL default false. No supervision/legal-consent field is reused.
```

The centre's eligibility and denial rules run first. The new choice only strengthens an allowed future request to individual opt-in before Matrix membership or ownership changes. Both request creation and preference saving use the session row lock so their committed ordering determines which choice is frozen into the new request. The batch route delegates to the same access path. Session uses Hibernate dynamic updates so an unrelated writer that loaded the session earlier cannot overwrite the preference, and a preference-only save cannot overwrite unrelated concurrent session data. This changes update SQL for the whole Session entity; both stale-writer directions have actual persistence regressions.

Existing pending/granted requests keep their frozen decision and consent policy. Changing this preference neither approves them, declines them nor revokes access. Existing approval/revocation paths stay responsible for the individual request. LiveChat, internal groups and self-help rooms do not expose this setting.

Source/API regressions and local database roundtrips are separate from merged deployment, real Dev persistence/access, received mail and human acceptance.
