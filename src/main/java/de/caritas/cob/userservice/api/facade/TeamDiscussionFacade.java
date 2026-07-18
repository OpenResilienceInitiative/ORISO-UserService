package de.caritas.cob.userservice.api.facade;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.model.TeamDiscussionParticipant;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionParticipantRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionFeatureGate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * US#473 / ADR-016: the Team-Besprechung — a team-only Matrix side room attached to one open
 * Agency-Counselling enquiry. Guardrail: team coordination never happens in the client's room; the
 * advice seeker is never invited to (and cannot discover) this room. The discussion closes
 * permanently at acceptance ({@link #archiveDiscussionIfPresent(Session)}) and stays reachable
 * read-only. Participation right = enquiry visibility right — exactly the consultants of the
 * enquiry's agency, no separate permission layer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamDiscussionFacade {

  /** events_default above the member level (0) — nobody but the operator can post any more. */
  static final int ARCHIVED_EVENTS_DEFAULT_POWER_LEVEL = 50;

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull TeamDiscussionRepository teamDiscussionRepository;
  private final @NonNull TeamDiscussionParticipantRepository participantRepository;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull AgencyMatrixCredentialClient matrixCredentialClient;
  private final @NonNull TeamDiscussionFeatureGate featureGate;

  /** Read model returned to the controller. */
  public record TeamDiscussionView(
      String matrixRoomId, TeamDiscussion.Status status, LocalDateTime archiveDate) {}

  /**
   * Returns the enquiry's Team-Besprechung, creating room + record on first use. Joining the caller
   * into the room happens on every call so a colleague opening the panel later gets access. Only
   * OPEN discussions can be created; an ARCHIVED one is returned read-only.
   */
  public TeamDiscussionView getOrCreateDiscussion(Long sessionId, String consultantId) {
    featureGate.requireEnabled();
    Session session = loadSession(sessionId);
    Consultant consultant = requireEligibleConsultant(session, consultantId);

    Optional<TeamDiscussion> existing = teamDiscussionRepository.findBySessionId(sessionId);
    if (existing.isPresent()) {
      TeamDiscussion discussion = existing.get();
      joinConsultantBestEffort(discussion, session, consultant);
      return toView(discussion);
    }

    requireOpenEnquiry(session);

    String roomId = createDiscussionRoom(session);
    TeamDiscussion discussion =
        teamDiscussionRepository.save(
            TeamDiscussion.builder()
                .sessionId(session.getId())
                .matrixRoomId(roomId)
                .status(TeamDiscussion.Status.OPEN)
                .createDate(LocalDateTime.now())
                .createdByConsultantId(consultant.getId())
                .tenantId(session.getTenantId())
                .build());
    joinConsultantBestEffort(discussion, session, consultant);
    return toView(discussion);
  }

  /** Returns the discussion if one exists — also ARCHIVED ones (read-only archive access). */
  public Optional<TeamDiscussionView> getDiscussion(Long sessionId, String consultantId) {
    featureGate.requireEnabled();
    Session session = loadSession(sessionId);
    requireEligibleConsultant(session, consultantId);
    return teamDiscussionRepository.findBySessionId(sessionId).map(this::toView);
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
      if (discussionOpt.isEmpty()
          || discussionOpt.get().getStatus() == TeamDiscussion.Status.ARCHIVED) {
        return;
      }
      TeamDiscussion discussion = discussionOpt.get();
      discussion.setStatus(TeamDiscussion.Status.ARCHIVED);
      discussion.setArchiveDate(LocalDateTime.now());
      teamDiscussionRepository.save(discussion);

      makeRoomReadOnlyBestEffort(session, discussion);
      log.info(
          "Archived team discussion for session {} (room {})",
          session.getId(),
          discussion.getMatrixRoomId());
    } catch (Exception ex) {
      log.error(
          "Failed to archive team discussion for session {}: {}",
          session != null ? session.getId() : null,
          ex.getMessage());
    }
  }

  private Session loadSession(Long sessionId) {
    return sessionRepository
        .findById(sessionId)
        .orElseThrow(() -> new NotFoundException("Session %s not found", sessionId));
  }

  private Consultant requireEligibleConsultant(Session session, String consultantId) {
    Consultant consultant =
        consultantRepository
            .findById(consultantId)
            .orElseThrow(() -> new ForbiddenException("Consultant not found"));
    if (session.getAgencyId() == null
        || !consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            consultant.getId(), session.getAgencyId())) {
      throw new ForbiddenException(
          "Consultant is not a member of the enquiry's agency and may not access its team"
              + " discussion");
    }
    return consultant;
  }

  private void requireOpenEnquiry(Session session) {
    boolean openEnquiry =
        session.getStatus() == SessionStatus.NEW
            && session.getRegistrationType() == RegistrationType.REGISTERED
            && session.getConsultant() == null;
    if (!openEnquiry) {
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
   * Invite + join the consultant and record them as participant. Best-effort: an already-joined
   * consultant or a transient Matrix error must not break opening the panel.
   */
  private void joinConsultantBestEffort(
      TeamDiscussion discussion, Session session, Consultant consultant) {
    try {
      if (consultant.getMatrixUserId() == null || consultant.getMatrixUserId().isBlank()) {
        return;
      }
      String agencyToken = loginAgencyOperator(session);
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
      if (consultantToken != null) {
        matrixSynapseService.joinRoom(discussion.getMatrixRoomId(), consultantToken);
      }
    } catch (Exception ex) {
      log.warn(
          "Could not join consultant {} into team discussion room {}: {}",
          consultant.getId(),
          discussion.getMatrixRoomId(),
          ex.getMessage());
    }
    recordParticipant(discussion, consultant.getId());
  }

  private void recordParticipant(TeamDiscussion discussion, String consultantId) {
    if (participantRepository.existsByTeamDiscussionIdAndConsultantId(
        discussion.getId(), consultantId)) {
      return;
    }
    participantRepository.save(
        TeamDiscussionParticipant.builder()
            .teamDiscussionId(discussion.getId())
            .consultantId(consultantId)
            .joinDate(LocalDateTime.now())
            .build());
  }

  private void makeRoomReadOnlyBestEffort(Session session, TeamDiscussion discussion) {
    try {
      String agencyToken = loginAgencyOperator(session);
      matrixSynapseService.setRoomEventsDefaultPowerLevel(
          discussion.getMatrixRoomId(), ARCHIVED_EVENTS_DEFAULT_POWER_LEVEL, agencyToken);
    } catch (Exception ex) {
      log.error(
          "Could not set team discussion room {} read-only: {}",
          discussion.getMatrixRoomId(),
          ex.getMessage());
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
    if (credentials.getMatrixUserId() == null
        || credentials.getMatrixUserId().isBlank()
        || credentials.getMatrixPassword() == null
        || credentials.getMatrixPassword().isBlank()) {
      throw new InternalServerErrorException(
          "Matrix service account for agency " + session.getAgencyId() + " is incomplete");
    }
    String token =
        matrixSynapseService.loginUser(
            extractLocalPart(credentials.getMatrixUserId()), credentials.getMatrixPassword());
    if (token == null || token.isBlank()) {
      throw new InternalServerErrorException(
          "Matrix service account login failed for agency " + session.getAgencyId());
    }
    return token;
  }

  private String extractLocalPart(String matrixUserId) {
    String value = matrixUserId.startsWith("@") ? matrixUserId.substring(1) : matrixUserId;
    int colon = value.indexOf(':');
    return colon > 0 ? value.substring(0, colon) : value;
  }

  private TeamDiscussionView toView(TeamDiscussion discussion) {
    return new TeamDiscussionView(
        discussion.getMatrixRoomId(), discussion.getStatus(), discussion.getArchiveDate());
  }
}
