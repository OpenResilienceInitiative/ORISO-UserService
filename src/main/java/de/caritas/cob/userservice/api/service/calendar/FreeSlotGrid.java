package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.service.calendar.CalendarWorkingHoursService.Window;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The times an advice seeker may book (ORISO-Frontend#1050, ADR-020 §4): slots of one length that
 * start on a fixed step inside the counsellor's working hours of each date, from the earliest start
 * the lead time allows, and touch no busy time. A date's working hours are its day exception if it
 * has one (none = day off), else none on a public holiday of the counsellor's region, else the
 * weekly windows of its weekday, the same order as the counsellor's calendar. Times are wall-clock
 * times in the platform's zone, so 09:00 stays 09:00 across a clock change. One-off windows
 * (ORISO-Frontend#2494) are dated instants and cut by {@link #onceSlots}. Pure.
 */
final class FreeSlotGrid {

  private static final Comparator<FreeSlot> BY_START = Comparator.comparing(FreeSlot::start);

  private FreeSlotGrid() {}

  /**
   * A counsellor's working hours.
   *
   * @param weekly windows by weekday, 0 = Sunday .. 6 = Saturday
   * @param exceptions windows of single dates; an empty list is a day off
   * @param holidayRegion the Bundesland whose public holidays are days off, or null
   */
  record Plan(
      Map<Integer, List<Window>> weekly,
      Map<LocalDate, List<Window>> exceptions,
      String holidayRegion) {

    List<Window> windowsOn(LocalDate date) {
      var exception = exceptions.get(date);
      if (exception != null) {
        return exception;
      }
      if (GermanPublicHolidays.isHoliday(date, holidayRegion)) {
        return List.of();
      }
      // java.time counts Monday = 1 .. Sunday = 7; the plan counts Sunday = 0 .. Saturday = 6.
      return weekly.getOrDefault(date.getDayOfWeek().getValue() % 7, List.of());
    }
  }

  /** The free slots that start in [from, to) and not before {@code earliest}, by start. */
  static List<FreeSlot> slots(
      Plan plan,
      List<BusySlot> busy,
      Instant from,
      Instant to,
      Instant earliest,
      Duration length,
      Duration step,
      ZoneId zone) {
    var notBefore = earliest.isAfter(from) ? earliest : from;
    var result = new ArrayList<FreeSlot>();
    var lastDay = to.atZone(zone).toLocalDate();
    for (var day = notBefore.atZone(zone).toLocalDate();
        !day.isAfter(lastDay);
        day = day.plusDays(1)) {
      for (var window : plan.windowsOn(day)) {
        var windowEnd = instantOf(LocalDateTime.of(day, window.end()), zone);
        for (var local = LocalDateTime.of(day, window.start());
            local.toLocalDate().equals(day);
            local = local.plus(step)) {
          if (zone.getRules().getValidOffsets(local).isEmpty()) {
            // Skipped when the clocks spring forward: java.time would move it to the next hour,
            // which the grid offers in its own turn.
            continue;
          }
          var start = local.atZone(zone).toInstant();
          var end = start.plus(length);
          if (end.isAfter(windowEnd)) {
            break;
          }
          if (!start.isBefore(notBefore)
              && start.isBefore(to)
              && busy.stream().noneMatch(taken -> overlaps(taken, start, end))) {
            result.add(new FreeSlot(start, end));
          }
        }
      }
    }
    result.sort(BY_START);
    return result;
  }

  /**
   * The free slots of one one-off window [windowStart, windowEnd) (ORISO-Frontend#2494) that start
   * in [from, to) and not before {@code earliest}, by start: cut from the window's start on the
   * step, each of the length and inside the window; a window shorter than the length is one slot of
   * the window's own length. The window is a dated instant range, so no clock change moves it.
   */
  static List<FreeSlot> onceSlots(
      Instant windowStart,
      Instant windowEnd,
      List<BusySlot> busy,
      Instant from,
      Instant to,
      Instant earliest,
      Duration length,
      Duration step) {
    var notBefore = earliest.isAfter(from) ? earliest : from;
    var result = new ArrayList<FreeSlot>();
    for (var start = windowStart; start.isBefore(windowEnd); start = start.plus(step)) {
      var end = start.plus(length);
      if (end.isAfter(windowEnd)) {
        if (!start.equals(windowStart)) {
          break;
        }
        end = windowEnd;
      }
      var slotEnd = end;
      var slotStart = start;
      if (!start.isBefore(notBefore)
          && start.isBefore(to)
          && busy.stream().noneMatch(taken -> overlaps(taken, slotStart, slotEnd))) {
        result.add(new FreeSlot(start, end));
      }
    }
    return result;
  }

  /**
   * The instant of a wall-clock time; one the clocks skip in spring is the instant they jump, not
   * the shifted time java.time would give, so a window ending in the gap does not grow.
   */
  private static Instant instantOf(LocalDateTime local, ZoneId zone) {
    var rules = zone.getRules();
    if (rules.getValidOffsets(local).isEmpty()) {
      return rules.getTransition(local).getInstant();
    }
    return local.atZone(zone).toInstant();
  }

  private static boolean overlaps(BusySlot taken, Instant start, Instant end) {
    return taken.start().isBefore(end) && taken.end().isAfter(start);
  }
}
