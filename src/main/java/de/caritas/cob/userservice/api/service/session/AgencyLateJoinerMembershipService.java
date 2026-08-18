package de.caritas.cob.userservice.api.service.session;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * US#1060 / FE#811: keeps the department membership of an enquiry room in step with the agency
 * roster <em>after</em> the room already exists.
 *
 * <p>{@link AgencySilentMembershipService} joins every counsellor of the agency at room creation,
 * which fixes the enquiry for everyone who was already in the agency at that moment. Nothing
 * repaired the mirror-image case: a counsellor added to the agency a day later is not a member of
 * yesterday's enquiry rooms, Matrix {@code /sync} therefore never hands those rooms to their
 * client, and no later event fixes it — the open initial requests simply stay invisible to them
 * until an admin intervenes.
 *
 * <p>Scope is deliberately the agency's <em>open</em> enquiries (status {@link SessionStatus#NEW},
 * no counsellor assigned yet). Those are exactly the cases the whole department is entitled to pick
 * up. An accepted case belongs to one counsellor and is governed by {@code AssignEnquiryFacade}, so
 * backfilling it here would widen its audience beyond what ADR-002 allows. Directly addressed
 * enquiries are excluded for the same reason they are excluded at creation.
 *
 * <p>Every step is best effort: an admin assigning an agency must never see their request fail
 * because Synapse hiccuped on one room, and the relation in the database is the source of truth
 * either way.
 *
 * <p>Note on encryption: a counsellor backfilled here holds no Megolm key for messages sent before
 * they joined and depends on the client-side history-key transfer that ships with FE#811. Joining
 * at creation stays the stronger guarantee; this service is the repair path for everyone who could
 * not be there at creation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgencyLateJoinerMembershipService {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull AgencyMatrixCredentialClient matrixCredentialClient;
  private final @NonNull SessionRoomGateway sessionRoomGateway;
  private final @NonNull AgencySilentMembershipService agencySilentMembershipService;

  /**
   * Joins a counsellor who was just assigned to an agency into the Matrix rooms of that agency's
   * already existing open enquiries.
   *
   * @param consultant the newly assigned counsellor
   * @param agencyId the agency they were assigned to
   * @return number of enquiry rooms the counsellor ended up joined into
   */
  public int joinConsultantIntoOpenEnquiryRooms(Consultant consultant, Long agencyId) {
    var roomIds = openEnquiryRoomIds(consultant, agencyId);
    if (roomIds.isEmpty()) {
      return 0;
    }

    var agencyToken = resolveAgencyToken(agencyId);
    if (isBlank(agencyToken)) {
      return 0;
    }

    int joined = 0;
    for (String roomId : roomIds) {
      try {
        if (agencySilentMembershipService.joinConsultantIntoRoom(consultant, roomId, agencyToken)) {
          joined++;
        }
      } catch (RuntimeException ex) {
        log.warn(
            "Could not backfill consultant {} into enquiry room {} of agency {}: {}",
            consultant.getId(),
            roomId,
            agencyId,
            ex.getMessage());
      }
    }

    log.info(
        "Backfilled consultant {} into {} of {} open enquiry rooms of agency {}",
        consultant.getId(),
        joined,
        roomIds.size(),
        agencyId);
    return joined;
  }

  /**
   * Revokes the membership a counsellor holds in the open enquiry rooms of an agency they were just
   * detached from. Without this the counsellor keeps syncing enquiries of an agency they no longer
   * belong to — the exact stale access the at-creation fan-out would otherwise create.
   *
   * <p>A counsellor without a Matrix account never joined anything, so there is nothing to revoke
   * and no account is provisioned here.
   *
   * <p>The scope is the same open enquiries the join side covers, and that symmetry is enforced by
   * Matrix rather than chosen for tidiness: {@code AssignEnquiryFacade} removes the agency service
   * account from the room the moment an enquiry is accepted, so its token can no longer kick anyone
   * out of an accepted case. Rooms of accepted cases therefore need the assigned counsellor's own
   * token and are handled by the case-handover/team-session paths, not here.
   *
   * @param consultant the detached counsellor
   * @param agencyId the agency they were detached from
   * @return number of enquiry rooms the counsellor was removed from
   */
  public int removeConsultantFromOpenEnquiryRooms(Consultant consultant, Long agencyId) {
    if (consultant == null || isBlank(consultant.getMatrixUserId())) {
      return 0;
    }

    var roomIds = openEnquiryRoomIds(consultant, agencyId);
    if (roomIds.isEmpty()) {
      return 0;
    }

    var agencyToken = resolveAgencyToken(agencyId);
    if (isBlank(agencyToken)) {
      return 0;
    }

    int removed = 0;
    for (String roomId : roomIds) {
      try {
        if (sessionRoomGateway.removeUserFromRoom(
            roomId, consultant.getMatrixUserId(), agencyToken)) {
          removed++;
        }
      } catch (RuntimeException ex) {
        log.warn(
            "Could not remove consultant {} from enquiry room {} of agency {}: {}",
            consultant.getId(),
            roomId,
            agencyId,
            ex.getMessage());
      }
    }

    log.info(
        "Removed consultant {} from {} of {} open enquiry rooms of agency {}",
        consultant.getId(),
        removed,
        roomIds.size(),
        agencyId);
    return removed;
  }

  private List<String> openEnquiryRoomIds(Consultant consultant, Long agencyId) {
    if (consultant == null || agencyId == null) {
      return List.of();
    }

    return sessionRepository
        .findByAgencyIdAndStatusAndConsultantIsNull(agencyId, SessionStatus.NEW)
        .stream()
        .filter(session -> !Boolean.TRUE.equals(session.getIsConsultantDirectlySet()))
        .map(Session::getMatrixRoomId)
        .filter(StringUtils::isNotBlank)
        .distinct()
        .toList();
  }

  /**
   * The enquiry rooms are owned by the agency's Matrix service account, so invites and kicks have
   * to be issued with its token — the same resolution {@code AgencyPreAssignmentRoomService}
   * performs when it creates the room.
   */
  private String resolveAgencyToken(Long agencyId) {
    var credentialsOpt = matrixCredentialClient.fetchMatrixCredentials(agencyId);
    if (credentialsOpt.isEmpty()) {
      log.warn(
          "No Matrix service account available for agency {}; enquiry room membership unchanged.",
          agencyId);
      return null;
    }

    var credentials = credentialsOpt.get();
    if (isBlank(credentials.getMatrixUserId()) || isBlank(credentials.getMatrixPassword())) {
      log.warn(
          "Matrix service account configuration incomplete for agency {}; enquiry room membership unchanged.",
          agencyId);
      return null;
    }

    var agencyToken =
        sessionRoomGateway.loginUser(
            MatrixIds.localpartLenient(credentials.getMatrixUserId()),
            credentials.getMatrixPassword());
    if (isBlank(agencyToken)) {
      log.error(
          "Failed to login Matrix service account {} for agency {}; enquiry room membership unchanged.",
          credentials.getMatrixUserId(),
          agencyId);
      return null;
    }

    return agencyToken;
  }
}
