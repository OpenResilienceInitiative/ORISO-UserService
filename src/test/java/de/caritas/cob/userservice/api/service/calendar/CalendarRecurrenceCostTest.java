package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.service.calendar.CalendarRecurrenceCost.Verdict;
import java.time.Instant;
import java.time.temporal.Temporal;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.fortuna.ical4j.model.Recur;
import org.junit.jupiter.api.Test;

class CalendarRecurrenceCostTest {

  private static final Instant FROM = Instant.parse("2026-11-07T00:00:00Z");
  private static final Instant TO = Instant.parse("2027-02-12T00:00:00Z");
  private static final Instant IN_WINDOW = Instant.parse("2026-11-16T09:00:00Z");
  private static final int LIMIT = CalendarBusyParser.MAX_SLOTS;

  @Test
  void isTooFrequent_Should_RefuseRulesFinerThanHourly() {
    for (var rule :
        new String[] {
          "FREQ=SECONDLY",
          "FREQ=MINUTELY;INTERVAL=90",
          "FREQ=HOURLY;BYMINUTE=0,30",
          "FREQ=DAILY;BYHOUR=9;BYSECOND=0,30",
          "FREQ=DAILY;BYHOUR=" + range(0, 23) + ";BYMINUTE=" + range(0, 59),
        }) {
      assertThat(CalendarRecurrenceCost.isTooFrequent(recur(rule))).as(rule).isTrue();
    }
  }

  @Test
  void isTooFrequent_Should_AcceptRulesUpToHourly() {
    for (var rule :
        new String[] {
          "FREQ=HOURLY",
          "FREQ=HOURLY;BYMINUTE=15",
          "FREQ=DAILY;BYHOUR=" + range(0, 23),
          "FREQ=WEEKLY;BYDAY=MO,TU;BYHOUR=9;BYMINUTE=30;BYSECOND=0",
          "FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU"
        }) {
      assertThat(CalendarRecurrenceCost.isTooFrequent(recur(rule))).as(rule).isFalse();
    }
  }

  @Test
  void repeatsAValue_Should_FindAValueNamedTwice() {
    assertThat(CalendarRecurrenceCost.repeatsAValue(recur("FREQ=DAILY;BYHOUR=9,9"))).isTrue();
    assertThat(CalendarRecurrenceCost.repeatsAValue(recur("FREQ=WEEKLY;BYDAY=MO,TU,MO"))).isTrue();
    assertThat(CalendarRecurrenceCost.repeatsAValue(recur("FREQ=YEARLY;BYMONTH=3,3"))).isTrue();
    assertThat(CalendarRecurrenceCost.repeatsAValue(recur("FREQ=MONTHLY;BYDAY=MO,1MO,-1MO")))
        .isFalse();
  }

  @Test
  void estimates_Should_CountEachValueOnce() {
    assertThat(CalendarRecurrenceCost.perDay(recur("FREQ=DAILY;BYHOUR=9,9,12"))).isEqualTo(2);
    assertThat(CalendarRecurrenceCost.candidatesPerStep(recur("FREQ=MONTHLY;BYDAY=MO,MO")))
        .isEqualTo(5);
    assertThat(CalendarRecurrenceCost.isTooFrequent(recur("FREQ=DAILY;BYMINUTE=30,30"))).isFalse();
  }

  @Test
  void perDay_Should_CountTheOccurrencesOneDayCanHold() {
    assertThat(CalendarRecurrenceCost.perDay(recur("FREQ=HOURLY"))).isEqualTo(24);
    assertThat(CalendarRecurrenceCost.perDay(recur("FREQ=HOURLY;INTERVAL=5"))).isEqualTo(5);
    assertThat(CalendarRecurrenceCost.perDay(recur("FREQ=HOURLY;INTERVAL=30"))).isEqualTo(1);
    assertThat(CalendarRecurrenceCost.perDay(recur("FREQ=HOURLY;BYHOUR=9,10"))).isEqualTo(2);
    assertThat(CalendarRecurrenceCost.perDay(recur("FREQ=DAILY;BYHOUR=9,12,15"))).isEqualTo(3);
    assertThat(CalendarRecurrenceCost.perDay(recur("FREQ=WEEKLY;BYDAY=MO,WE"))).isEqualTo(1);
  }

