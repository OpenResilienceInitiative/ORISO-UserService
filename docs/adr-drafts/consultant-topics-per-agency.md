# Consultant topics per counselling centre (draft, #1264 slice C2)

Status: draft · Date: 2026-09-25

## Decision

Add a nullable `agency_id` column to `consultant_topic` and widen the unique key
from `(consultant_id, topic_id)` to `(consultant_id, topic_id, agency_id)`.
One row now means "this counsellor offers topic T **at centre A**" (Fachbereich = centre × topic).

Why this and not a new link table from `consultant_agency` to topic:

1. One table, one entity, one migration — no new aggregate and no new cascade rules.
2. `consultant_agency` rows are soft-deleted and re-created on `PUT .../agencies`; hanging topics
   off their ids would make every agency edit a topic migration. `agency_id` is stable.
3. Routing keeps reading the same table with the same query (`DISTINCT` added).
4. Legacy rows fit without a data migration: `agency_id IS NULL` = "all centres of the consultant".
5. Every existing reader of `findTopicIdsByConsultantId` keeps its flat answer (`DISTINCT`).

## What routing reads

`ConsultantTopicRepository.findConsultantIdsByTopicId` (used by `TopicConsultantRoutingService`)
and `findTopicIdsByConsultantId` (used by `SessionToConsultantConditionProvider`, `SessionService`,
…). Both ignore `agency_id` and now return distinct values, so routing behaves exactly as before.
Routing by centre × topic is a later, separate decision.

## Backfill

None. A Liquibase SQL backfill cannot see which topics an agency offers (that lives in
AgencyService), and a startup job that calls AgencyService is fragile. Existing rows keep
`agency_id = NULL`, which means "applies to every centre" — the behaviour they have today.
The next admin save of that consultant's topics rewrites them as per-centre rows.
There are no production users (pre-production), so no reconciliation job is needed.

## API (additive, `api/useradminservice.yaml`)

- `ConsultantDTO.topicsByAgency: [{agencyId, topicIds[]}]` on admin GET and admin list.
  An entry without `agencyId` holds legacy rows that apply to all centres.
  `topics` (flat, named) is unchanged.
- `UpdateAdminConsultantDTO.topicsByAgency: [{agencyId, topicIds[]}]` (optional). When non-empty
  it replaces the full per-centre set and wins over `topicIds` (the generated DTO defaults an
  absent list to `[]`, so an empty list counts as "not sent"; clear everything with `topicIds: []`). Each agency must be assigned to
  the consultant and must offer every topic listed for it.
- `UpdateAdminConsultantDTO.topicIds` (flat) keeps working: each topic is stored for every
  assigned centre that offers it.

## Found on the way

The consultant self-service profile edit (`PUT /users/data`) built an `UpdateAdminConsultantDTO`
whose `topicIds` defaulted to `[]`, which the update read as "remove every topic". A counsellor
saving their own profile lost all admin-assigned topics. Fixed here: self-service sends `null`.

## Write paths

- Admin create (`CreateConsultantSaga`) and grant-identity (`GrantConsultantIdentityService`): with
  `agencyIds` each topic is stored for every selected centre that offers it; without `agencyIds`
  the flow has no centre, so rows stay legacy (`agency_id = NULL`).
- Invite accept and onboarding wizard (`CounsellorInviteProvisioningService`, shared by both): the
  invite is about exactly one centre, so the new rows are pinned to `invite.agencyId` right after
  the centre relation is created.
- CSV import (`createNewConsultant(ImportRecord, ...)`): no centre context, rows stay legacy.
- Removing a centre (`PUT .../agencies` and `DELETE .../agencies/{id}`, both via
  `ConsultantAgencyAdminService.markAsDeleted`) deletes that centre's rows; legacy rows stay.

## Out of scope

Admin form (C2b), routing per centre (product question).
