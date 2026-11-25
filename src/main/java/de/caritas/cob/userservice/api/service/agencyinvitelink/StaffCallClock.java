package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * ORISO-Frontend#2356: a counsellor's knock through a direct invitation's link counts for one call.
 * Knocks carry {@code LocalDateTime.now()}, calls carry epoch milliseconds; this is the one place
 * that compares the two, in the zone the knocks were written in.
 */
final class StaffCallClock {

  private StaffCallClock() {}

  /**
   * When the call was placed. A takeover (FE#2209) resets it, so knocks on the call room that was
   * taken over are stale, like its guest seats. Cut to the second: knock times may be stored
   * without fractions, and a knock in the call's first second must not read as older than the call.
   */
  static LocalDateTime startOf(MatrixCallBinding binding) {
    return LocalDateTime.ofInstant(
            Instant.ofEpochMilli(binding.getInvitedAt()), ZoneId.systemDefault())
        .truncatedTo(ChronoUnit.SECONDS);
  }
}
