package de.caritas.cob.userservice.api.service.furthercounselling;

import java.util.Arrays;

/**
 * FE#1876 "Themendopplung": what happens when an asker holds two accepted conversations on the same
 * topic. Only {@link #INFORM_EXISTING_COUNSELLOR_ONCE} is implemented; the others are reserved
 * (FE#1878) and behave like the default here.
 */
public enum TopicDuplicateMode {
  INFORM_EXISTING_COUNSELLOR_ONCE,
  INFORM_BOTH_ONCE,
  INFORM_BOTH_EVERY_MESSAGE,
  FORBID;

  public static final TopicDuplicateMode DEFAULT = INFORM_EXISTING_COUNSELLOR_ONCE;

  /** Absent, blank or unknown values fall back to {@link #DEFAULT}. */
  public static TopicDuplicateMode parse(String value) {
    if (value == null) {
      return DEFAULT;
    }
    return Arrays.stream(values())
        .filter(mode -> mode.name().equals(value.trim()))
        .findFirst()
        .orElse(DEFAULT);
  }
}
