package de.caritas.cob.userservice.api.service.session;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.port.out.SessionRoomRow;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
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
 * <p>Scope is deliberately the agency's <em>open</em> enquiries (status {@link SessionStatus#NEW}
 * or {@link SessionStatus#INITIAL}, no counsellor assigned yet). Those are exactly the cases the
 * whole department is entitled to pick up. An accepted case belongs to one counsellor and is
 * governed by {@code AssignEnquiryFacade}, so backfilling it here would widen its audience beyond
 * what ADR-002 allows. Directly addressed enquiries are excluded for the same reason they are
 * excluded at creation.
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
  private final @NonNull AgencyOpenEnquiryMembership openEnquiryMembership;
  private final @NonNull MatrixRateLimitPause rateLimitPause;
  private final @NonNull TeamDiscussionRepository teamDiscussionRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull de.caritas.cob.userservice.api.service.teamdiscussion
          .TeamDiscussionParticipantWriter
      participantWriter;
  private final @NonNull de.caritas.cob.userservice.api.workflow.scheduling
          .ScheduledTaskClaimService
      claims;

  static final int ENQUIRY_ROOM_PAGE_SIZE = 100;

  public static final String TEAM_ACCESS_REPAIR_TASK = "team-discussion-access-repair";

  /**
   * Joins a counsellor who was just assigned to an agency into the Matrix rooms of that agency's
   * already existing open enquiries.
   *
   * @param consultant the newly assigned counsellor
   * @param agencyId the agency they were assigned to
   * @return number of enquiry rooms the counsellor ended up joined into
   */
  public int joinConsultantIntoOpenEnquiryRooms(Consultant consultant, Long agencyId) {
    if (consultant == null || agencyId == null) {
      return 0;
    }
    var upperId = sessionRepository.findAgencySessionUpperId(agencyId).orElse(null);
    if (upperId == null) {
      return 0;
    }

    var page = PageRequest.ofSize(ENQUIRY_ROOM_PAGE_SIZE);
    long afterId = Long.MIN_VALUE;
    String agencyToken = null;
    int candidates = 0;
    int joined = 0;
    while (afterId < upperId) {
      var rooms =
          sessionRepository.findLateJoinerEnquiryRooms(
              agencyId, AgencyOpenEnquiryMembership.OPEN_STATUSES, afterId, upperId, page);
      if (rooms.isEmpty()) {
        break;
      }
      afterId = rooms.getLast().sessionId();
      for (var room : rooms) {
        // SQL TRIM handles spaces. Keep the Java blank check for other whitespace.
        if (isBlank(room.roomId())) {
          continue;
        }
        if (agencyToken == null) {
          agencyToken = resolveAgencyToken(agencyId);
          if (isBlank(agencyToken)) {
            return 0;
          }
        }
        candidates++;
        if (joinOpenEnquiry(consultant.getId(), agencyId, room, agencyToken)) {
          joined++;
        }
      }
      if (rooms.size() < page.getPageSize()) {
        break;
      }
    }

    if (candidates > 0) {
      log.info(
          "Backfilled consultant {} into {} of {} open enquiry rooms of agency {}",
          consultant.getId(),
          joined,
          candidates,
          agencyId);
    }
    return joined;
  }

  private boolean joinOpenEnquiry(
      String consultantId, Long agencyId, SessionRoomRow room, String token) {
    try {
      // Match the existing Matrix invite cap, with the wait outside the entitlement transaction.
      for (int attempt = 1; attempt <= 3; attempt++) {
        var outcome =
            openEnquiryMembership.joinIfCurrent(
                consultantId, agencyId, room.sessionId(), room.roomId(), token);
        if (outcome.isEmpty()) {
          return false;
        }
        var result = outcome.get();
        if (!result.rateLimited()) {
          return result.joined();
        }
        if (attempt == 3 || !rateLimitPause.pause(Math.min(result.retryAfterMs(), 2_000L))) {
          return false;
        }
      }
    } catch (RuntimeException ex) {
      log.warn(
          "Could not backfill consultant {} into enquiry room {} of agency {}: {}",
          consultantId,
          room.roomId(),
          agencyId,
          ex.getClass().getSimpleName());
    }
    return false;
  }

  /** Revokes recorded team-discussion membership after the counsellor leaves the agency. */
  public int removeConsultantFromTeamRooms(Consultant consultant, Long agencyId) {
    if (consultant == null || isBlank(consultant.getMatrixUserId())) {
      return 0;
    }

    var roomIds =
        Stream.of(TeamDiscussion.Status.values())
            .flatMap(
                status ->
                    teamDiscussionRepository
                        .findRoomIdsForParticipantInAgency(consultant.getId(), agencyId, status)
                        .stream())
            .distinct()
            .toList();
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
        if (removeTeamMemberWithLease(consultant, agencyId, roomId, agencyToken)) {
          removed++;
        }
      } catch (RuntimeException ex) {
        log.warn(
            "Could not remove consultant {} from team room {} of agency {}: {}",
            consultant.getId(),
            roomId,
            agencyId,
            ex.getMessage());
      }
    }

    log.info(
        "Removed consultant {} from {} of {} team rooms of agency {}",
        consultant.getId(),
        removed,
        roomIds.size(),
        agencyId);
    return removed;
  }

  private boolean removeTeamMemberWithLease(
      Consultant consultant, Long agencyId, String roomId, String token) {
    var lease = claims.tryClaimLease(TEAM_ACCESS_REPAIR_TASK, Duration.ofMinutes(2));
    if (lease.isEmpty()) return false; // Persisted agency eligibility drives the scheduler retry.
    var removed = new AtomicBoolean();
    try {
      claims.runIfHeld(
          lease.get(),
          () -> {
            if (consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
                consultant.getId(), agencyId)) return;
            participantWriter.markAgencyRevocation(consultant.getId(), agencyId);
            removed.set(
                sessionRoomGateway.removeUserFromRoom(roomId, consultant.getMatrixUserId(), token));
          });
      return removed.get();
    } finally {
      claims.release(lease.get());
    }
  }

  /** Retry a recorded team-room revocation using its agency operator. */
  public boolean removeConsultantFromTeamRoom(Consultant consultant, Long agencyId, String roomId) {
    if (consultant == null || isBlank(consultant.getMatrixUserId())) return false;
    var token = resolveAgencyToken(agencyId);
    return !isBlank(token)
        && sessionRoomGateway.removeUserFromRoom(roomId, consultant.getMatrixUserId(), token);
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
    if (isBlank(credentials.getMatrixUserId())) {
      log.warn(
          "Matrix service account configuration incomplete for agency {}; enquiry room membership unchanged.",
          agencyId);
      return null;
    }

    var agencyToken = sessionRoomGateway.loginAsUser(credentials.getMatrixUserId());
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
