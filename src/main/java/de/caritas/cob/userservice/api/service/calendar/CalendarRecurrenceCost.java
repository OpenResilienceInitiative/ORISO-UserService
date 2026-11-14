package de.caritas.cob.userservice.api.service.calendar;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.WeekDay;
import net.fortuna.ical4j.transform.recurrence.Frequency;

/**
 * What expanding one repeat rule (RRULE or EXRULE) of an imported calendar would cost, judged from
 * the rule alone before ical4j expands it (ADR-032, ORISO-UserService#1645). The document comes
 * from an address the counsellor typed in, so its author may be hostile, and ical4j is generous: it
 * builds every date of one step of a rule at once (a whole year of a YEARLY rule), walks the steps
 * one by one from the start of the rule, and keeps every date since that start for a rule with
 * COUNT. Each of these stays small here whatever the document says; the parser in addition caps
 * what ical4j returns.
 */
final class CalendarRecurrenceCost {

  /** More than one occurrence an hour, on average over a day, is no appointment. */
  static final int MAX_PER_DAY = 24;

  /** Dates ical4j may build for one step of a rule. */
  static final long MAX_CANDIDATES_PER_STEP = 5_000;

  /** Steps from the start of a rule to the end of the window, which ical4j walks one by one. */
  static final long MAX_STEPS = 1_000_000;

  /** Dates a rule with COUNT keeps from its start on, most of them before the window. */
  static final long MAX_KEPT = 20_000;

  /**
   * Steps times dates per step: the whole work of walking a rule. Each bound above alone still lets
   * a rule just under both multiply them.
   */
  static final long MAX_WORK = 2_000_000;

  private static final long DAY = Duration.ofDays(1).toSeconds();

  /** What to do with a rule. */
  enum Verdict {
    /** Within every bound: expand it. */
    EXPAND,
    /** Ends before the window or starts after it: there is nothing to expand. */
    NONE,
    /**
     * Repeats more often than hourly, repeats a value, or walking it costs too much: the event is
     * not read.
     */
    REFUSED,
    /** Would yield more occurrences inside the window than the caller accepts. */
    TOO_MANY
  }

  private CalendarRecurrenceCost() {}

  /**
   * @param start the start of the event, which seeds the rule
   * @param until the rule's UNTIL as an instant, or {@code null}
   * @param windowStart start of the span the rule is expanded in
   * @param windowEnd end of that span
   * @param limit the most occurrences inside the span the caller accepts
   */
  static Verdict judge(
      Recur<?> rule,
      Instant start,
      Instant until,
      Instant windowStart,
      Instant windowEnd,
      long limit) {
    if (repeatsAValue(rule)
        || usesAPartItsFrequencyForbids(rule)
        || isTooFrequent(rule)
        || candidatesPerStep(rule) > MAX_CANDIDATES_PER_STEP) {
      return Verdict.REFUSED;
    }
    // An UNTIL given as a whole day lasts to the end of that day; one day of slack covers it.
    var end = windowEnd;
    if (until != null && until.plusSeconds(DAY).isBefore(windowEnd)) {
      end = until.plusSeconds(DAY);
    }
    var begin = start.isAfter(windowStart) ? start : windowStart;
    if (end.isBefore(begin)) {
      return Verdict.NONE;
    }
    var steps = steps(rule, start, end);
    if (steps > MAX_STEPS || times(steps, candidatesPerStep(rule)) > MAX_WORK) {
      return Verdict.REFUSED;
    }
    var count = rule.getCount();
    if (hasCount(rule)) {
      // ical4j walks a rule with COUNT from its start and keeps every date it passes.
      var kept = occurrences(rule, start, end);
      if ((count > 0 ? Math.min(count, kept) : kept) > MAX_KEPT) {
        return Verdict.REFUSED;
      }
    }
    var inWindow = occurrences(rule, begin, end);
    if (count > 0) {
      inWindow = Math.min(inWindow, count);
    }
    return inWindow > limit ? Verdict.TOO_MANY : Verdict.EXPAND;
  }

  /**
   * A rule that repeats by the second or minute, names more than one second or minute, or yields
   * more than {@value #MAX_PER_DAY} occurrences a day is no appointment.
   */
  static boolean isTooFrequent(Recur<?> rule) {
    var frequency = rule.getFrequency();
    return frequency == null
        || frequency == Frequency.SECONDLY
        || frequency == Frequency.MINUTELY
        || distinct(rule.getSecondList()) > 1
        || distinct(rule.getMinuteList()) > 1
        || perDay(rule) > MAX_PER_DAY;
  }

  /**
   * RFC 5545 (3.3.10) allows BYWEEKNO only on a YEARLY rule and BYYEARDAY not on a DAILY, WEEKLY or
   * MONTHLY one. ical4j still applies them there, and the estimates do not count them for those
   * frequencies, so such a rule is not read at all.
   */
  static boolean usesAPartItsFrequencyForbids(Recur<?> rule) {
    var frequency = rule.getFrequency();
    if (!rule.getWeekNoList().isEmpty() && frequency != Frequency.YEARLY) {
      return true;
    }
    return !rule.getYearDayList().isEmpty()
        && (frequency == Frequency.DAILY
            || frequency == Frequency.WEEKLY
            || frequency == Frequency.MONTHLY);
  }

