package de.caritas.cob.userservice.api.facade;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import de.caritas.cob.userservice.api.service.consultant.ConsultantChatIdentityService;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionCreationWriter;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionFeatureGate;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionRoomCleanupService;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * US#473 / ADR-016: the Team-Besprechung — a team-only Matrix side room attached to one open
 * Agency-Counselling enquiry. Guardrail: team coordination never happens in the client's room; the
 * advice seeker is never invited to (and cannot discover) this room. The discussion closes
 * permanently at acceptance ({@link #archiveDiscussionIfPresent(Session)}) and stays reachable
 * read-only. Participation right = enquiry visibility right — exactly the consultants of the
 * enquiry's agency, no separate permission layer.
 *
 * <p>ORISO-Frontend#2491: while the enquiry is still open, a counsellor can switch the team chat
 * off ({@link #closeDiscussion}, status CLOSED, room read-only like an archive) and on again
 * ({@link #getOrCreateDiscussion} reopens a CLOSED discussion). The switch is gated like
 * Supervision: Träger AND Beratungsstelle ({@link TeamDiscussionFeatureGate}). When {@code
 * featureTeamDiscussionAfterAcceptEnabled} is on, acceptance does not archive: the discussion keeps
 * OPEN or CLOSED and can still be switched off and on; rejection always archives.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamDiscussionFacade {

  /** events_default above the member level (0) — nobody but the operator can post any more. */
  static final int ARCHIVED_EVENTS_DEFAULT_POWER_LEVEL = 50;

  /** events_default of a writable room (Matrix default): every member can post again. */
  static final int OPEN_EVENTS_DEFAULT_POWER_LEVEL = 0;

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull TeamDiscussionRepository teamDiscussionRepository;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull AgencyMatrixCredentialClient matrixCredentialClient;
  private final @NonNull TeamDiscussionFeatureGate featureGate;
  private final @NonNull TeamDiscussionCreationWriter creationWriter;
  private final @NonNull de.caritas.cob.userservice.api.service.teamdiscussion
          .TeamDiscussionAccessTransactions
      accessTransactions;
  private final @NonNull TeamDiscussionRoomCleanupService roomCleanupService;
  private final @NonNull MembersOnlyChatAccess membersOnlyChatAccess;

  /** Read model returned to the controller. */
  public record TeamDiscussionView(
      String matrixRoomId,
      TeamDiscussion.Status status,
      LocalDateTime archiveDate,
      LocalDateTime closedDate,
      boolean readOnly) {}

  /**
   * Returns the enquiry's Team-Besprechung, creating room + record on first use. Joining the caller
   * into the room happens on every call so a colleague opening the panel later gets access. Only
   * OPEN discussions can be created; an ARCHIVED one is returned read-only. A CLOSED one (switched
   * off on a still open enquiry) is switched on again: room writable, status OPEN.
   */
  public TeamDiscussionView getOrCreateDiscussion(Long sessionId, String consultantId) {
    Session session = loadSession(sessionId);
    featureGate.requireEnabled(session);
    Consultant consultant = requireEligibleConsultant(session, consultantId);

    Optional<TeamDiscussion> existing = teamDiscussionRepository.findBySessionId(sessionId);
    if (existing.isPresent()) {
      if (existing.get().getStatus() == TeamDiscussion.Status.CLOSED) {
        // Switching on again commits on its own before the join: a failed join must not roll back
        // OPEN while the room is already writable.
        accessTransactions.read(
            sessionId,
            existing.get().getId(),
            consultantId,
            access -> {
              var discussion = reconcileOnAccess(access.discussion(), access.session());
              if (discussion.getStatus() == TeamDiscussion.Status.CLOSED) {
                // Still CLOSED after reconciliation: an open enquiry, or an accepted case whose
                // team chat continues after acceptance.
                reopenClosedDiscussion(access.session(), discussion);
              }
              return discussion.getStatus();
            });
      }
      return joinAndView(sessionId, existing.get().getId(), consultantId);
    }

    requireStartable(session);

    String roomId = createDiscussionRoom(session);
    TeamDiscussion proposed =
        TeamDiscussion.builder()
            .sessionId(session.getId())
            .matrixRoomId(roomId)
            .status(TeamDiscussion.Status.OPEN)
            .createDate(LocalDateTime.now())
            .createdByConsultantId(consultant.getId())
            .tenantId(session.getTenantId())
            .build();
    TeamDiscussion discussion;
    try {
      discussion = creationWriter.create(proposed);
    } catch (de.caritas.cob.userservice.api.exception.httpresponses.ConflictException rejected) {
      // No ownership or membership committed: compensate only this unused external room.
      var outcome = matrixSynapseService.purgeRoomOrConfirmGone(roomId);
      if (outcome == MatrixSynapseService.RoomPurgeOutcome.FAILED) {
        roomCleanupService.recordFailedCleanup(sessionId, roomId);
        log.error(
            "Unused team discussion room for session {} needs cleanup after rejected creation",
            sessionId);
        throw new ResponseStatusException(
            HttpStatus.BAD_GATEWAY, "Team discussion cleanup failed", rejected);
      }
      throw rejected;
    } catch (DataIntegrityViolationException conflict) {
      // The unique session constraint chooses one shared room across service replicas.
      // The failed insert has rolled back before this fresh read begins.
      discussion = creationWriter.findCommitted(sessionId).orElseThrow(() -> conflict);
      if (!roomId.equals(discussion.getMatrixRoomId())) {
        // No caller has joined our uncommitted room, so it cannot contain team messages.
        var outcome = matrixSynapseService.purgeRoomOrConfirmGone(roomId);
        if (outcome == MatrixSynapseService.RoomPurgeOutcome.FAILED) {
          roomCleanupService.recordFailedCleanup(sessionId, roomId);
          log.error(
              "Unused team discussion room {} for session {} needs cleanup", roomId, sessionId);
          throw new ResponseStatusException(
              HttpStatus.BAD_GATEWAY, "Team discussion cleanup failed", conflict);
        }
      }
    }
    // Create/accept race: re-read the assignment state after committing the row — if the case
    // was accepted while we were creating the room, archive immediately instead of leaving an
    // OPEN discussion on an assigned case.
    return joinAndView(sessionId, discussion.getId(), consultantId);
  }

  /**
   * ORISO-Frontend#2491: switches the team chat of a still open enquiry off. The room becomes
   * read-only for everyone like an archive (participants keep read access); switching on again
   * ({@link #getOrCreateDiscussion}) reopens it. No discussion yet: nothing to do. Already CLOSED:
   * nothing to do.
   *
   * @throws ConflictException 409 when the discussion is ARCHIVED (enquiry accepted or rejected,
   *     ADR-016 hard close)
   */
  public void closeDiscussion(Long sessionId, String consultantId) {
    Session session = loadSession(sessionId);
    featureGate.requireEnabled(session);
    requireEligibleConsultant(session, consultantId);
    Optional<TeamDiscussion> existing = teamDiscussionRepository.findBySessionId(sessionId);
    if (existing.isEmpty()) {
      return;
    }
    // Failures are raised after the transaction: a lazy archive or a CLOSED row whose read-only
    // switch is still pending must commit, so the next access can retry the switch.
    var outcome =
        accessTransactions.read(
            sessionId,
            existing.get().getId(),
            consultantId,
            access -> {
              var discussion = reconcileOnAccess(access.discussion(), access.session());
              if (discussion.getStatus() == TeamDiscussion.Status.OPEN) {
                closeOpenDiscussion(access.session(), discussion);
              }
              return new TeamDiscussionView(
                  discussion.getMatrixRoomId(),
                  discussion.getStatus(),
                  discussion.getArchiveDate(),
                  discussion.getClosedDate(),
                  discussion.isReadOnlyApplied());
            });
    if (outcome.status() == TeamDiscussion.Status.ARCHIVED) {
      throw new ConflictException(
          "The team discussion is archived since the enquiry was accepted or rejected");
    }
    if (!outcome.readOnly()) {
      // CLOSED is recorded, but the room is still writable: never report that as switched off.
      // The row keeps readOnlyApplied=false, so the next DELETE or GET retries the switch.
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "The team discussion is closed but not yet read-only");
    }
  }

  /** Repairs access only to an existing room; never creates a replacement. */
  public void restoreExistingDiscussionMembership(Long discussionId, String consultantId) {
    var discussion = teamDiscussionRepository.findById(discussionId).orElse(null);
    if (discussion == null) return;
    var session = loadSession(discussion.getSessionId());
    featureGate.requireEnabled(session);
    joinAndView(session.getId(), discussion.getId(), consultantId);
  }

  /** Returns the discussion if one exists — also ARCHIVED ones (read-only archive access). */
  public Optional<TeamDiscussionView> getDiscussion(Long sessionId, String consultantId) {
    Session session = loadSession(sessionId);
    featureGate.requireEnabled(session);
    requireEligibleConsultant(session, consultantId);
    return teamDiscussionRepository
        .findBySessionId(sessionId)
        .map(
            discussion ->
                accessTransactions.read(
                    sessionId,
                    discussion.getId(),
                    consultantId,
                    access -> toView(reconcileOnAccess(access.discussion(), access.session()))));
  }

  private TeamDiscussionView joinAndView(Long sessionId, Long discussionId, String consultantId) {
    return accessTransactions.join(
        sessionId,
        discussionId,
        consultantId,
        access -> {
          var discussion = reconcileOnAccess(access.discussion(), access.session());
          if (access.session().getStatus() == SessionStatus.REJECTED
              && !discussion.isReadOnlyApplied()) {
            // An existing joined reader may keep reading while verified closure is pending.
            // Rejoining a departed reader must wait until the room is confirmed read-only.
            if (!isJoinedMember(
                discussion.getMatrixRoomId(), access.consultant().getMatrixUserId())) {
              throw new ResponseStatusException(
                  HttpStatus.SERVICE_UNAVAILABLE, "Team closure is pending");
            }
          } else {
            joinConsultant(discussion, access.session(), access.consultant());
          }
          return toView(discussion);
        });
  }

  /**
   * Lazy reconciliation on every access (review findings F2/F3): an OPEN or CLOSED discussion on an
   * already-accepted case is archived now (the accept-time hook may have raced the creation) unless
   * the team chat continues after acceptance (FE#2491), and an ARCHIVED or CLOSED discussion whose
   * read-only switch failed gets the power-level call retried.
   */
  private TeamDiscussion reconcileOnAccess(TeamDiscussion discussion, Session session) {
    if (session.getStatus() == SessionStatus.REJECTED) {
      // Rejection owns a durable, verified primary+team closure. The legacy default-only
      // switch must neither downgrade its permissions nor certify an incomplete readback.
      if (discussion.getStatus() != TeamDiscussion.Status.ARCHIVED) {
        discussion.setStatus(TeamDiscussion.Status.ARCHIVED);
        discussion.setArchiveDate(LocalDateTime.now());
        discussion.setReadOnlyApplied(false);
        teamDiscussionRepository.save(discussion);
      }
      return discussion;
    }
    boolean sessionAccepted =
        session.getConsultant() != null || session.getStatus() != SessionStatus.NEW;
    if (discussion.getStatus() != TeamDiscussion.Status.ARCHIVED
        && sessionAccepted
        && !featureGate.continuesAfterAccept(session)) {
      // Also a CLOSED discussion: acceptance is the permanent hard close (ADR-016).
      archiveDiscussion(session, discussion);
      return discussion;
    }
    if (discussion.getStatus() != TeamDiscussion.Status.OPEN && !discussion.isReadOnlyApplied()) {
      if (makeRoomReadOnlyBestEffort(session, discussion)) {
        discussion.setReadOnlyApplied(true);
        teamDiscussionRepository.save(discussion);
      }
    }
    return discussion;
  }

  /**
   * ADR-016 hard close: called from the enquiry-accept path. Best-effort by contract — it never
   * throws, because a discussion problem must degrade to "the discussion stays open a little
   * longer", never to "the counsellor cannot accept the case" (same contract as {@code
   * attachStandingSupervisorIfAssigned}).
   */
  public void archiveDiscussionIfPresent(Session session) {
    try {
      if (session == null || session.getId() == null) {
        return;
      }
      Optional<TeamDiscussion> discussionOpt =
          teamDiscussionRepository.findBySessionId(session.getId());
      if (discussionOpt.isEmpty()) {
        return;
      }
      if (discussionOpt.get().getStatus() == TeamDiscussion.Status.ARCHIVED) {
        reconcileOnAccess(discussionOpt.get(), session);
        return;
      }
      // FE#2491: the Träger and Beratungsstelle may let the team chat continue after acceptance.
      // An unreadable setting throws (503) and is logged below: on an outage nothing is archived,
      // the repair scheduler retries.
      if (featureGate.continuesAfterAccept(session)) {
        return;
      }
      archiveDiscussion(session, discussionOpt.get());
    } catch (Exception ex) {
      log.error(
          "Failed to archive team discussion for session {}: {}",
          session != null ? session.getId() : null,
          ex.getMessage());
    }
  }

  private Session loadSession(Long sessionId) {
    var session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new NotFoundException("Session %s not found", sessionId));
    // A team discussion belongs to an open enquiry; a members-only chat's case is none.
    membersOnlyChatAccess.requireOrdinary(
        session, "The session of a members-only chat has no team discussion.");
    return session;
  }

  private Consultant requireEligibleConsultant(Session session, String consultantId) {
    Consultant consultant =
        consultantRepository
            .findById(consultantId)
            .orElseThrow(() -> new ForbiddenException("Consultant not found"));
    // Same rule as TeamDiscussionAccessTransactions: an active membership in the case's agency,
    // also for the assigned counsellor after acceptance (FE#2491).
    if (session.getAgencyId() == null
        || !consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            consultant.getId(), session.getAgencyId())) {
      throw new ForbiddenException(
          "Consultant is not a member of the enquiry's agency and may not access its team"
              + " discussion");
    }
    return consultant;
  }

  /**
   * A team discussion starts on an open, unassigned registered enquiry, or (FE#2491) on its
   * accepted case while the team chat continues after acceptance.
   */
  private void requireStartable(Session session) {
    boolean registered =
        session.getRegistrationType() == RegistrationType.REGISTERED
            && session.getEnquiryMessageDate() != null;
    boolean openEnquiry =
        registered && session.getStatus() == SessionStatus.NEW && session.getConsultant() == null;
    boolean acceptedAndContinuing =
        registered
            && session.getStatus() == SessionStatus.IN_PROGRESS
            && session.getConsultant() != null
            && featureGate.continuesAfterAccept(session);
    if (!openEnquiry && !acceptedAndContinuing) {
      throw new BadRequestException(
          "A team discussion can only be started on an open, unassigned registered enquiry"
              + " (ADR-016: it closes at acceptance)");
    }
  }

  private String createDiscussionRoom(Session session) {
    String agencyToken = loginAgencyOperator(session);
    String alias =
        "team_discussion_"
            + session.getId()
            + "_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String name = "Team-Besprechung – Enquiry " + session.getId();
    org.springframework.http.ResponseEntity<
            de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO>
        response;
    try {
      response = matrixSynapseService.createRoom(name, alias, agencyToken);
    } catch (Exception ex) {
      throw new InternalServerErrorException(
          "Matrix room creation for team discussion failed (session "
              + session.getId()
              + "): "
              + ex.getMessage());
    }
    if (response == null
        || response.getBody() == null
        || response.getBody().getRoomId() == null
        || response.getBody().getRoomId().isBlank()) {
      throw new InternalServerErrorException(
          "Matrix room creation for team discussion failed (session " + session.getId() + ")");
    }
    return response.getBody().getRoomId();
  }

  /**
   * Join the caller before reporting a usable discussion. Failed membership remains retryable; only
   * a confirmed join records participation.
   */
  private void joinConsultant(TeamDiscussion discussion, Session session, Consultant consultant) {
    if (!ConsultantChatIdentityService.hasChatIdentity(consultant)) {
      throw new InternalServerErrorException(
          ConsultantChatIdentityService.missingChatIdentityMessage(
              "Consultant", consultant.getId()));
    }
    // Every open of a request lands here. Inviting a counsellor who is already in the room changes
    // nothing but spends their Matrix invite allowance (rc_invites per_user), and once that runs
    // out every invite waits minutes and the request times out (FE#1799).
    if (isJoinedMember(discussion.getMatrixRoomId(), consultant.getMatrixUserId())) {
      return;
    }
    String agencyToken = loginAgencyOperator(session);
    try {
      try {
        matrixSynapseService.inviteUserToRoom(
            discussion.getMatrixRoomId(), consultant.getMatrixUserId(), agencyToken);
      } catch (Exception inviteEx) {
        log.debug(
            "Invite to team discussion room {} skipped: {}",
            discussion.getMatrixRoomId(),
            inviteEx.getMessage());
      }
      String consultantToken =
          matrixSynapseService.loginAsUserAccessToken(consultant.getMatrixUserId());
      if (consultantToken == null
          || consultantToken.isBlank()
          || !matrixSynapseService.joinRoom(discussion.getMatrixRoomId(), consultantToken)) {
        throw new InternalServerErrorException("Could not join the team discussion");
      }
    } catch (Exception ex) {
      log.warn(
          "Could not join consultant {} into team discussion room {}: {}",
          consultant.getId(),
          discussion.getMatrixRoomId(),
          ex.getMessage());
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Could not join the team discussion", ex);
    }
  }

  /** Unknown membership counts as not joined, so the invite-and-join path still runs. */
  private boolean isJoinedMember(String roomId, String matrixUserId) {
    return matrixSynapseService
        .getRoomMembers(roomId)
        .map(members -> members.contains(matrixUserId))
        .orElse(false);
  }

  /**
   * ARCHIVED is persisted first (notifications must stop at acceptance regardless), then the
   * read-only switch is attempted; a failure is tracked via {@code readOnlyApplied=false} and
   * retried on next access ({@link #reconcileOnAccess}) instead of being forgotten.
   */
  private void archiveDiscussion(Session session, TeamDiscussion discussion) {
    discussion.setStatus(TeamDiscussion.Status.ARCHIVED);
    discussion.setArchiveDate(LocalDateTime.now());
    discussion.setReadOnlyApplied(false);
    teamDiscussionRepository.save(discussion);

    if (makeRoomReadOnlyBestEffort(session, discussion)) {
      discussion.setReadOnlyApplied(true);
      teamDiscussionRepository.save(discussion);
    }
    log.info(
        "Archived team discussion for session {} (room {}, readOnlyApplied={})",
        session.getId(),
        discussion.getMatrixRoomId(),
        discussion.isReadOnlyApplied());
  }

  /**
   * CLOSED is persisted first, then the read-only switch is attempted like for an archive; a
   * failure is tracked via {@code readOnlyApplied=false} and retried on next access.
   */
  private void closeOpenDiscussion(Session session, TeamDiscussion discussion) {
    discussion.setStatus(TeamDiscussion.Status.CLOSED);
    discussion.setClosedDate(LocalDateTime.now());
    discussion.setReadOnlyApplied(false);
    teamDiscussionRepository.save(discussion);
    if (makeRoomReadOnlyBestEffort(session, discussion)) {
      discussion.setReadOnlyApplied(true);
      teamDiscussionRepository.save(discussion);
    }
    log.info(
        "Closed team discussion for session {} (room {}, readOnlyApplied={})",
        session.getId(),
        discussion.getMatrixRoomId(),
        discussion.isReadOnlyApplied());
  }

  /**
   * The room is made writable first; only then is the discussion OPEN again. A failed Matrix call
   * leaves it CLOSED and answers 502, so status and room never disagree towards "open".
   */
  private void reopenClosedDiscussion(Session session, TeamDiscussion discussion) {
    boolean writable;
    try {
      writable =
          matrixSynapseService.setRoomEventsDefaultPowerLevel(
              discussion.getMatrixRoomId(),
              OPEN_EVENTS_DEFAULT_POWER_LEVEL,
              loginAgencyOperator(session));
    } catch (Exception ex) {
      log.warn(
          "Could not make team discussion room {} writable again: {}",
          discussion.getMatrixRoomId(),
          ex.getMessage());
      writable = false;
    }
    if (!writable) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Could not switch the team discussion on again");
    }
    try {
      discussion.setStatus(TeamDiscussion.Status.OPEN);
      discussion.setReadOnlyApplied(false);
      teamDiscussionRepository.saveAndFlush(discussion);
    } catch (RuntimeException failed) {
      // The row stays CLOSED (rollback): put the room back to read-only so both agree.
      discussion.setStatus(TeamDiscussion.Status.CLOSED);
      if (!makeRoomReadOnlyBestEffort(session, discussion)) {
        log.error(
            "Team discussion room {} is writable while its row stays CLOSED",
            discussion.getMatrixRoomId());
      }
      throw failed;
    }
    log.info(
        "Reopened team discussion for session {} (room {})",
        session.getId(),
        discussion.getMatrixRoomId());
  }

  private boolean makeRoomReadOnlyBestEffort(Session session, TeamDiscussion discussion) {
    try {
      String agencyToken = loginAgencyOperator(session);
      return matrixSynapseService.setRoomEventsDefaultPowerLevel(
          discussion.getMatrixRoomId(), ARCHIVED_EVENTS_DEFAULT_POWER_LEVEL, agencyToken);
    } catch (Exception ex) {
      log.error(
          "Could not set team discussion room {} read-only: {}",
          discussion.getMatrixRoomId(),
          ex.getMessage());
      return false;
    }
  }

  private String loginAgencyOperator(Session session) {
    AgencyMatrixCredentialsDTO credentials =
        matrixCredentialClient
            .fetchMatrixCredentials(session.getAgencyId())
            .orElseThrow(
                () ->
                    new InternalServerErrorException(
                        "No Matrix service account for agency " + session.getAgencyId()));
    if (credentials.getMatrixUserId() == null || credentials.getMatrixUserId().isBlank()) {
      throw new InternalServerErrorException(
          "Matrix service account for agency " + session.getAgencyId() + " is incomplete");
    }
    String token = matrixSynapseService.loginAsUserAccessToken(credentials.getMatrixUserId());
    if (token == null || token.isBlank()) {
      throw new InternalServerErrorException(
          "Matrix service account login failed for agency " + session.getAgencyId());
    }
    return token;
  }

  private TeamDiscussionView toView(TeamDiscussion discussion) {
    return new TeamDiscussionView(
        discussion.getMatrixRoomId(),
        discussion.getStatus(),
        discussion.getArchiveDate(),
        discussion.getClosedDate(),
        discussion.getStatus() != TeamDiscussion.Status.OPEN && discussion.isReadOnlyApplied());
  }
}
