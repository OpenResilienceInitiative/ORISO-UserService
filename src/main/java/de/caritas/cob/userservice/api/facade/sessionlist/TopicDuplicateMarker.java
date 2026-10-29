package de.caritas.cob.userservice.api.facade.sessionlist;

import de.caritas.cob.userservice.api.adapters.web.dto.SessionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.service.furthercounselling.TopicDuplicateRule;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

/**
 * FE#1876: sets {@code isTopicDuplicate} on the asker's list from data already in it — no extra
 * query. Uses {@link TopicDuplicateRule}, the same rule as the end-duplicate endpoint.
 */
final class TopicDuplicateMarker {

  private TopicDuplicateMarker() {}

  static void mark(List<UserSessionResponseDTO> userSessions) {
    var sessions =
        userSessions.stream()
            .map(UserSessionResponseDTO::getSession)
            .filter(Objects::nonNull)
            .toList();
    var facts = sessions.stream().map(TopicDuplicateMarker::facts).toList();
    for (int i = 0; i < sessions.size(); i++) {
      if (TopicDuplicateRule.counts(facts.get(i))) {
        sessions
            .get(i)
            .setIsTopicDuplicate(TopicDuplicateRule.isNewerDuplicate(facts.get(i), facts));
      }
    }
  }

  private static TopicDuplicateRule.Facts facts(SessionDTO session) {
    var nullable = session.getConversationType();
    var type =
        nullable != null && nullable.isPresent() && nullable.get() != null
            ? ConversationType.valueOf(nullable.get().getValue())
            : null;
    return new TopicDuplicateRule.Facts(
        session.getId(),
        session.getTopic() == null ? null : session.getTopic().getId(),
        Objects.equals(session.getStatus(), SessionStatus.IN_PROGRESS.getValue()),
        type,
        parse(session.getCreateDate()));
  }

  /** createDate is the ISO format from CustomLocalDateTime.toIsoTime, in UTC. */
  private static LocalDateTime parse(String createDate) {
    if (createDate == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(createDate)
          .withOffsetSameInstant(ZoneOffset.UTC)
          .toLocalDateTime();
    } catch (DateTimeParseException unparseable) {
      return null;
    }
  }
}
