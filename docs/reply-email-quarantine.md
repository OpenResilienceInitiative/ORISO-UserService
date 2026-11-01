# Requeue quarantined notification e-mails

A `reply_email_delivery` row becomes `QUARANTINED` when its preflight (for example the tenant's notification e-mail setup) still fails 24 hours after the mail was created (retried every 5 minutes until then, so short platform outages recover on their own). The row is set aside so it stops taking delivery slots; other tenants' mails keep flowing. No SMTP call was made for a quarantined row, so requeueing it cannot send a mail twice.

The UserService logs `QUARANTINED` at ERROR level per row (with delivery id, tenant id and attempt count) and once per change of the total count. Alert on that log line.

1. Find the cause in the log entries `Notification email ... delivery <id> retry stage=... reason=...`, and in the tenant's settings (for example `featureSystemNotificationEmailsEnabled`). Fix it first.

2. In the approved operations session, list the rows without exporting recipient data:

   ```sql
   SELECT tenant_id, recipient_kind, COUNT(*) AS rows_quarantined, MIN(created_at) AS oldest
   FROM reply_email_delivery
   WHERE status = 'QUARANTINED'
   GROUP BY tenant_id, recipient_kind;
   ```

3. Requeue the fixed tenant's rows. Bind the named parameter in the approved database client:

   ```sql
   UPDATE reply_email_delivery
   SET status = 'PENDING', attempt_count = 0, next_attempt_at = CURRENT_TIMESTAMP
   WHERE status = 'QUARANTINED'
     AND tenant_id = :tenant_id;
   ```

   The scheduler picks them up within a minute. Every send re-checks the recipient's current access and notification preference, so a mail that is no longer appropriate ends as `REJECTED`, not sent. If the cause is not fixed, the rows return to `QUARANTINED` at their next failed attempt, because they are older than 24 hours.

Mails that are very old may no longer be useful; deciding to leave them quarantined is an operator decision. The account deletion workflow removes quarantined rows like any other delivery row.
