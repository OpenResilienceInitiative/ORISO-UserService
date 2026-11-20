package de.caritas.cob.userservice.api.service.statistics.settings;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * How often the admin dashboard of a role gets a new stand. A stand belongs to a fixed cutoff,
 * 06:00 in Europe/Berlin: every day, or every Monday. Until the next cutoff the numbers do not
 * change. It holds full German calendar days only, up to its {@link #referenceDay}.
 */
public enum StatisticsSnapshotInterval {
  LIVE,
  DAILY,
  WEEKLY;

  public static final String CUTOFF_ZONE = "Europe/Berlin";

  private static final LocalTime CUTOFF_TIME = LocalTime.of(6, 0);

  /** The most recent cutoff at or before {@code now}. LIVE has none: it never holds a stand. */
  public Instant latestCutoff(Instant now) {
    if (this == LIVE) {
      throw new IllegalStateException("A live dashboard has no cutoff");
    }
    var local = now.atZone(ZoneId.of(CUTOFF_ZONE));
    var cutoff = local.with(CUTOFF_TIME);
    if (this == DAILY) {
      return (cutoff.isAfter(local) ? cutoff.minusDays(1) : cutoff).toInstant();
    }
    var monday = cutoff.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    return (monday.isAfter(local) ? monday.minusWeeks(1) : monday).toInstant();
  }

  /**
   * The newest full German calendar day of the stand at {@code now}: the day before the latest
   * cutoff, so a stand never holds the day still running when it is published.
   */
  public LocalDate referenceDay(Instant now) {
    return latestCutoff(now).atZone(ZoneId.of(CUTOFF_ZONE)).toLocalDate().minusDays(1);
  }
}
