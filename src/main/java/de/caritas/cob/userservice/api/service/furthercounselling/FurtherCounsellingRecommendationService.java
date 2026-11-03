package de.caritas.cob.userservice.api.service.furthercounselling;

import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import java.time.Clock;
import java.time.Instant;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * FE#1876 S2: the assigned counsellor recommends further counselling on a topic. The room gets a
 * system card; the asker opens the request dialog from it.
 */
@Service
@RequiredArgsConstructor
public class FurtherCounsellingRecommendationService {

  public static final int MAX_NOTE_LENGTH = 1000;

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull MembersOnlyChatAccess membersOnlyChatAccess;
  private final @NonNull TopicService topicService;
  private final @NonNull ConsultantDisplayNameResolver displayNames;
  private final @NonNull MatrixSessionSystemMessageService systemMessages;
  private final Clock clock = Clock.systemUTC();

  public void recommend(Long sessionId, Long topicId, String note) {
    Session session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new NotFoundException("Session with id %s not found.", sessionId));
    if (!session.isAdvisedBy(authenticatedUser.getUserId())) {
      throw new ForbiddenException(
          String.format(
              "Only the assigned counsellor may recommend further counselling for session %s",
              sessionId));
    }
    if (session.getStatus() != IN_PROGRESS
        || !TopicDuplicateService.isOneToOne(session)
        || membersOnlyChatAccess.concerns(session)) {
      throw new ConflictException(
          String.format("Session %s is no accepted 1:1 conversation", sessionId));
    }
    var normalizedNote = normalizeNote(note);
    if (topicId == null || topicService.getTopicById(topicId) == null) {
      throw new BadRequestException(String.format("Topic %s does not exist", topicId));
    }
    // The card must come from the counsellor; never post it under the asker's account.
    if (!systemMessages.hasConsultantMatrixIdentity(session)) {
      throw new ConflictException(
          String.format("The counsellor of session %s has no Matrix account", sessionId));
    }
    var body =
        FurtherCounsellingNotices.recommended(
            topicId,
            normalizedNote,
            displayNames.resolveMatrixDisplayName(session.getConsultant()),
            Instant.now(clock));
    if (!systemMessages.postSystemMessage(session, body, true)) {
      throw new InternalServerErrorException(
          String.format("Recommendation for session %s could not be posted", sessionId));
    }
  }

  static String normalizeNote(String note) {
    if (isBlank(note)) {
      return null;
    }
    var trimmed = note.trim();
    if (trimmed.length() > MAX_NOTE_LENGTH) {
      throw new BadRequestException("Note is longer than " + MAX_NOTE_LENGTH + " characters");
    }
    return trimmed;
  }
}
