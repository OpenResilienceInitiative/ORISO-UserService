# Self-help group admission: Matrix repair

An Owner or Co-Moderator approval first commits `ADMITTING`. The admission worker then joins the counsellor to the Matrix room and commits the participant row and `ADMITTED` together. If that database transaction fails after Matrix joined, direct Matrix access may briefly remain. The worker attempts an immediate leave, and a durable repair task keeps retrying until Matrix confirms that the member is absent.

The repair scheduler uses a shared lease. For each task it locks the admission request before reading or changing Matrix membership. It will not remove a member from an `ADMITTED` request or one with a participant row. A task is deleted only after successful admission or confirmed Matrix absence. An unavailable Matrix member list leaves the task pending.

## Read-only triage

1. Confirm the deployed UserService revision includes the admission repair migration and the `group-chat-admission-matrix-repair` scheduler. A green PR or migration in Git is not deployment proof.
2. Find requests that remain `ADMITTING` and their repair attempts. Prefer the request and task IDs in logs; avoid copying invite tokens, room contents, message text or mail addresses into a ticket.
3. Check the current request status, participant row and live Matrix membership for the task's **stored room ID**. A recurring Series can change rooms, so its current room ID may differ from the task's room ID.
4. If Matrix is unavailable, restore it and let the scheduler retry. If a task stays pending while Matrix is reachable, capture the task ID, attempt count, last attempt, request status and Matrix error for the service owner. Do not mark the request `ADMITTED` or delete the task by hand.

**For operators — read-only database queries (adjust the ID parameter for the incident):**

```sql
SELECT id, series_id, consultant_id, status,
       admission_requested_at, admission_attempt_count, admission_last_attempt_at
FROM group_chat_join_request
WHERE id = :request_id;

SELECT id, request_id, series_id, consultant_id, room_id, member_id,
       attempt_count, last_attempt_at, created_at
FROM group_chat_admission_matrix_repair_task
WHERE request_id = :request_id
ORDER BY id;

SELECT series_id, consultant_id, participant_role
FROM group_chat_participant
WHERE series_id = :series_id AND consultant_id = :consultant_id;
```

## Controlled Dev exercise

Use an approved Dev self-help group and an authorized test identity. Make Matrix join succeed while forcing the final participant write to fail in a controlled test run. Expect a committed `ADMITTING` request, no participant row and one durable repair task. While Matrix removal fails, expect the task and incrementing attempts to remain. Restore Matrix removal; expect the member absent in the stored room and the task deleted. Retry admission normally; expect one participant row, `ADMITTED` and no task. Finally confirm the appointment sender is still disabled until its own dependencies and mail receipts are approved.

Keep local integration evidence, GitHub CI, deployed revision, Matrix membership readback and received mail as separate records. This procedure does not authorize provisioning a Dev account, merging a PR or activating the appointment-mail worker.
