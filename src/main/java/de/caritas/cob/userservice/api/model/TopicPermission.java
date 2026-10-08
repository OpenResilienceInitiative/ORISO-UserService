package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import java.util.Locale;

/** How far a counsellor may extend their own topics. */
public enum TopicPermission {

  /** Only the department(s) the invite assigned. */
  NONE,

  /** Only among the agency's existing departments. */
  SELECT_EXISTING,

  /** Also further topics of the Träger (the wizard's "+"). */
  CREATE;

  /** The CSV import sends true (= CREATE) or false (= NONE); null when no value was sent. */
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
