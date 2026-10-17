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
| Supervisions | The counsellor's active `session_supervisor` rows end (`is_active = false`, `removed_date`), so moving back does not silently restore them. Colleagues' supervisions of the counsellor's cases stay. |
| Matrix | Every room of the old Träger the counsellor is a member of is left through the durable repair queue (`LEAVE`), at most 100 per minute: owned, team, supervised and co-access cases, the staff-only supervision side rooms (owned and supervised), and the accepted cases of colleagues they sit in as a silent department member (ADR-002 §1). Found by asking the chat server for their joined rooms and intersecting with the rooms of the old Träger; when it cannot be asked, every room of the old centres is queued (leaving a room one never sat in is a no-op). Open-enquiry and team-discussion rooms of the old centres are also left by the existing `ConsultantLeftAgencyEvent` listener. |
| Identity provider | Tenant attribute switched last but one, roles of the new centres last, both inside the transaction. The restore of the old tenant is registered before the write, so a failure after the write (timeout) is undone as well. A refusal (503) leaves the identity provider as it was. Roles of the old centres are not removed. |
| Audit | `consultant_tenant_change_audit`: actor, old and new Träger, centre ids, counts. No case data. |

## Refused

| Status | When |
| --- | --- |
| 400 | multi-tenancy off, same Träger, no centre, unknown centre, a centre does not belong to the new Träger, topics not covered |
| 403 | caller is not a platform admin |
| 404 | counsellor not found |
| 409 | last counsellor of a still active centre of the old Träger, or the account also holds an admin identity |
| 503 | the identity provider could not switch the tenant or grant the roles (nothing is changed) |

Every 400 and 409 above carries a stable `X-Reason` header (ORISO-Admin#1217 maps them):

| `X-Reason` | Status |
| --- | --- |
| `TENANT_CHANGE_MULTITENANCY_OFF` | 400 |
| `TENANT_CHANGE_SAME_TENANT` | 400 |
| `TENANT_CHANGE_AGENCIES_REQUIRED` | 400 |
| `TENANT_CHANGE_AGENCY_UNKNOWN` | 400 |
| `TENANT_CHANGE_AGENCY_OTHER_TENANT` | 400 |
| `TENANT_CHANGE_TOPICS_NOT_COVERED` | 400 |
| `TENANT_CHANGE_LAST_COUNSELLOR` | 409 |
| `TENANT_CHANGE_ADMIN_IDENTITY` | 409 |

Watch `userservice.matrix.leave.exhausted`: it counts `LEAVE` tasks the repair queue gave up on
(counsellor still in a room of the old Träger). Each also logs one WARN with the case id only.

## Known limits

- **#1421 must not ship without #1422.** A colleague who is not already a member of the case room
  cannot be invited once the counsellor has left: the existing takeover invites with the previous
  owner's token (`CaseHandoverService.ensureRequesterJoinedMatrixRoom`) and answers 500 when the
  join fails. This only affects colleagues who are no room members (joined the centre after the
  case was accepted, directly assigned cases, cases from before ADR-002); department members are
  members already, so the common case works. The new owner also keeps Matrix power 0 instead of
  100. The LEAVE cannot be ordered after the takeover (it happens when the move happens, the
  takeover whenever a colleague gets to it), so the fix is the admin force-join of #1422. Merge
  order: #1422, then #1421.
- Megolm keys already on the counsellor's devices cannot be revoked. Group chats and appointments
  are not handled.
- The rooms are left asynchronously: until the queue has run (at most 100 per minute) the
  counsellor can still read and write in them. `matrixAccessRevocationsQueued` is a promise.
- A case the counsellor accepts between the room lookup and the commit gets no `LEAVE` task.
- Until a colleague takes a case over, notifications for it still address the old owner.

## Run the MariaDB proof

```bash
LIQUIBASE_IT_DB_URL=jdbc:mariadb://127.0.0.1:3306/userservice LIQUIBASE_IT_DB_PASSWORD=root \
  ./mvnw -B -DskipITs=false test -Dtest=ConsultantTenantChangeMariaDbIT
```