  @Test
  void candidatesPerStep_Should_MultiplyWhatExpandsAStep() {
    assertThat(CalendarRecurrenceCost.candidatesPerStep(recur("FREQ=WEEKLY;BYDAY=MO,WE,FR")))
        .isEqualTo(3);
    assertThat(CalendarRecurrenceCost.candidatesPerStep(recur("FREQ=MONTHLY;BYDAY=MO")))
        .isEqualTo(5);
    assertThat(CalendarRecurrenceCost.candidatesPerStep(recur("FREQ=MONTHLY;BYDAY=1MO,-1FR")))
        .isEqualTo(2);
    assertThat(CalendarRecurrenceCost.candidatesPerStep(recur("FREQ=YEARLY;BYDAY=MO")))
        .isEqualTo(53);
    assertThat(CalendarRecurrenceCost.candidatesPerStep(recur("FREQ=YEARLY;BYMONTH=1,7;BYDAY=MO")))
        .isEqualTo(10);
    assertThat(
            CalendarRecurrenceCost.candidatesPerStep(
                recur("FREQ=DAILY;BYMONTH=1,2;BYDAY=MO;BYHOUR=9,17")))
        .isEqualTo(2);
    assertThat(
            CalendarRecurrenceCost.candidatesPerStep(
                recur(
                    "FREQ=YEARLY;BYMONTH="
                        + range(1, 12)
                        + ";BYMONTHDAY="
                        + range(1, 31)
                        + ";BYHOUR="
                        + range(0, 23))))
        .isEqualTo(12 * 31 * 24);
  }

  @Test
  void judge_Should_ExpandAnOrdinaryRule() {
    assertThat(judge("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", IN_WINDOW)).isEqualTo(Verdict.EXPAND);
    assertThat(judge("FREQ=DAILY", Instant.parse("1990-01-01T09:00:00Z")))
        .isEqualTo(Verdict.EXPAND);
    assertThat(judge("FREQ=YEARLY;COUNT=500", Instant.parse("1950-03-01T09:00:00Z")))
        .isEqualTo(Verdict.EXPAND);
  }

  @Test
  void judge_Should_BoundAnHourlyRuleByItsCountOrEnd() {
    assertThat(judge("FREQ=HOURLY;COUNT=8", IN_WINDOW)).isEqualTo(Verdict.EXPAND);
    assertThat(judge("FREQ=DAILY;BYHOUR=" + range(0, 23) + ";UNTIL=20261130T000000Z", FROM))
        .isEqualTo(Verdict.EXPAND);
  }

  @Test
  void judge_Should_FindNothingToExpand_When_TheRuleEndsBeforeOrStartsAfterTheWindow() {
    var rule = recur("FREQ=HOURLY;UNTIL=19100101T000000Z");
    assertThat(
            CalendarRecurrenceCost.judge(
                rule,
                Instant.parse("1900-01-01T00:00:00Z"),
                Instant.parse("1910-01-01T00:00:00Z"),
                FROM,
                TO,
                LIMIT))
        .isEqualTo(Verdict.NONE);
    assertThat(judge("FREQ=DAILY", Instant.parse("2027-06-01T09:00:00Z"))).isEqualTo(Verdict.NONE);
  }

  @Test
  void judge_Should_Refuse_When_TheRuleRepeatsTooOftenOrCostsTooMuchToWalk() {
    // finer than hourly
    assertThat(judge("FREQ=DAILY;BYHOUR=" + range(0, 23) + ";BYMINUTE=" + range(0, 59), FROM))
        .isEqualTo(Verdict.REFUSED);
    // a year of hours built for every step
    assertThat(
            judge(
                "FREQ=YEARLY;BYMONTH="
                    + range(1, 12)
                    + ";BYMONTHDAY="
                    + range(1, 31)
                    + ";BYHOUR="
                    + range(9, 22),
                FROM))
        .isEqualTo(Verdict.REFUSED);
    // more than a million steps from its start
    assertThat(judge("FREQ=HOURLY;BYHOUR=9", Instant.parse("1900-01-01T09:00:00Z")))
        .isEqualTo(Verdict.REFUSED);
    // a COUNT makes ical4j keep every date since the start
    assertThat(judge("FREQ=HOURLY;COUNT=100000000", Instant.parse("1970-01-01T00:00:00Z")))
        .isEqualTo(Verdict.REFUSED);
    assertThat(judge("FREQ=DAILY;BYHOUR=9,21;COUNT=0", Instant.parse("1970-01-01T00:00:00Z")))
        .isEqualTo(Verdict.REFUSED);
    // a value named twice: ical4j builds a date for each copy
    assertThat(judge("FREQ=DAILY;BYHOUR=9," + "9,".repeat(200) + "9", IN_WINDOW))
        .isEqualTo(Verdict.REFUSED);
  }

