package de.caritas.cob.userservice.api.service.furthercounselling;

import static java.time.temporal.ChronoUnit.SECONDS;

import de.caritas.cob.userservice.api.model.ConversationType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;

/**
 * FE#1876: the one rule for "newer same-topic duplicate". The end-duplicate endpoint (entities) and
 * the asker's list flag (DTOs) both go through it, so they cannot disagree.
 *
 * <p>Same topic means the same {@code mainTopicId}. Only accepted 1:1 conversations count; a
 * members-only chat is always an internal group chat and is excluded with the groups. Age is the
 * create date to the second, then the id: the list only carries seconds.
 */
public final class TopicDuplicateRule {

  /** The facts the rule reads from one conversation. */
  public record Facts(
      Long id, Long topicId, boolean inProgress, ConversationType type, LocalDateTime createDate) {}

  /** Older first; equal seconds fall back to the id so the order is total. */
  public static final Comparator<Facts> AGE =
      Comparator.comparing(
              (Facts facts) ->
                  facts.createDate() == null ? null : facts.createDate().truncatedTo(SECONDS),
              Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder()))
          .thenComparing(Facts::id, Comparator.nullsFirst(Comparator.<Long>naturalOrder()));

  private TopicDuplicateRule() {}

  /** A conversation between one asker and their counsellor: no group, no members-only chat. */
  public static boolean isOneToOne(ConversationType type) {
    return type == null
        || type == ConversationType.AGENCY_COUNSELLING
        || type == ConversationType.LIVE_CHAT;
  }

  /** Whether the conversation takes part at all: accepted, 1:1, with a topic. */
  public static boolean counts(Facts facts) {
    return facts.inProgress() && facts.topicId() != null && isOneToOne(facts.type());
  }

  /** Whether {@code other} is a counting conversation on the same topic, started before. */
  public static boolean isOlderSameTopic(Facts other, Facts session) {
    return counts(other)
        && !Objects.equals(other.id(), session.id())
        && Objects.equals(other.topicId(), session.topicId())
        && AGE.compare(other, session) < 0;
  }

  /** Whether the conversation counts and an older counting one shares its topic. */
  public static boolean isNewerDuplicate(Facts session, Collection<Facts> asker) {
    return counts(session) && asker.stream().anyMatch(other -> isOlderSameTopic(other, session));
  }
}
