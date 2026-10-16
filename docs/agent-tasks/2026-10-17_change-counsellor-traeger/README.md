# UserService #1419: change a counsellor's Träger

A platform admin can move a counsellor to another Träger without waiting for their open cases.
The cases stay with the old Träger; colleagues there take them over with the neutral reason
`ASSIGNMENT_ENDED`. Decision: ORISO-Admin#952 (Frank, 2026-10-16).

`PUT /useradmin/consultants/{consultantId}/tenant` (also under `/service`), platform admin only.

## What the move does

| Part | Effect |
| --- | --- |
| Sessions, rooms, advice seekers | Untouched. `session.consultant_id` keeps pointing at the counsellor, so a takeover still has a previous owner. |
| `consultant_agency` | Old rows soft-deleted (topics of those centres go with them), new rows created in the new Träger. |
| `consultant.tenant_id` | New Träger. The strict tenant filter now hides every old case from the counsellor. |
| Keycloak `tenantId` attribute | Switched inside the transaction; put back if the transaction fails later. Login sessions end after the commit. |
| Standing supervisor | The counsellor's own is cleared, and so is every colleague's that points at them. |
| Matrix | Rooms of owned, team, supervised and co-access cases are left through the durable repair queue (`LEAVE`), at most 100 per minute. Open-enquiry and team-discussion rooms of the old centres are left by the existing `ConsultantLeftAgencyEvent` listener. |
| Audit | `consultant_tenant_change_audit`: actor, old and new Träger, centre ids, counts. No case data. |

## Refused

| Status | When |
| --- | --- |
| 400 | multi-tenancy off, same Träger, a centre does not belong to the new Träger, topics not covered |
| 403 | caller is not a platform admin |
| 404 | counsellor not found |
| 409 | last counsellor of a still active centre of the old Träger, or the account also holds an admin identity |
| 503 | the identity provider could not switch the tenant (nothing is changed) |

## Known limits

- A colleague who is not already a silent member of the case room cannot be invited once the
  counsellor has left: the existing takeover invites with the previous owner's token. Department
  members are members already (ADR-002 §1), so the normal case works.
- The new owner then keeps Matrix power 0 instead of 100 (the existing takeover only warns), as
  for any departed counsellor today.
- Megolm keys already on the counsellor's devices cannot be revoked. Group chats and appointments
  are not handled.
- Until a colleague takes a case over, notifications for it still address the old owner.

## Run the MariaDB proof

```bash
LIQUIBASE_IT_DB_URL=jdbc:mariadb://127.0.0.1:3306/userservice LIQUIBASE_IT_DB_PASSWORD=root \
  ./mvnw -B -DskipITs=false test -Dtest=ConsultantTenantChangeMariaDbIT
```
