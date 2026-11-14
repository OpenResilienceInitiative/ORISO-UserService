package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import java.time.LocalDateTime;

/**
 * The times of a direct invitation with an appointment (ORISO-Frontend#2252), in UTC: the link is
 * valid until {@code validityAfterEndMinutes} after the end (0 to 48 hours, default 24 hours), and
 * the call waiting room opens 15 minutes before the start. The chat works at once.
 */
public record DirectInvitationSchedule(
    LocalDateTime start, LocalDateTime end, int validityAfterEndMinutes) {

  public static final int DEFAULT_VALIDITY_MINUTES = 1440;
  public static final int MAX_VALIDITY_MINUTES = 2880;
  public static final int CALL_OPENS_MINUTES_BEFORE_START = 15;

  public DirectInvitationSchedule {
    if (start == null || end == null) {
      throw new BadRequestException("An appointment needs a start and an end");
    }
    if (!end.isAfter(start)) {
      throw new BadRequestException("The end of an appointment must be after its start.");
    }
    requireValidity(validityAfterEndMinutes);
  }

  public static DirectInvitationSchedule of(
      LocalDateTime start, LocalDateTime end, Integer validityAfterEndMinutes) {
    return new DirectInvitationSchedule(
        start,
        end,
        validityAfterEndMinutes == null ? DEFAULT_VALIDITY_MINUTES : validityAfterEndMinutes);
  }

  public static void requireValidity(int minutes) {
    if (minutes < 0 || minutes > MAX_VALIDITY_MINUTES) {
      throw new BadRequestException(
          "validityAfterEndMinutes must be between 0 and " + MAX_VALIDITY_MINUTES);
    }
  }

  public LocalDateTime expiresAt() {
    return end.plusMinutes(validityAfterEndMinutes);
  }

  public LocalDateTime callOpensAt() {
    return start.minusMinutes(CALL_OPENS_MINUTES_BEFORE_START);
  }

  public boolean callOpen(LocalDateTime now) {
    return !now.isBefore(callOpensAt());
  }

  /** "Neuen Link senden": the validity counts from the end, or from now once the end is past. */
  public LocalDateTime renewedExpiry(LocalDateTime now) {
    return (now.isAfter(end) ? now : end).plusMinutes(validityAfterEndMinutes);
  }
}
