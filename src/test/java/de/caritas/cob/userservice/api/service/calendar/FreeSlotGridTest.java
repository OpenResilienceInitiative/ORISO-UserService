package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.service.calendar.CalendarWorkingHoursService.Window;
import de.caritas.cob.userservice.api.service.calendar.FreeSlotGrid.Plan;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FreeSlotGridTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static final Duration HOUR = Duration.ofMinutes(60);
  private static final Duration HALF_HOUR = Duration.ofMinutes(30);

  /** A Monday. */
  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 23);

  private static final int SUNDAY = 0;
  private static final int MON = 1;

  @Test
  void slots_Should_OfferEveryHalfHourAWholeHourFitsIntoTheWindow() {
    var plan = weekly(MON, window("09:00", "12:00"));

    assertThat(starts(slots(plan, List.of(), dayOf(MONDAY))))
        .containsExactly("09:00", "09:30", "10:00", "10:30", "11:00");
  }

  @Test
  void slots_Should_LastOneHour() {
    var plan = weekly(MON, window("09:00", "10:00"));

    var slots = slots(plan, List.of(), dayOf(MONDAY));

    assertThat(slots).containsExactly(new FreeSlot(at(MONDAY, "09:00"), at(MONDAY, "10:00")));
  }

  @Test
  void slots_Should_LeaveOutEverySlotThatTouchesBusyTime() {
    var plan = weekly(MON, window("09:00", "12:00"));
    var busy = List.of(new BusySlot(at(MONDAY, "10:00"), at(MONDAY, "10:30")));

    assertThat(starts(slots(plan, busy, dayOf(MONDAY)))).containsExactly("09:00", "10:30", "11:00");
  }

  @Test
  void slots_Should_LetBusyTimeEndExactlyWhereASlotStarts() {
    var plan = weekly(MON, window("09:00", "11:00"));
    var busy = List.of(new BusySlot(at(MONDAY, "08:00"), at(MONDAY, "09:00")));

    assertThat(starts(slots(plan, busy, dayOf(MONDAY)))).containsExactly("09:00", "09:30", "10:00");
  }

  @Test
  void slots_Should_StartNoEarlierThanTheLeadTimeAllows() {
    var plan = weekly(MON, window("09:00", "12:00"));
    var earliest = at(MONDAY, "09:45");

    var slots =
        FreeSlotGrid.slots(
            plan,
            List.of(),
            at(MONDAY, "00:00"),
            at(MONDAY.plusDays(1), "00:00"),
            earliest,
            HOUR,
            HALF_HOUR,
            BERLIN);

    assertThat(starts(slots)).containsExactly("10:00", "10:30", "11:00");
  }

  @Test
  void slots_Should_OfferNothingOnADayWithoutWorkingHours() {
    var plan = weekly(SUNDAY, window("09:00", "12:00"));

    assertThat(slots(plan, List.of(), dayOf(MONDAY))).isEmpty();
  }

  @Test
  void slots_Should_OfferNothing_When_NoWorkingHoursAreSetAtAll() {
    var plan = new Plan(Map.of(), Map.of(), null);

    assertThat(slots(plan, List.of(), dayOf(MONDAY))).isEmpty();
  }

  @Test
  void slots_Should_UseTheDayExceptionInsteadOfTheWeeklyWindows() {
    var plan =
        new Plan(
            Map.of(MON, List.of(window("09:00", "12:00"))),
            Map.of(MONDAY, List.of(window("14:00", "15:30"))),
            null);

    assertThat(starts(slots(plan, List.of(), dayOf(MONDAY)))).containsExactly("14:00", "14:30");
  }

  @Test
  void slots_Should_OfferNothingOnADayOff() {
    var plan =
        new Plan(Map.of(MON, List.of(window("09:00", "12:00"))), Map.of(MONDAY, List.of()), null);

    assertThat(slots(plan, List.of(), dayOf(MONDAY))).isEmpty();
  }

  @Test
  void slots_Should_OfferNothingOnAPublicHolidayOfTheRegion() {
    // Wednesday 18 November 2026 is Buß- und Bettag in Saxony only.
    var wednesday = LocalDate.of(2026, 11, 18);
    var weekly = Map.of(3, List.of(window("09:00", "10:00")));

    assertThat(slots(new Plan(weekly, Map.of(), "SN"), List.of(), dayOf(wednesday))).isEmpty();
    assertThat(slots(new Plan(weekly, Map.of(), "BY"), List.of(), dayOf(wednesday))).hasSize(1);
  }

  @Test
  void slots_Should_LetADayExceptionOpenAPublicHoliday() {
    var christmas = LocalDate.of(2026, 12, 25);
    var plan = new Plan(Map.of(), Map.of(christmas, List.of(window("10:00", "11:00"))), "BE");

    assertThat(slots(plan, List.of(), dayOf(christmas))).hasSize(1);
  }

  @Test
  void slots_Should_KeepTheWallClockTimeAcrossAClockChange() {
    // 29 March 2026: Berlin moves from UTC+1 to UTC+2. The following Monday is in summer time.
    var before = LocalDate.of(2026, 3, 23);
    var after = LocalDate.of(2026, 3, 30);
    var plan = weekly(MON, window("09:00", "10:00"));

    var slots = slots(plan, List.of(), new Instant[] {at(before, "00:00"), at(after, "23:00")});

    assertThat(slots)
        .extracting(FreeSlot::start)
        .containsExactly(
            Instant.parse("2026-03-23T08:00:00Z"), Instant.parse("2026-03-30T07:00:00Z"));
  }

  @Test
  void slots_Should_OfferOnlyStartsInsideTheAskedWindow() {
    var plan = weekly(MON, window("09:00", "12:00"));

    var slots = slots(plan, List.of(), new Instant[] {at(MONDAY, "10:00"), at(MONDAY, "11:00")});

    assertThat(starts(slots)).containsExactly("10:00", "10:30");
  }

  @Test
  void slots_Should_ComeInOrderOfTheirStart() {
    var plan =
        new Plan(
            Map.of(MON, List.of(window("14:00", "15:00"), window("09:00", "10:00"))),
            Map.of(),
            null);

    assertThat(starts(slots(plan, List.of(), dayOf(MONDAY)))).containsExactly("09:00", "14:00");
  }

  private static List<FreeSlot> slots(Plan plan, List<BusySlot> busy, Instant[] range) {
    return FreeSlotGrid.slots(plan, busy, range[0], range[1], range[0], HOUR, HALF_HOUR, BERLIN);
  }

  private static Instant[] dayOf(LocalDate date) {
    return new Instant[] {at(date, "00:00"), at(date.plusDays(1), "00:00")};
  }

  private static Plan weekly(int weekday, Window window) {
    return new Plan(Map.of(weekday, List.of(window)), Map.of(), null);
  }

  private static Window window(String start, String end) {
    return new Window(LocalTime.parse(start), LocalTime.parse(end));
  }

  private static Instant at(LocalDate date, String time) {
    return LocalDateTime.of(date, LocalTime.parse(time)).atZone(BERLIN).toInstant();
  }

  private static List<String> starts(List<FreeSlot> slots) {
    return slots.stream()
        .map(slot -> slot.start().atZone(BERLIN).toLocalTime().toString())
        .toList();
  }
}
