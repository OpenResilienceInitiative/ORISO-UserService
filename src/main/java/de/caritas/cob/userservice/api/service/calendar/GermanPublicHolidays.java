package de.caritas.cob.userservice.api.service.calendar;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The German public holidays per Bundesland that the counsellor calendar treats as days off once a
 * counsellor names a region (contract v1.2). The same rules as the Frontend's {@code
 * calendarHolidays.ts}: only holidays that are statutory in a whole Land count; local ones (parts
 * of Saxony, Thuringia, Bavaria, Augsburg) and one-off ones are left out. Self-booking needs them
 * on the server, so that an advice seeker is never offered a day the counsellor's calendar shows as
 * off.
 */
final class GermanPublicHolidays {

  private static final Set<String> ALL = null;

  private record Rule(
      BiFunction<Integer, LocalDate, LocalDate> date,
      Set<String> regions,
      Map<String, Integer> since) {

    boolean appliesTo(int year, String region) {
      if (regions != null && !regions.contains(region)) {
        return false;
      }
      var first = since.get(region);
      return first == null || year >= first;
    }
  }

  private static final Set<String> REGIONS =
      Set.of(
          "BW", "BY", "BE", "BB", "HB", "HH", "HE", "MV", "NI", "NW", "RP", "SL", "SN", "ST", "SH",
          "TH");

  private static final List<Rule> RULES =
      List.of(
          fixed(1, 1, ALL),
          fixed(1, 6, Set.of("BW", "BY", "ST")),
          new Rule(
              (y, e) -> LocalDate.of(y, 3, 8), Set.of("BE", "MV"), Map.of("BE", 2019, "MV", 2023)),
          easter(-2, ALL),
          easter(0, Set.of("BB", "HE")),
          easter(1, ALL),
          fixed(5, 1, ALL),
          easter(39, ALL),
          easter(49, Set.of("BB", "HE")),
          easter(50, ALL),
          easter(60, Set.of("BW", "BY", "HE", "NW", "RP", "SL")),
          fixed(8, 15, Set.of("SL")),
          new Rule((y, e) -> LocalDate.of(y, 9, 20), Set.of("TH"), Map.of("TH", 2019)),
          fixed(10, 3, ALL),
          new Rule(
              (y, e) -> LocalDate.of(y, 10, 31),
              Set.of("BB", "HB", "HH", "MV", "NI", "SN", "ST", "SH", "TH"),
              Map.of("HB", 2018, "HH", 2018, "NI", 2018, "SH", 2018)),
          fixed(11, 1, Set.of("BW", "BY", "NW", "RP", "SL")),
          new Rule((y, e) -> repentanceDay(y), Set.of("SN"), Map.of()),
          fixed(12, 25, ALL),
          fixed(12, 26, ALL));

  private GermanPublicHolidays() {}

  /** Whether {@code date} is a public holiday in {@code region}; no or an unknown region: never. */
  static boolean isHoliday(LocalDate date, String region) {
    if (date == null || region == null || !REGIONS.contains(region)) {
      return false;
    }
    var year = date.getYear();
    var easter = easterSunday(year);
    for (var rule : RULES) {
      if (rule.appliesTo(year, region) && rule.date().apply(year, easter).equals(date)) {
        return true;
      }
    }
    return false;
  }

  /** Easter Sunday of a Gregorian year (anonymous Gregorian algorithm, Meeus/Jones/Butcher). */
  static LocalDate easterSunday(int year) {
    var a = year % 19;
    var b = year / 100;
    var c = year % 100;
    var d = b / 4;
    var e = b % 4;
    var f = (b + 8) / 25;
    var g = (b - f + 1) / 3;
    var h = (19 * a + b - d - g + 15) % 30;
    var i = c / 4;
    var k = c % 4;
    var l = (32 + 2 * e + 2 * i - h - k) % 7;
    var m = (a + 11 * h + 22 * l) / 451;
    var month = (h + l - 7 * m + 114) / 31;
    var day = ((h + l - 7 * m + 114) % 31) + 1;
    return LocalDate.of(year, month, day);
  }

  /** Buß- und Bettag: the Wednesday before 23 November. */
  private static LocalDate repentanceDay(int year) {
    var nov22 = LocalDate.of(year, 11, 22);
    // java.time: Monday = 1 .. Sunday = 7; Wednesday = 3.
    return nov22.minusDays((nov22.getDayOfWeek().getValue() - 3 + 7) % 7);
  }

  private static Rule fixed(int month, int day, Set<String> regions) {
    return new Rule((y, e) -> LocalDate.of(y, month, day), regions, Map.of());
  }

  private static Rule easter(int days, Set<String> regions) {
    return new Rule((y, e) -> e.plus(days, ChronoUnit.DAYS), regions, Map.of());
  }
}
