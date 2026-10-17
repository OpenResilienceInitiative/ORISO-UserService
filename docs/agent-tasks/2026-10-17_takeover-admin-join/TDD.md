# UserService #1422: takeover after the previous owner left the Matrix room

After a Traeger change the repair queue (#1421) makes the previous owner leave the case rooms. A colleague who was not already a silent member then could not take the case over: the invite used the departed owner's token, the join into the invite-only room failed and the takeover ended in HTTP 500.

## Test seams

| Seam | What it proves | What it does not |
| --- | --- | --- |
| `CaseHandoverService.requestAccess` against `FakeSynapseHomeserver` (unit) | membership, power level and rollback behaviour for each room shape | persistence |
| Same call on MariaDB (`CaseHandoverTakeoverMatrixMariaDbIT`, needs `LIQUIBASE_IT_DB_URL`) | ownership row, request row and Matrix state agree, several cases in a row, failed join leaves the owner untouched | a real Synapse |
| `MatrixRoomClient` / `MatrixSynapseService` unit tests | URLs, bodies, admin token, no user impersonation, retry via a staff member | Synapse behaviour |

`FakeSynapseHomeserver` is the `RestTemplate` behind the real gateway classes. It remembers membership and power levels and refuses what Synapse refuses (invite-only join, invite by a non-member, power changes above the sender). It never answers "yes" to every join.

## Synapse facts the double encodes (read from `element-hq/synapse` develop, not from the deployed version)

- `POST /_synapse/admin/v1/join/{room}`: for a non-public room Synapse invites the target as the calling admin first, so the admin must be a joined member who may invite.
- `POST /_synapse/admin/v1/rooms/{room}/make_room_admin`: acts through the highest-power local user in the `users` map who is currently joined (a user who left is skipped) and gives the target that user's level. It answers 400 when nobody qualifies.

Consequence: the new owner only reaches power 100 when a joined member still holds power. In a room where the agency account was removed after acceptance and the owner has left, nobody does, and the new owner stays at level 0 (can post, cannot change power levels).

## Red-green

| Case | RED (before) | GREEN (after) |
| --- | --- | --- |
| Colleague joined the centre after acceptance, owner left | HTTP 500 "Failed to join case handover requester to Matrix room" | joined, can post, owner gone |
| Directly assigned session, owner left | same 500 | joined, can post |
| Older room without the technical admin | same 500 | admin let in by a staff member, then colleague joined |
| Owner left, a joined member holds power | 500 | colleague has power 100 |
| Owner left, nobody holds power | 500 | colleague joined at level 0 |
| Rollback after the join | 500 | colleague removes themselves with their own token |
| Owner still in the room | green | unchanged: owner invites, owner raises to 100, no admin call |
