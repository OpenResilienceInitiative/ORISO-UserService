package de.caritas.cob.userservice.api.service.calendar;

import static de.caritas.cob.userservice.api.service.calendar.CalendarImportError.TOO_LARGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.fortuna.ical4j.util.Configurator;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class CalendarBusyParserTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static final Instant FROM = Instant.parse("2026-11-07T00:00:00Z");
  private static final Instant TO = Instant.parse("2027-02-12T00:00:00Z");

  private final CalendarBusyParser parser = new CalendarBusyParser();

  @Test
  void parse_Should_KeepStartAndEndOfPlainEvents() {
    var slots =
        parse(
            event(
                "UID:utc", "DTSTART:20261116T090000Z", "DTEND:20261116T100000Z", "SUMMARY:Dentist"),
            event(
                "UID:zoned",
                "DTSTART;TZID=Europe/Berlin:20261117T140000",
                "DTEND;TZID=Europe/Berlin:20261117T153000",
                "LOCATION:Somewhere private",
                "DESCRIPTION:Something private"),
            event("UID:floating", "DTSTART:20261118T080000", "DTEND:20261118T083000"),
            event("UID:duration", "DTSTART:20261119T100000Z", "DURATION:PT45M"));

    assertThat(slots)
        .containsExactly(
            slot("2026-11-16T09:00:00Z", "2026-11-16T10:00:00Z"),
            slot("2026-11-17T13:00:00Z", "2026-11-17T14:30:00Z"),
            slot("2026-11-18T07:00:00Z", "2026-11-18T07:30:00Z"),
            slot("2026-11-19T10:00:00Z", "2026-11-19T10:45:00Z"));
  }

  @Test
  void parse_Should_ReadWholeDayEventsInThePlatformZone() {
    var slots =
        parse(
            event("UID:day", "DTSTART;VALUE=DATE:20261120", "DTEND;VALUE=DATE:20261121"),
            event("UID:days", "DTSTART;VALUE=DATE:20261123", "DTEND;VALUE=DATE:20261126"));

    assertThat(slots)
        .containsExactly(
            slot("2026-11-19T23:00:00Z", "2026-11-20T23:00:00Z"),
            slot("2026-11-22T23:00:00Z", "2026-11-25T23:00:00Z"));
  }

  @Test
  void parse_Should_LetAWholeDayWithoutAnEndLastOneDay() {
    // RFC 5545 3.6.1: a DATE start without DTEND or DURATION lasts one day.
    var slots =
        parse(
            event("UID:day", "DTSTART;VALUE=DATE:20261120"),
            event("UID:weekly", "DTSTART;VALUE=DATE:20261123", "RRULE:FREQ=WEEKLY;COUNT=2"));

    assertThat(slots)
        .containsExactly(
            slot("2026-11-19T23:00:00Z", "2026-11-20T23:00:00Z"),
            slot("2026-11-22T23:00:00Z", "2026-11-23T23:00:00Z"),
            slot("2026-11-29T23:00:00Z", "2026-11-30T23:00:00Z"));
  }

  @Test
  void parse_Should_ExpandARepeatInsideTheWindow_WithoutItsExceptions() {
    var slots =
        parse(
            event(
                "UID:weekly",
                "DTSTART;TZID=Europe/Berlin:20261102T090000",
                "DTEND;TZID=Europe/Berlin:20261102T100000",
                "RRULE:FREQ=WEEKLY;BYDAY=MO;UNTIL=20261207T235959Z",
                "EXDATE;TZID=Europe/Berlin:20261123T090000"));

    // 2 November lies before the window; 23 November is excluded.
    assertThat(slots)
        .containsExactly(
            slot("2026-11-09T08:00:00Z", "2026-11-09T09:00:00Z"),
            slot("2026-11-16T08:00:00Z", "2026-11-16T09:00:00Z"),
            slot("2026-11-30T08:00:00Z", "2026-11-30T09:00:00Z"),
            slot("2026-12-07T08:00:00Z", "2026-12-07T09:00:00Z"));
  }

  @Test
  void parse_Should_KeepTheClockTimeOfARepeatAcrossAClockChange() {
    var slots =
        parser.parse(
            calendar(
                event(
                    "UID:dst",
                    "DTSTART;TZID=Europe/Berlin:20270326T090000",
                    "DTEND;TZID=Europe/Berlin:20270326T100000",
                    "RRULE:FREQ=DAILY;COUNT=4")),
            Instant.parse("2027-03-20T00:00:00Z"),
            Instant.parse("2027-04-20T00:00:00Z"),
            BERLIN);

    assertThat(slots)
        .extracting(BusySlot::start)
        .containsExactly(
            Instant.parse("2027-03-26T08:00:00Z"),
            Instant.parse("2027-03-27T08:00:00Z"),
            Instant.parse("2027-03-28T07:00:00Z"),
            Instant.parse("2027-03-29T07:00:00Z"));
  }

  @Test
  void parse_Should_UseTheMovedOccurrence_NotTheOneItReplaces() {
    var slots =
        parse(
            event(
                "UID:series",
                "DTSTART:20261116T090000Z",
                "DTEND:20261116T100000Z",
                "RRULE:FREQ=DAILY;COUNT=3"),
            event(
                "UID:series",
                "RECURRENCE-ID:20261117T090000Z",
                "DTSTART:20261117T150000Z",
                "DTEND:20261117T160000Z"));

    assertThat(slots)
        .containsExactly(
            slot("2026-11-16T09:00:00Z", "2026-11-16T10:00:00Z"),
            slot("2026-11-17T15:00:00Z", "2026-11-17T16:00:00Z"),
            slot("2026-11-18T09:00:00Z", "2026-11-18T10:00:00Z"));
  }

  @Test
  void parse_Should_MoveEveryLaterOccurrence_WithAnOverrideForThisAndFuture() {
    var slots =
        parse(
            event(
                "UID:series",
                "DTSTART:20261116T090000Z",
                "DTEND:20261116T100000Z",
                "RRULE:FREQ=DAILY;COUNT=5",
                "EXDATE:20261119T090000Z"),
            event(
                "UID:series",
                "RECURRENCE-ID;RANGE=THISANDFUTURE:20261117T090000Z",
                "DTSTART:20261117T110000Z",
                "DTEND:20261117T113000Z"));

    // From the 17th on two hours later and half an hour long; the 19th stays excluded by the time
    // the rule gave it.
    assertThat(slots)
        .containsExactly(
            slot("2026-11-16T09:00:00Z", "2026-11-16T10:00:00Z"),
            slot("2026-11-17T11:00:00Z", "2026-11-17T11:30:00Z"),
            slot("2026-11-18T11:00:00Z", "2026-11-18T11:30:00Z"),
            slot("2026-11-20T11:00:00Z", "2026-11-20T11:30:00Z"));
  }

  @Test
  void parse_Should_LetAPeriodReplaceTheOccurrenceItStarts() {
    var slots =
        parse(
            event(
                "UID:series",
                "DTSTART:20261116T090000Z",
                "DTEND:20261116T100000Z",
                "RRULE:FREQ=DAILY;COUNT=2",
                "RDATE;VALUE=PERIOD:20261117T090000Z/20261117T093000Z"));

    assertThat(slots)
        .containsExactly(
            slot("2026-11-16T09:00:00Z", "2026-11-16T10:00:00Z"),
            slot("2026-11-17T09:00:00Z", "2026-11-17T09:30:00Z"));
  }

  @Test
  void parse_Should_CountTheEventsItSkips_And_LogNeitherTheirContentNorTheirUid() {
    var logger = (Logger) LoggerFactory.getLogger(CalendarBusyParser.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var slots =
          parse(
              event(
                  "UID:private-uid-1",
                  "SUMMARY:Private matter",
                  "DTSTART:20261116T140000Z",
                  "DTEND:20261116T140001Z",
                  "RRULE:FREQ=SECONDLY"),
              event("UID:private-uid-2", "DTSTART:20261116T150000Z", "DTEND:20261116T160000Z"));

      assertThat(slots).containsExactly(slot("2026-11-16T15:00:00Z", "2026-11-16T16:00:00Z"));
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.get(0).getFormattedMessage())
          .contains("skipped 1 of 2 events")
          .contains("UnreadableEvent")
          .doesNotContain("private-uid", "Private matter", "20261116");

      appender.list.clear();
      parse(event("UID:fine", "DTSTART:20261116T150000Z", "DTEND:20261116T160000Z"));
      assertThat(appender.list).as("nothing skipped, nothing logged").isEmpty();
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void parse_Should_RefuseATimeZoneWhoseRulesCostTooMuch_BeforeIcal4jBuildsIt() {
    for (var rule :
        List.of(
            "RRULE:FREQ=SECONDLY",
            "RRULE:FREQ=SEC\r\n ONDLY",
            "RRULE:FREQ=MINUTELY",
            "RRULE:FREQ=HOURLY",
            "RRULE:FREQ=DAILY;BYHOUR=" + range(0, 23),
            "rrule:FREQ=HOURLY",
            // ical4j walks an exclusion rule as well.
            "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=10\r\nEXRULE:FREQ=SECONDLY")) {
      var document =
          calendar(
              timeZone("Synthetic/Hostile", "19700101T000000", rule),
              event(
                  "UID:zoned",
                  "DTSTART;TZID=Synthetic/Hostile:20261116T090000",
                  "DTEND;TZID=Synthetic/Hostile:20261116T100000"));

      var error =
          assertTimeoutPreemptively(
              Duration.ofSeconds(5), () -> errorOf(() -> parser.parse(document, FROM, TO, BERLIN)));

      assertThat(error).as(rule).isEqualTo(TOO_LARGE);
    }
  }

  @Test
  void parse_Should_ReadAnEventInATimeZoneTheDocumentDefines() {
    // As Outlook writes its zones: every rule from 1601 on.
    var slots =
        parse(
            timeZone(
                "W. Europe Standard Time",
                "16010101T030000",
                "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=10"),
            event(
                "UID:zoned",
                "DTSTART;TZID=W. Europe Standard Time:20261116T090000",
                "DTEND;TZID=W. Europe Standard Time:20261116T100000"));

    assertThat(slots).containsExactly(slot("2026-11-16T08:00:00Z", "2026-11-16T09:00:00Z"));
  }

  @Test
  void parse_Should_ReadAZoneThatStartsInYearOne() {
    // No floor on the year: the estimate counts every step from the start, however early.
    var slots =
        parse(
            timeZone(
                "Synthetic/Always", "00010101T030000", "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=10"),
            event(
                "UID:zoned",
                "DTSTART;TZID=Synthetic/Always:20261116T090000",
                "DTEND;TZID=Synthetic/Always:20261116T100000"));

    assertThat(slots).containsExactly(slot("2026-11-16T08:00:00Z", "2026-11-16T09:00:00Z"));
  }

  @Test
  void parse_Should_RefuseAZoneRule_ThatIcal4jCouldReadDifferentlyFromTheEstimate() {
    for (var rule :
        List.of(
            // A colon inside a quoted parameter value does not end the parameters.
            "RRULE;X-P=\"a:FREQ=YEARLY;X-Q\":FREQ=SECONDLY",
            "RRULE;X-P=\"a:FREQ=SECONDLY",
            // A component inside an observance.
            "RRULE:FREQ=SECONDLY\r\nBEGIN:X-NESTED\r\nEND:X-NESTED",
            // A second start: ical4j walks the rule from the first one.
            "DTSTART:20360101T000000\r\nRRULE:FREQ=HOURLY")) {
      var document =
          calendar(
              timeZone("Synthetic/Hostile", "19700101T000000", rule),
              event(
                  "UID:zoned",
                  "DTSTART;TZID=Synthetic/Hostile:20261116T090000",
                  "DTEND;TZID=Synthetic/Hostile:20261116T100000"));

      var error =
          assertTimeoutPreemptively(
              Duration.ofSeconds(5), () -> errorOf(() -> parser.parse(document, FROM, TO, BERLIN)));

      assertThat(error).as(rule).isEqualTo(CalendarImportError.NOT_ICS);
    }
  }

  @Test
  void parse_Should_SkipFreeCancelledAndEmptyEvents_And_ThoseOutsideTheWindow() {
    var slots =
        parse(
            event(
                "UID:free",
                "DTSTART:20261116T090000Z",
                "DTEND:20261116T100000Z",
                "TRANSP:TRANSPARENT"),
            event(
                "UID:cancelled",
                "DTSTART:20261116T110000Z",
                "DTEND:20261116T120000Z",
                "STATUS:CANCELLED"),
            event("UID:empty", "DTSTART:20261116T130000Z", "DTEND:20261116T130000Z"),
            event("UID:before", "DTSTART:20261101T090000Z", "DTEND:20261101T100000Z"),
            event("UID:after", "DTSTART:20270301T090000Z", "DTEND:20270301T100000Z"),
            event(
                "UID:every-second",
                "DTSTART:20261116T140000Z",
                "DTEND:20261116T140001Z",
                "RRULE:FREQ=SECONDLY"),
            event(
                "UID:busy", "DTSTART:20261116T150000Z", "DTEND:20261116T160000Z", "TRANSP:OPAQUE"));

    assertThat(slots).containsExactly(slot("2026-11-16T15:00:00Z", "2026-11-16T16:00:00Z"));
  }

  @Test
  void parse_Should_RefuseARuleThatAloneYieldsMoreBusyTimesThanAreKept_BeforeExpandingIt() {
    for (var rule : List.of("RRULE:FREQ=HOURLY", "RRULE:FREQ=DAILY;BYHOUR=" + range(0, 23))) {
      var document =
          calendar(event("UID:flood", "DTSTART:20261107T000000Z", "DTEND:20261107T001500Z", rule));

      assertThat(errorOf(() -> parser.parse(document, FROM, TO, BERLIN)))
          .as(rule)
          .isEqualTo(TOO_LARGE);
    }
  }

  @Test
  void parse_Should_ExpandAnHourlyRuleThatEnds() {
    var slots =
        parse(
            event(
                "UID:hourly",
                "DTSTART:20261116T080000Z",
                "DTEND:20261116T081500Z",
                "RRULE:FREQ=HOURLY;COUNT=8"));

    assertThat(slots).hasSize(8);
    assertThat(slots.get(7).start()).isEqualTo(Instant.parse("2026-11-16T15:00:00Z"));
  }

  @Test
  void parse_Should_SkipRulesThatRepeatMoreOftenThanHourly_WithoutBuildingTheirDates() {
    var slots =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () ->
                parse(
                    event(
                        "UID:every-minute-via-lists",
                        "DTSTART:20261107T000000Z",
                        "DTEND:20261107T000100Z",
                        "RRULE:FREQ=DAILY;BYHOUR=" + range(0, 23) + ";BYMINUTE=" + range(0, 59)),
                    event(
                        "UID:every-second-via-lists",
                        "DTSTART:20261107T000000Z",
                        "DTEND:20261107T000001Z",
                        "RRULE:FREQ=DAILY;BYHOUR="
                            + range(0, 23)
                            + ";BYMINUTE="
                            + range(0, 59)
                            + ";BYSECOND="
                            + range(0, 59)),
                    event(
                        "UID:half-hourly",
                        "DTSTART:20261107T000000Z",
                        "DTEND:20261107T001000Z",
                        "RRULE:FREQ=HOURLY;BYMINUTE=0,30"),
                    event(
                        "UID:every-90-minutes",
                        "DTSTART:20261107T000000Z",
                        "DTEND:20261107T001000Z",
                        "RRULE:FREQ=MINUTELY;INTERVAL=90"),
                    event(
                        "UID:repeated-hours",
                        "DTSTART:20261107T000000Z",
                        "DTEND:20261107T001000Z",
                        "RRULE:FREQ=DAILY;BYHOUR=" + range(0, 23) + ",1,2"),
                    event("UID:kept", "DTSTART:20261116T090000Z", "DTEND:20261116T100000Z")));

    assertThat(slots).containsExactly(slot("2026-11-16T09:00:00Z", "2026-11-16T10:00:00Z"));
  }

  @Test
  void parse_Should_SkipRulesThatCostTooMuchToWalk() {
    var slots =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () ->
                parse(
                    event(
                        "UID:a-year-of-hours-per-step",
                        "DTSTART:20261107T000000Z",
                        "DTEND:20261107T001000Z",
                        "RRULE:FREQ=YEARLY;BYMONTH="
                            + range(1, 12)
                            + ";BYMONTHDAY="
                            + range(1, 31)
                            + ";BYHOUR="
                            + range(0, 23)
                            + ";UNTIL=20261108T000000Z"),
                    event(
                        "UID:counted-since-1970",
                        "DTSTART:19700101T000000Z",
                        "DTEND:19700101T001000Z",
                        "RRULE:FREQ=HOURLY;COUNT=100000000"),
                    event(
                        "UID:walked-since-1900",
                        "DTSTART:19000101T090000Z",
                        "DTEND:19000101T100000Z",
                        "RRULE:FREQ=HOURLY;BYHOUR=9"),
                    event("UID:kept", "DTSTART:20261116T090000Z", "DTEND:20261116T100000Z")));

    assertThat(slots).containsExactly(slot("2026-11-16T09:00:00Z", "2026-11-16T10:00:00Z"));
  }

  @Test
  void parse_Should_NotWalkARuleThatEndedBeforeTheWindow_ButKeepItsExtraDates() {
    var slots =
        parse(
            event(
                "UID:ended",
                "DTSTART:19000101T090000Z",
                "DTEND:19000101T100000Z",
                "RRULE:FREQ=HOURLY;BYHOUR=9;UNTIL=19100101T000000Z",
                "RDATE:20261120T090000Z"));

    assertThat(slots).containsExactly(slot("2026-11-20T09:00:00Z", "2026-11-20T10:00:00Z"));
  }

  @Test
  void parse_Should_AddExtraDates_And_LeaveOutExcludedOnes() {
    var slots =
        parse(
            event(
                "UID:series",
                "DTSTART:20261116T090000Z",
                "DTEND:20261116T100000Z",
                "RRULE:FREQ=DAILY;COUNT=5",
                "EXRULE:FREQ=DAILY;INTERVAL=2;COUNT=3",
                "RDATE:20261125T090000Z",
                "RDATE;VALUE=PERIOD:20261126T090000Z/20261126T113000Z"),
            event(
                "UID:zoned",
                "DTSTART;TZID=Europe/Berlin:20261201T090000",
                "DTEND;TZID=Europe/Berlin:20261201T100000",
                "RRULE:FREQ=DAILY;COUNT=2",
                "EXDATE:20261202T080000Z"));

    // The exclusion rule removes the 16th, 18th and 20th; the exclusion in UTC the 2nd December.
    assertThat(slots)
        .containsExactly(
            slot("2026-11-17T09:00:00Z", "2026-11-17T10:00:00Z"),
            slot("2026-11-19T09:00:00Z", "2026-11-19T10:00:00Z"),
            slot("2026-11-25T09:00:00Z", "2026-11-25T10:00:00Z"),
            slot("2026-11-26T09:00:00Z", "2026-11-26T11:30:00Z"),
            slot("2026-12-01T08:00:00Z", "2026-12-01T09:00:00Z"));
  }

  @Test
  void parse_Should_KeepExactlyTheLimit_And_RefuseOneBusyTimeMore() {
    var limit = CalendarBusyParser.MAX_SLOTS;

    assertThat(parser.parse(manyEvents(limit), FROM, TO, BERLIN)).hasSize(limit);
    assertThat(errorOf(() -> parser.parse(manyEvents(limit + 1), FROM, TO, BERLIN)))
        .isEqualTo(TOO_LARGE);
  }

  @Test
  void parse_Should_RefuseWhenTheEventsTogetherYieldMoreBusyTimesThanAreKept() {
    // 8 a day for 97 days: 776 each, under the limit alone, 2328 together.
    var everyMorning = "RRULE:FREQ=DAILY;BYHOUR=" + range(0, 7);
    var first = event("UID:a", "DTSTART:20261107T000000Z", "DTEND:20261107T000500Z", everyMorning);
    var second = event("UID:b", "DTSTART:20261107T000500Z", "DTEND:20261107T001000Z", everyMorning);
    var third = event("UID:c", "DTSTART:20261107T001000Z", "DTEND:20261107T001500Z", everyMorning);

    assertThat(parse(first)).hasSize(776);
    assertThat(errorOf(() -> parse(first, second, third))).isEqualTo(TOO_LARGE);
  }

  @Test
  void parse_Should_RefuseMoreEventsThanAreRead() {
    var events = new StringBuilder();
    for (var i = 0; i <= CalendarBusyParser.MAX_EVENTS; i++) {
      events.append(event("UID:e" + i, "DTSTART:20261116T090000Z", "DTEND:20261116T100000Z"));
    }

    assertThat(errorOf(() -> parse(events.toString()))).isEqualTo(TOO_LARGE);
  }

  @Test
  void parse_Should_StopOnceTheTimeBudgetIsSpent() {
    var spent = new CalendarBusyParser(Duration.ZERO);
    var document = calendar(event("UID:any", "DTSTART:20261116T090000Z", "DTEND:20261116T100000Z"));

    assertThat(errorOf(() -> spent.parse(document, FROM, TO, BERLIN))).isEqualTo(TOO_LARGE);
  }

  @Test
  void parse_Should_AcceptACalendarWithoutEvents() {
    assertThat(parse()).isEmpty();
  }

  @Test
  void parse_Should_RefuseWhatIsNoCalendar() {
    for (var document :
        List.of(
            "<html><body>Sign in</body></html>",
            "",
            "BEGIN:VEVENT\r\nDTSTART:20261116T090000Z\r\nEND:VEVENT\r\n",
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nDTSTART:2026")) {
      var refusal =
          catchThrowableOfType(
              CalendarImportException.class,
              () -> parser.parse(document.getBytes(StandardCharsets.UTF_8), FROM, TO, BERLIN));
      assertThat(refusal).as(document).isNotNull();
      assertThat(refusal.error()).isEqualTo(CalendarImportError.NOT_ICS);
    }
    assertThat(
            catchThrowableOfType(
                    CalendarImportException.class, () -> parser.parse(null, FROM, TO, BERLIN))
                .error())
        .isEqualTo(CalendarImportError.NOT_ICS);
  }

  @Test
  void ical4j_Should_NeverFetchTimeZonesOverTheNetwork() {
    assertThat(Configurator.getProperty("net.fortuna.ical4j.timezone.update.enabled"))
        .contains("false");
  }

  private static CalendarImportError errorOf(ThrowingCallable parse) {
    var refusal = catchThrowableOfType(CalendarImportException.class, parse);
    return refusal == null ? null : refusal.error();
  }

  /** {@code count} one-hour events, one an hour from the start of the window. */
  private static byte[] manyEvents(int count) {
    var format = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    var events = new String[count];
    for (var i = 0; i < count; i++) {
      var start = FROM.plus(Duration.ofHours(i));
      events[i] =
          event(
              "UID:e" + i,
              "DTSTART:" + format.format(start),
              "DTEND:" + format.format(start.plus(Duration.ofMinutes(30))));
    }
    return calendar(events);
  }

  private static String range(int first, int last) {
    return IntStream.rangeClosed(first, last)
        .mapToObj(String::valueOf)
        .collect(Collectors.joining(","));
  }

  private List<BusySlot> parse(String... events) {
    return parser.parse(calendar(events), FROM, TO, BERLIN);
  }

  private static byte[] calendar(String... events) {
    return ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Synthetic//Test//EN\r\n"
            + String.join("", events)
            + "END:VCALENDAR\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String event(String... lines) {
    return "BEGIN:VEVENT\r\nDTSTAMP:20261101T000000Z\r\n"
        + String.join("\r\n", lines)
        + "\r\nEND:VEVENT\r\n";
  }

  /** A zone of standard time {@code +0100} by {@code standardRule} and summer time in March. */
  private static String timeZone(String id, String start, String standardRule) {
    return String.join(
            "\r\n",
            "BEGIN:VTIMEZONE",
            "TZID:" + id,
            "BEGIN:STANDARD",
            "DTSTART:" + start,
            "TZOFFSETFROM:+0200",
            "TZOFFSETTO:+0100",
            standardRule,
            "END:STANDARD",
            "BEGIN:DAYLIGHT",
            "DTSTART:" + start,
            "TZOFFSETFROM:+0100",
            "TZOFFSETTO:+0200",
            "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=3",
            "END:DAYLIGHT",
            "END:VTIMEZONE")
        + "\r\n";
  }

  private static BusySlot slot(String start, String end) {
    return new BusySlot(Instant.parse(start), Instant.parse(end));
  }
}
