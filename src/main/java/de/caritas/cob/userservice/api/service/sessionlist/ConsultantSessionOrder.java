package de.caritas.cob.userservice.api.service.sessionlist;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;

/**
 * The order of a counsellor's conversations: the latest activity on top.
 *
 * <p>{@code latestMessage} is the date of the first enquiry message. A conversation that never had
 * one (somebody who came through a direct invitation, ORISO-Frontend#2542) carries none; it used to
 * sort to the very end and fell off the first page of the list, so the counsellor did not see the
 * person who had just arrived. Such a conversation now counts from the moment it was created.
 */
public final class ConsultantSessionOrder {

  private static final long NEVER = Long.MIN_VALUE;

  /** Latest activity first; conversations without any date stay last. */
  public static final Comparator<ConsultantSessionResponseDTO> LATEST_ACTIVITY_FIRST =
      Comparator.comparingLong(ConsultantSessionOrder::latestActivityMillis).reversed();

  private ConsultantSessionOrder() {}

  static long latestActivityMillis(ConsultantSessionResponseDTO conversation) {
    if (conversation.getLatestMessage() != null) {
      return conversation.getLatestMessage().getTime();
    }
    var session = conversation.getSession();
    if (session == null || session.getCreateDate() == null) {
      return NEVER;
    }
    try {
      return OffsetDateTime.parse(session.getCreateDate()).toInstant().toEpochMilli();
    } catch (DateTimeParseException unreadable) {
      return NEVER;
    }
  }
}