  /**
   * A value named twice in one BY part means nothing (RFC 5545), but ical4j builds a date for each
   * copy: the estimates count distinct values, so a rule with copies is not read at all.
   */
  static boolean repeatsAValue(Recur<?> rule) {
    for (var values :
        List.of(
            rule.getSecondList(),
            rule.getMinuteList(),
            rule.getHourList(),
            rule.getDayList(),
            rule.getMonthDayList(),
            rule.getYearDayList(),
            rule.getWeekNoList(),
            rule.getMonthList(),
            rule.getSetPosList())) {
      if (distinct(values) < values.size()) {
        return true;
      }
    }
    return false;
  }

  /** The most occurrences the rule can have on one day. */
  static long perDay(Recur<?> rule) {
    long hours = sizeOf(rule.getHourList());
    if (rule.getFrequency() == Frequency.HOURLY) {
      long every = Math.max(1, rule.getInterval());
      long stepsPerDay = (24 + every - 1) / every;
      hours = rule.getHourList().isEmpty() ? stepsPerDay : Math.min(hours, stepsPerDay);
    }
    return times(times(hours, sizeOf(rule.getMinuteList())), sizeOf(rule.getSecondList()));
  }

  /**
   * The most dates ical4j builds for one step of the rule: the parts that expand a step multiply
   * (RFC 5545, 3.3.10), the parts that only limit it count once. A weekday without a position
   * stands for every such day of the month (5) or year (53). Values are counted once each.
   */
  static long candidatesPerStep(Recur<?> rule) {
    var frequency = rule.getFrequency();
    long dates = 1;
    if (frequency == Frequency.WEEKLY) {
      dates = sizeOf(rule.getDayList());
    } else if (frequency == Frequency.MONTHLY) {
      dates = times(sizeOf(rule.getMonthDayList()), weekdays(rule.getDayList(), 5));
    } else if (frequency == Frequency.YEARLY) {
      var everyDayOf =
          !rule.getWeekNoList().isEmpty() ? 1 : !rule.getMonthList().isEmpty() ? 5 : 53;
      dates =
          times(
              times(
                  times(sizeOf(rule.getMonthList()), sizeOf(rule.getWeekNoList())),
                  times(sizeOf(rule.getYearDayList()), sizeOf(rule.getMonthDayList()))),
              weekdays(rule.getDayList(), everyDayOf));
    }
    long clock = times(sizeOf(rule.getMinuteList()), sizeOf(rule.getSecondList()));
    if (frequency != Frequency.HOURLY) {
      clock = times(clock, sizeOf(rule.getHourList()));
    }
    return times(dates, clock);
  }

  /** The most occurrences of the rule between two instants. */
  static long occurrences(Recur<?> rule, Instant from, Instant to) {
    return Math.min(
        times(perDay(rule), days(from, to)), times(steps(rule, from, to), candidatesPerStep(rule)));
  }

  /** The most steps of the rule between two instants. */
  static long steps(Recur<?> rule, Instant from, Instant to) {
    return ceilDiv(secondsBetween(from, to), stepSeconds(rule)) + 1;
  }

  /**
   * ical4j walks a rule from its start, instead of jumping to the window, as soon as the rule
   * carries a COUNT at all, whatever its value.
   */
  private static boolean hasCount(Recur<?> rule) {
    return rule.getCount() != -1 || rule.toString().contains("COUNT=");
  }

  private static long stepSeconds(Recur<?> rule) {
    long every = Math.max(1, rule.getInterval());
    var frequency = rule.getFrequency();
    long unit;
    if (frequency == Frequency.HOURLY) {
      unit = 3_600;
    } else if (frequency == Frequency.DAILY) {
      unit = DAY;
    } else if (frequency == Frequency.WEEKLY) {
      unit = 7 * DAY;
    } else if (frequency == Frequency.MONTHLY) {
      unit = 28 * DAY;
    } else if (frequency == Frequency.YEARLY) {
      unit = 365 * DAY;
    } else {
      unit = 1;
    }
    return times(every, unit);
  }

  private static long days(Instant from, Instant to) {
    return ceilDiv(secondsBetween(from, to), DAY) + 1;
  }

  private static long secondsBetween(Instant from, Instant to) {
    return to.isAfter(from) ? Duration.between(from, to).toSeconds() : 0;
  }

  private static long weekdays(List<WeekDay> days, long everyDayOf) {
    if (days.isEmpty()) {
      return 1;
    }
    var counted = new HashSet<String>();
    long sum = 0;
    for (var day : days) {
      if (counted.add(String.valueOf(day))) {
        sum += day.getOffset() != 0 ? 1 : everyDayOf;
      }
    }
    return sum;
  }

  /** At least one: an absent BY part leaves the step as it is. */
  private static long sizeOf(List<?> values) {
    return Math.max(1, distinct(values));
  }

  /** By written form, which does not depend on how a value type defines equality. */
  private static long distinct(List<?> values) {
    return values.stream().map(String::valueOf).distinct().count();
  }

  private static long ceilDiv(long dividend, long divisor) {
    return Math.ceilDiv(dividend, Math.max(1, divisor));
  }

  private static long times(long a, long b) {
    var high = Math.multiplyHigh(a, b);
    var low = a * b;
    return high != 0 || low < 0 ? Long.MAX_VALUE : low;
  }
}
