package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import java.util.Locale;

/**
 * How far a counsellor may extend their own topics (ORISO-Admin#1026, slice 6). Stored per invite
 * and per counsellor; an agency carries the default for new invites.
 */
public enum TopicPermission {

  /** Only the department(s) the invite assigned — nothing further. The default for new invites. */
  NONE,

  /** May pick further departments, but only among the agency's existing ones. */
  SELECT_EXISTING,

  /** May add further topics of the Träger (the onboarding wizard's "+"). Today's behaviour. */
  CREATE;

  /**
   * Reads the wire value: one of the three names (case-insensitive), or a plain yes/no as the CSV
   * import sends it — {@code true} is today's behaviour ({@link #CREATE}), {@code false} is {@link
   * #NONE}.
   *
   * @return the permission, or {@code null} when no value was sent
   * @throws BadRequestException for anything else
   */
  public static TopicPermission fromWire(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean flag) {
      return Boolean.TRUE.equals(flag) ? CREATE : NONE;
    }
    if (!(value instanceof String text)) {
      throw invalid(value);
    }
    String normalized = text.trim().toUpperCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      return null;
    }
    if ("TRUE".equals(normalized)) {
      return CREATE;
    }
    if ("FALSE".equals(normalized)) {
      return NONE;
    }
    try {
      return valueOf(normalized);
    } catch (IllegalArgumentException exception) {
      throw invalid(value);
    }
  }

  private static BadRequestException invalid(Object value) {
    return new BadRequestException(
        "Unknown topicPermission: "
            + value
            + " (expected NONE, SELECT_EXISTING, CREATE, true or false)");
  }
}
