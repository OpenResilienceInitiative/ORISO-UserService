package de.caritas.cob.userservice.api.service.statistics.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The cutoff is 06:00 in Europe/Berlin, also across the change between summer and winter time. */
class StatisticsSnapshotIntervalTest {

  private static Instant at(String utc) {
    return Instant.parse(utc);
  }

  @Test
  void referenceDay_Should_BeTheDayBeforeTheLatestCutoff() {
    assertThat(StatisticsSnapshotInterval.DAILY.referenceDay(at("2026-11-04T05:00:00Z")))
        .isEqualTo("2026-11-03");
    assertThat(StatisticsSnapshotInterval.DAILY.referenceDay(at("2026-11-04T04:59:59Z")))
        .isEqualTo("2026-11-02");
    assertThat(StatisticsSnapshotInterval.WEEKLY.referenceDay(at("2026-11-04T10:00:00Z")))
        .as("the Sunday before the Monday cutoff")
        .isEqualTo("2026-11-01");
  }

  @Test
  void daily_Should_UseTodaysCutoff_FromSixOClockBerlinTimeOn() {
    assertThat(StatisticsSnapshotInterval.DAILY.latestCutoff(at("2026-11-04T05:00:00Z")))
        .isEqualTo(at("2026-11-04T05:00:00Z"));
    assertThat(StatisticsSnapshotInterval.DAILY.latestCutoff(at("2026-11-04T22:59:59Z")))
        .isEqualTo(at("2026-11-04T05:00:00Z"));
  }

  @Test
  void daily_Should_UseYesterdaysCutoff_BeforeSixOClockBerlinTime() {
    assertThat(StatisticsSnapshotInterval.DAILY.latestCutoff(at("2026-11-04T04:59:59Z")))
        .isEqualTo(at("2026-11-03T05:00:00Z"));
  }

  @Test
  void daily_Should_FollowBerlinSummerTime() {
    assertThat(StatisticsSnapshotInterval.DAILY.latestCutoff(at("2026-07-11T10:00:00Z")))
        .isEqualTo(at("2026-07-11T04:00:00Z"));
  }

  @Test
  void weekly_Should_UseThisMondaysCutoff_FromMondaySixOClockOn() {
    assertThat(StatisticsSnapshotInterval.WEEKLY.latestCutoff(at("2026-11-02T05:00:00Z")))
        .isEqualTo(at("2026-11-02T05:00:00Z"));
    assertThat(StatisticsSnapshotInterval.WEEKLY.latestCutoff(at("2026-11-08T22:00:00Z")))
        .isEqualTo(at("2026-11-02T05:00:00Z"));
  }

  @Test
  void weekly_Should_UseLastMondaysCutoff_OnMondayBeforeSixOClock() {
    assertThat(StatisticsSnapshotInterval.WEEKLY.latestCutoff(at("2026-11-09T04:59:59Z")))
        .isEqualTo(at("2026-11-02T05:00:00Z"));
  }

  @Test
  void weekly_Should_KeepItsLocalTime_AcrossTheSwitchToWinterTime() {
    // Sunday 2026-10-25 ends summer time: the Monday before is still 04:00 UTC.
    assertThat(StatisticsSnapshotInterval.WEEKLY.latestCutoff(at("2026-10-25T12:00:00Z")))
        .isEqualTo(at("2026-10-19T04:00:00Z"));
    assertThat(StatisticsSnapshotInterval.WEEKLY.latestCutoff(at("2026-10-26T06:00:00Z")))
        .isEqualTo(at("2026-10-26T05:00:00Z"));
  }

  @Test
  void live_Should_HaveNoCutoff() {
    assertThatThrownBy(() -> StatisticsSnapshotInterval.LIVE.latestCutoff(Instant.EPOCH))
        .isInstanceOf(IllegalStateException.class);
  }
}
