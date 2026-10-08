# Reconcile an uncertain reply e-mail

Use this only for a `reply_email_delivery` row in `UNCERTAIN`. An interrupted or failed SMTP call can mean either that the server accepted the message or that it did not. The scheduler deliberately leaves these rows alone to avoid sending the same reply notification twice.

1. In the approved operations session, list the unresolved rows without exporting recipient addresses or message content:

   ```sql
   SELECT id, correlation_id, tenant_id, session_id, attempted_at, attempt_count
   FROM reply_email_delivery
   WHERE status = 'UNCERTAIN'
   ORDER BY attempted_at;
   ```

2. For each row, search the selected PLATFORM or OWN SMTP relay record and, where authorized, the received MIME message for `X-ORISO-Delivery-ID` equal to `correlation_id`. Match the tenant and attempt time too. The UUID is opaque; it contains no recipient or counselling content. A matching mailbox copy proves a received message. A missing copy or missing relay log does **not** prove that SMTP rejected it.

3. If a received message or authoritative relay acceptance is confirmed, have the database operator record the result in one transaction, guarded by the row's ID, status and correlation ID. Bind the named parameters in the approved database client; they are placeholders, not literal MariaDB syntax:

   ```sql
   UPDATE reply_email_delivery
   SET status = 'SENT', sent_at = COALESCE(sent_at, CURRENT_TIMESTAMP)
   WHERE id = :delivery_id
     AND status = 'UNCERTAIN'
     AND correlation_id = :correlation_id;
   ```

   Check that exactly one row changed. Record the evidence location and operator in the incident record, not in this table.

4. If acceptance cannot be established, leave the row `UNCERTAIN` and escalate with its ID, correlation ID, tenant and attempted time. Do not reset it to `PENDING` or send another mail merely because no receipt was found. If the recipient's account is being deleted, the normal deletion workflow removes this evidence; preserve the incident record according to the operations retention policy.

This runbook is ready for a Dev exercise after the UserService sender and both SMTP header paths are deployed. A source test or CI result alone cannot prove a real relay preserved the header.
