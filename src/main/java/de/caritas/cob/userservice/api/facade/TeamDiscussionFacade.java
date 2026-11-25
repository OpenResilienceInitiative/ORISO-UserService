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
import java.util.HashMap;
import java.util.Map;
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
    var target = resolve(session, consultantId);
    Consultant consultant = target.consultant();

    Optional<TeamDiscussion> existing = target.discussion();
    if (target.moved()) {
      // A former Beratungsstelle's discussion: read-only join, never reopened or replaced.
      return joinAndView(sessionId, existing.get().getId(), consultantId);
    }
    if (existing.isPresent()) {
      if (existing.get().getStatus() == TeamDiscussion.Status.CLOSED) {
        // Switching on again commits on its own before the join: a failed join must not roll back
        // OPEN while the room is already writable.
        Boolean reopened;
        try {
          reopened =
              accessTransactions.read(
                  sessionId,
                  existing.get().getId(),
                  consultantId,
                  access -> {
                    var discussion = reconcileOnAccess(access.discussion(), access.session());
                    // Still CLOSED after reconciliation: an open enquiry, or an accepted case
                    // whose team chat continues after acceptance.
                    return discussion.getStatus() != TeamDiscussion.Status.CLOSED
                        || reopenClosedDiscussion(access.session(), discussion);
                  });
        } catch (ReopenCompensationFailed failed) {
          markReadOnlyAfterFailedReopen(existing.get().getId());
          throw (RuntimeException) failed.getCause();
        }
        if (!Boolean.TRUE.equals(reopened)) {
          // Raised after the transaction, so the CLOSED row's read-only marker is committed.
          throw new ResponseStatusException(
              HttpStatus.BAD_GATEWAY, "Could not switch the team discussion on again");
        }
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
            .agencyId(session.getAgencyId())
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
    Optional<TeamDiscussion> existing = resolve(session, consultantId).discussion();
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
    featureGate.requireEnabled(session.getTenantId(), agencyOf(discussion, session));
    joinAndView(session.getId(), discussion.getId(), consultantId);
  }

  /** Returns the discussion if one exists — also ARCHIVED ones (read-only archive access). */
  public Optional<TeamDiscussionView> getDiscussion(Long sessionId, String consultantId) {
    Session session = loadSession(sessionId);
    return resolve(session, consultantId)
        .discussion()
        .map(
            discussion ->
                accessTransactions.read(
                    sessionId,
                    discussion.getId(),
                    consultantId,
                    access -> toView(reconcileOnAccess(access.discussion(), access.session()))));
  }

  private TeamDiscussionView joinAndView(Long sessionId, Long discussionId, String consultantId) {
    // Refusal is raised after the transaction: a lazy archive or close recorded by the
    // reconciliation must commit with its pending marker, so the next access retries the switch.
    Optional<TeamDiscussionView> joined =
        accessTransactions.join(
            sessionId,
            discussionId,
            consultantId,
            access -> {
              var discussion = reconcileOnAccess(access.discussion(), access.session());
              if (discussion.getStatus() != TeamDiscussion.Status.OPEN
                  && !discussion.isReadOnlyApplied()) {
                // An existing joined reader may keep reading while the read-only switch (or the
                // rejection's verified closure) is pending. A new reader of a CLOSED or ARCHIVED
                // chat must wait until the room is confirmed read-only.
                if (!isJoinedMember(
                    discussion.getMatrixRoomId(), access.consultant().getMatrixUserId())) {
                  return Optional.empty();
                }
              } else {
                joinConsultant(discussion, access.session(), access.consultant());
              }
              return Optional.of(toView(discussion));
            });
    return joined.orElseThrow(
        () ->
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Team closure is pending"));
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
    if (movedAway(discussion, session)) {
      // D4: the case moved to another Beratungsstelle; its team chat closes for good.
      if (discussion.getStatus() != TeamDiscussion.Status.ARCHIVED) {
        archiveDiscussion(session, discussion);
      } else if (!discussion.isReadOnlyApplied()
          && makeRoomReadOnlyBestEffort(session, discussion)) {
        discussion.setReadOnlyApplied(true);
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
    archiveDiscussionIfPresent(session, new HashMap<>());
  }

  /**
   * The repair sweep's variant: {@code decisionsOfThisSweep} remembers whether the team chat
   * continues after acceptance per Träger and Beratungsstelle, so a sweep over many continuing
   * discussions reads the settings once per pair instead of once per row (FE#2491). An unreadable
   * setting is remembered as "unknown" for the sweep: nothing is archived, the next sweep retries.
   * Switching the setting or the master off is seen by the next sweep, which then archives.
   */
  public void archiveDiscussionIfPresent(
      Session session, Map<String, Optional<Boolean>> decisionsOfThisSweep) {
    try {
      if (session == null || session.getId() == null) {
        return;
      }
      archiveMovedDiscussions(session);
      Optional<TeamDiscussion> discussionOpt = currentDiscussion(session);
      if (discussionOpt.isEmpty()) {
        return;
      }
      if (discussionOpt.get().getStatus() == TeamDiscussion.Status.ARCHIVED
          || session.getStatus() == SessionStatus.REJECTED) {
        // A rejected case never continues: its rejection-owned closure archives the row without
        // certifying the default-only read-only switch.
        reconcileOnAccess(discussionOpt.get(), session);
        return;
      }
      // FE#2491: the Träger and Beratungsstelle may let the team chat continue after acceptance.
      var continues =
          decisionsOfThisSweep.computeIfAbsent(
              session.getTenantId() + ":" + session.getAgencyId(),
              pair -> continuesAfterAcceptOrUnknown(session));
      if (continues.isEmpty()) {
        return;
      }
      if (continues.get()) {
        // A continuing CLOSED chat whose read-only switch is unconfirmed is retried here too.
        retryReadOnlyIfPending(session, discussionOpt.get());
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

  private Optional<Boolean> continuesAfterAcceptOrUnknown(Session session) {
    try {
      return Optional.of(featureGate.continuesAfterAccept(session));
    } catch (RuntimeException unavailable) {
      log.warn(
          "Team discussion of session {} not archived: the after-acceptance setting is not"
              + " readable ({})",
          session.getId(),
          unavailable.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /**
   * ORISO-Frontend#2491: joins the caller into an existing discussion without changing it — the
   * read-only way into a CLOSED or ARCHIVED team chat (and a plain join into an OPEN one). Never
   * creates, never reopens.
   *
   * @return empty when the enquiry has no team discussion
   */
  public Optional<TeamDiscussionView> joinDiscussion(Long sessionId, String consultantId) {
    Session session = loadSession(sessionId);
    return resolve(session, consultantId)
        .discussion()
        .map(discussion -> joinAndView(sessionId, discussion.getId(), consultantId));
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
    String agencyToken = loginAgencyOperator(agencyOf(discussion, session));
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
  private boolean reopenClosedDiscussion(Session session, TeamDiscussion discussion) {
    boolean writable;
    try {
      writable =
          matrixSynapseService.setRoomEventsDefaultPowerLevel(
              discussion.getMatrixRoomId(),
              OPEN_EVENTS_DEFAULT_POWER_LEVEL,
              loginAgencyOperator(agencyOf(discussion, session)));
    } catch (Exception ex) {
      log.warn(
          "Could not make team discussion room {} writable again: {}",
          discussion.getMatrixRoomId(),
          ex.getMessage());
      writable = false;
    }
    if (!writable) {
      // Unconfirmed is not "rejected": Synapse may have applied the write before a timeout. Put
      // the room back to read-only; if that is unconfirmed too, the row stays CLOSED with
      // readOnlyApplied=false and the next access or sweep retries the switch.
      discussion.setReadOnlyApplied(makeRoomReadOnlyBestEffort(session, discussion));
      teamDiscussionRepository.save(discussion);
      return false;
    }
    try {
      discussion.setStatus(TeamDiscussion.Status.OPEN);
      discussion.setReadOnlyApplied(false);
      teamDiscussionRepository.saveAndFlush(discussion);
    } catch (RuntimeException failed) {
      // The row stays CLOSED (rollback): put the room back to read-only so both agree. Whether
      // that worked is recorded after the rollback by the caller (markReadOnlyAfterFailedReopen).
      discussion.setStatus(TeamDiscussion.Status.CLOSED);
      if (!makeRoomReadOnlyBestEffort(session, discussion)) {
        log.error(
            "Team discussion room {} is writable while its row stays CLOSED",
            discussion.getMatrixRoomId());
        throw new ReopenCompensationFailed(failed);
      }
      throw failed;
    }
    log.info(
        "Reopened team discussion for session {} (room {})",
        session.getId(),
        discussion.getMatrixRoomId());
    return true;
  }

  /** The room may be writable although the row says CLOSED and read-only (FE#2491). */
  private static final class ReopenCompensationFailed extends RuntimeException {
    ReopenCompensationFailed(RuntimeException cause) {
      super(cause.getMessage(), cause);
    }
  }

  /**
   * After a reopen whose transaction rolled back and whose compensating read-only switch was not
   * confirmed, the committed row still claims readOnlyApplied=true. A fresh write clears that claim
   * so the next access or repair sweep retries the switch. If the database is still failing, the
   * marker cannot be written: that is logged as an error for operators, and the room stays as it is
   * until the next switch-off or switch-on.
   */
  private void markReadOnlyAfterFailedReopen(Long discussionId) {
    try {
      teamDiscussionRepository
          .findById(discussionId)
          .filter(discussion -> discussion.getStatus() == TeamDiscussion.Status.CLOSED)
          .ifPresent(
              discussion -> {
                discussion.setReadOnlyApplied(false);
                teamDiscussionRepository.save(discussion);
              });
    } catch (RuntimeException stillFailing) {
      log.error(
          "Team discussion {} may be writable while CLOSED; the retry marker could not be"
              + " written: {}",
          discussionId,
          stillFailing.getMessage());
    }
  }

  /** Retries the read-only switch of a CLOSED or ARCHIVED row whose switch is unconfirmed. */
  private void retryReadOnlyIfPending(Session session, TeamDiscussion discussion) {
    if (discussion.getStatus() != TeamDiscussion.Status.OPEN
        && !discussion.isReadOnlyApplied()
        && makeRoomReadOnlyBestEffort(session, discussion)) {
      discussion.setReadOnlyApplied(true);
      teamDiscussionRepository.save(discussion);
    }
  }

  private boolean makeRoomReadOnlyBestEffort(Session session, TeamDiscussion discussion) {
    try {
      String agencyToken = loginAgencyOperator(agencyOf(discussion, session));
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
    return loginAgencyOperator(session.getAgencyId());
  }

  /** The room belongs to the Beratungsstelle that created it, also after the case moved. */
  private String loginAgencyOperator(Long agencyId) {
    AgencyMatrixCredentialsDTO credentials =
        matrixCredentialClient
            .fetchMatrixCredentials(agencyId)
            .orElseThrow(
                () ->
                    new InternalServerErrorException(
                        "No Matrix service account for agency " + agencyId));
    if (credentials.getMatrixUserId() == null || credentials.getMatrixUserId().isBlank()) {
      throw new InternalServerErrorException(
          "Matrix service account for agency " + agencyId + " is incomplete");
    }
    String token = matrixSynapseService.loginAsUserAccessToken(credentials.getMatrixUserId());
    if (token == null || token.isBlank()) {
      throw new InternalServerErrorException(
          "Matrix service account login failed for agency " + agencyId);
    }
    return token;
  }

  /** The Beratungsstelle a discussion belongs to; legacy rows without one belong to the case's. */
  static Long agencyOf(TeamDiscussion discussion, Session session) {
    return discussion.getAgencyId() != null ? discussion.getAgencyId() : session.getAgencyId();
  }

  /** Whether the case moved to another Beratungsstelle after this discussion was started (D4). */
  static boolean movedAway(TeamDiscussion discussion, Session session) {
    return discussion.getAgencyId() != null
        && !discussion.getAgencyId().equals(session.getAgencyId());
  }

  /** What a caller may see: the case's current discussion, or a former Beratungsstelle's one. */
  private record Target(
      Consultant consultant, Optional<TeamDiscussion> discussion, boolean moved) {}

  /**
   * D4 (FE#2491): members of the case's current Beratungsstelle see only its own discussion; a
   * discussion of a Beratungsstelle the case moved away from is archived and stays readable only
   * for that Beratungsstelle's members. Everyone else is refused. One rule throughout: an active
   * membership in the discussion's Beratungsstelle (as in {@code
   * TeamDiscussionAccessTransactions}).
   */
  private Target resolve(Session session, String consultantId) {
    Consultant consultant =
        consultantRepository
            .findById(consultantId)
            .orElseThrow(() -> new ForbiddenException("Consultant not found"));
    if (session.getAgencyId() != null && isMember(consultant.getId(), session.getAgencyId())) {
      featureGate.requireEnabled(session);
      archiveMovedDiscussions(session);
      return new Target(consultant, currentDiscussion(session), false);
    }
    var formerDiscussions =
        teamDiscussionRepository.findAllBySessionIdOrderByIdDesc(session.getId()).stream()
            .filter(discussion -> movedAway(discussion, session))
            .toList();
    // One membership read for all former Beratungsstellen, not one per former discussion.
    var activeAgencies =
        formerDiscussions.isEmpty()
            ? java.util.Set.<Long>of()
            : consultantAgencyRepository
                .findByConsultantIdAndDeleteDateIsNull(consultant.getId())
                .stream()
                .map(de.caritas.cob.userservice.api.model.ConsultantAgency::getAgencyId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    var former =
        formerDiscussions.stream()
            .filter(discussion -> activeAgencies.contains(discussion.getAgencyId()))
            .findFirst();
    if (former.isEmpty()) {
      throw new ForbiddenException(
          "Consultant is not a member of the enquiry's agency and may not access its team"
              + " discussion");
    }
    featureGate.requireEnabled(session.getTenantId(), former.get().getAgencyId());
    return new Target(consultant, former, true);
  }

  /**
   * The discussion of the case's current Beratungsstelle. After a move back (A → B → A) the newest
   * row is B's, so A's own row is looked up explicitly; a legacy row without a Beratungsstelle
   * counts only when no row of the current one exists.
   */
  private Optional<TeamDiscussion> currentDiscussion(Session session) {
    var newest = teamDiscussionRepository.findBySessionId(session.getId());
    if (newest.isEmpty() || !movedAway(newest.get(), session)) {
      return newest;
    }
    var all = teamDiscussionRepository.findAllBySessionIdOrderByIdDesc(session.getId());
    return all.stream()
        .filter(
            discussion -> java.util.Objects.equals(discussion.getAgencyId(), session.getAgencyId()))
        .findFirst()
        .or(() -> all.stream().filter(discussion -> discussion.getAgencyId() == null).findFirst());
  }

  private boolean isMember(String consultantId, Long agencyId) {
    return agencyId != null
        && consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            consultantId, agencyId);
  }

  /** Archives every discussion of a Beratungsstelle the case moved away from (D4). */
  private void archiveMovedDiscussions(Session session) {
    for (var discussion :
        teamDiscussionRepository.findAllBySessionIdOrderByIdDesc(session.getId())) {
      if (movedAway(discussion, session)
          && discussion.getStatus() != TeamDiscussion.Status.ARCHIVED) {
        if (session.getStatus() == SessionStatus.REJECTED) {
          // Rejection owns a verified closure: archive without the default-only switch.
          reconcileOnAccess(discussion, session);
        } else {
          archiveDiscussion(session, discussion);
        }
      }
    }
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
