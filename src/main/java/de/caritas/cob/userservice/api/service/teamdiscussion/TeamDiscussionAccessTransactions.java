package de.caritas.cob.userservice.api.service.teamdiscussion;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.model.TeamDiscussionParticipant;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionParticipantRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serializes audience admission with rejection of the same enquiry. Creation commits separately.
 */
@Service
@RequiredArgsConstructor
public class TeamDiscussionAccessTransactions {
  private final @NonNull SessionRepository sessions;
  private final @NonNull ConsultantRepository consultants;
  private final @NonNull ConsultantAgencyRepository agencies;
  private final @NonNull TeamDiscussionRepository discussions;
  private final @NonNull TeamDiscussionParticipantRepository participants;

  public record Access(Session session, TeamDiscussion discussion, Consultant consultant) {}

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public <T> T read(
      Long sessionId, Long discussionId, String consultantId, Function<Access, T> projection) {
    return projection.apply(currentAccess(sessionId, discussionId, consultantId));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public <T> T join(
      Long sessionId, Long discussionId, String consultantId, Function<Access, T> confirmedJoin) {
    var access = currentAccess(sessionId, discussionId, consultantId);
    // The session lock spans the bounded Matrix join and its committed participant record.
    // A rejection cannot freeze its audience halfway through this admission.
    T result = confirmedJoin.apply(access);
    if (!participants.existsByTeamDiscussionIdAndConsultantId(discussionId, consultantId)) {
      participants.saveAndFlush(
          TeamDiscussionParticipant.builder()
              .teamDiscussionId(discussionId)
              .consultantId(consultantId)
              .joinDate(LocalDateTime.now())
              .build());
    }
    return result;
  }

  private Access currentAccess(Long sessionId, Long discussionId, String consultantId) {
    var session =
        sessions
            .findByIdForUpdate(sessionId)
            .orElseThrow(() -> new NotFoundException("Session unavailable"));
    var discussion =
        discussions
            .findById(discussionId)
            .filter(row -> Objects.equals(row.getSessionId(), sessionId))
            .orElseThrow(() -> new NotFoundException("Team discussion unavailable"));
    var consultant =
        consultants
            .findById(consultantId)
            .filter(row -> row.getDeleteDate() == null)
            .orElseThrow(() -> new ForbiddenException("Current consultant required"));
    if (session.getAgencyId() == null
        || !agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            consultantId, session.getAgencyId())) {
      throw new ForbiddenException("Current owning agency membership required");
    }
    if (session.getStatus() == Session.SessionStatus.REJECTED
        && !participants.existsByTeamDiscussionIdAndConsultantId(discussionId, consultantId)) {
      throw new ForbiddenException("Rejected enquiry cannot admit a new team reader");
    }
    return new Access(session, discussion, consultant);
  }
}
