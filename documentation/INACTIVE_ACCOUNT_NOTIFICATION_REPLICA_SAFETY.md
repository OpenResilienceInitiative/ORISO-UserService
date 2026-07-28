# Inactive-account notification replica safety

`InactiveAccountNotificationService` uses the audit table's unique
`notification_fingerprint` as a database-backed claim. The claim is committed in a
new transaction before UserService calls MailService. When two UserService replicas
scan the same account concurrently, one claim succeeds and the other replica skips
the external mail side effect.

This is an **at-most-once dispatch** contract:

- a duplicate scan does not submit the same fingerprint twice;
- a rejected MailService call leaves `email_dispatched=false`;
- an accepted MailService call is followed by a separate update to
  `email_dispatched=true`.

It is not a crash-safe replay contract. The current MailService OpenAPI request has
no provider idempotency key. If UserService stops after MailService accepts the
request but before the audit update commits, the claim remains undispatched and is
not automatically retried. Adding retries without a provider idempotency key could
send duplicate email.

End-to-end exactly-once processing therefore requires either:

1. a stable fingerprint accepted and deduplicated by MailService, or
2. a durable outbox with an idempotent MailService consumer.

Until that contract exists, operators can inspect undispatched audit rows, but must
not replay them automatically.
