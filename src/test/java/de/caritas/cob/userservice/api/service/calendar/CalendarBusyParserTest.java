package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import net.fortuna.ical4j.util.Configurator;
import org.junit.jupiter.api.Test;

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
  void parse_Should_StopAtTheRowLimit() {
    var slots =
        parse(
            event(
                "UID:hourly",
                "DTSTART:20261107T000000Z",
                "DTEND:20261107T001500Z",
                "RRULE:FREQ=HOURLY"));

    assertThat(slots).hasSize(CalendarBusyParser.MAX_SLOTS);
    assertThat(slots.get(0).start()).isEqualTo(Instant.parse("2026-11-07T00:00:00Z"));
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

  private static BusySlot slot(String start, String end) {
    return new BusySlot(Instant.parse(start), Instant.parse(end));
  }
}
