package de.caritas.cob.userservice.api.facade.sessionlist;

import de.caritas.cob.userservice.api.adapters.web.dto.ConversationType;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * FE#1876: sets {@code isTopicDuplicate} on the asker's list from data already in it — no extra
 * query. Mirrors {@code TopicDuplicateService.isNewerDuplicate}: accepted 1:1 session with an older
 * accepted 1:1 session on the same topic.
 */
final class TopicDuplicateMarker {

  // createDate is the fixed-width ISO format from CustomLocalDateTime, so it sorts as text.
  private static final Comparator<SessionDTO> AGE =
      Comparator.comparing(SessionDTO::getCreateDate, Comparator.nullsFirst(String::compareTo))
          .thenComparing(SessionDTO::getId, Comparator.nullsFirst(Long::compareTo));

  private TopicDuplicateMarker() {}

  static void mark(List<UserSessionResponseDTO> userSessions) {
    var candidates =
        userSessions.stream()
            .map(UserSessionResponseDTO::getSession)
            .filter(Objects::nonNull)
            .filter(TopicDuplicateMarker::isActiveOneToOneWithTopic)
            .toList();
    for (var session : candidates) {
      boolean newer =
          candidates.stream()
              .anyMatch(
                  other ->
                      other != session
                          && Objects.equals(other.getTopic().getId(), session.getTopic().getId())
                          && AGE.compare(other, session) < 0);
      session.setIsTopicDuplicate(newer);
    }
  }

  private static boolean isActiveOneToOneWithTopic(SessionDTO session) {
    var nullable = session.getConversationType();
    var type = nullable != null && nullable.isPresent() ? nullable.get() : null;
    return Objects.equals(session.getStatus(), SessionStatus.IN_PROGRESS.getValue())
        && session.getTopic() != null
        && session.getTopic().getId() != null
        && (type == null
            || type == ConversationType.AGENCY_COUNSELLING
            || type == ConversationType.LIVE_CHAT);
  }
}