  @Test
  void judge_Should_Refuse_When_StepsTimesDatesPerStepExceedTheWorkBudget() {
    // 12 x 26 x 16 = 4992 dates per step, 16 a day: under the step, per-step and per-day bounds.
    var rule =
        "FREQ=YEARLY;BYMONTH="
            + range(1, 12)
            + ";BYMONTHDAY="
            + range(1, 26)
            + ";BYHOUR="
            + range(0, 15);
    assertThat(CalendarRecurrenceCost.candidatesPerStep(recur(rule)))
        .isEqualTo(4992)
        .isLessThan(CalendarRecurrenceCost.MAX_CANDIDATES_PER_STEP);
    var since1500 = Instant.parse("1500-01-01T00:00:00Z");
    assertThat(CalendarRecurrenceCost.steps(recur(rule), since1500, TO))
        .isLessThan(CalendarRecurrenceCost.MAX_STEPS);
    assertThat(
            CalendarRecurrenceCost.steps(recur(rule), since1500, TO)
                * CalendarRecurrenceCost.candidatesPerStep(recur(rule)))
        .isGreaterThan(CalendarRecurrenceCost.MAX_WORK);

    assertThat(judge(rule, since1500)).isEqualTo(Verdict.REFUSED);
    // The same rule started in 2000 stays within the budget.
    assertThat(judge(rule, Instant.parse("2000-01-01T00:00:00Z"))).isEqualTo(Verdict.EXPAND);
  }

  @Test
  void judge_Should_Refuse_When_TheRuleUsesAPartItsFrequencyForbids() {
    // The estimate of a MONTHLY step ignores BYWEEKNO and BYYEARDAY, but ical4j applies them.
    assertThat(judge("FREQ=MONTHLY;BYWEEKNO=" + range(1, 53), FROM)).isEqualTo(Verdict.REFUSED);
    assertThat(judge("FREQ=WEEKLY;BYWEEKNO=47", FROM)).isEqualTo(Verdict.REFUSED);
    assertThat(judge("FREQ=MONTHLY;BYYEARDAY=" + range(1, 366), FROM)).isEqualTo(Verdict.REFUSED);
    assertThat(judge("FREQ=DAILY;BYYEARDAY=320", FROM)).isEqualTo(Verdict.REFUSED);
    // Both stay allowed on a YEARLY rule.
    assertThat(judge("FREQ=YEARLY;BYWEEKNO=47;BYDAY=MO", FROM)).isEqualTo(Verdict.EXPAND);
    assertThat(judge("FREQ=YEARLY;BYYEARDAY=320", FROM)).isEqualTo(Verdict.EXPAND);
  }

  @Test
  void judge_Should_ReportTooMany_When_TheWindowAloneHoldsMoreThanTheLimit() {
    assertThat(judge("FREQ=HOURLY", FROM)).isEqualTo(Verdict.TOO_MANY);
    assertThat(judge("FREQ=DAILY;BYHOUR=" + range(0, 23), FROM)).isEqualTo(Verdict.TOO_MANY);
  }

  @Test
  void timeZoneWork_Should_CoverFiveYearsPastTheStart_OfAnObservanceAfterTheHorizon() {
    // ical4j builds an observance that starts after today up to five years past its start.
    var horizon = Instant.parse("2037-11-01T00:00:00Z");
    var start = Instant.parse("2090-01-01T00:00:00Z");

    assertThat(
            CalendarRecurrenceCost.timeZoneWork(
                recur("FREQ=DAILY;BYHOUR=" + range(0, 23)), start, horizon))
        .isGreaterThanOrEqualTo(5 * 365 * 24L);
    assertThat(CalendarRecurrenceCost.timeZoneWork(recur("FREQ=YEARLY"), start, horizon))
        .isGreaterThanOrEqualTo(5L)
        .isLessThan(10L);
  }

  private static Verdict judge(String rule, Instant start) {
    var recur = recur(rule);
    var until = recur.getUntil() == null ? null : Instant.from(recur.getUntil());
    return CalendarRecurrenceCost.judge(recur, start, until, FROM, TO, LIMIT);
  }

  private static Recur<Temporal> recur(String rule) {
    return new Recur<>(rule);
  }

  private static String range(int first, int last) {
    return IntStream.rangeClosed(first, last)
        .mapToObj(String::valueOf)
        .collect(Collectors.joining(","));
  }
}
