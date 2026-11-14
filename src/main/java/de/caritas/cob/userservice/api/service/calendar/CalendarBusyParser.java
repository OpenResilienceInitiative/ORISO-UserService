package de.caritas.cob.userservice.api.service.calendar;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.RRule;
import net.fortuna.ical4j.transform.recurrence.Frequency;

/**
 * Reads the busy times out of an iCalendar document (ADR-032, ORISO-UserService#1645). Of every
 * event only start and end are taken; summary, description, location and attendees are never looked
 * at. Events marked free (TRANSP:TRANSPARENT) or cancelled are no busy time. Repeating events are
 * expanded inside the window; times without a zone and whole-day events are read in the platform's
 * zone.
 */
@org.springframework.stereotype.Component
public class CalendarBusyParser {

  static final int MAX_SLOTS = 2000;
  private static final Comparator<BusySlot> BY_START =
      Comparator.comparing(BusySlot::start).thenComparing(BusySlot::end);

  /**
   * @return the busy times overlapping {@code [from, to)}, earliest first, at most {@value
   *     #MAX_SLOTS}
   * @throws CalendarImportException {@code NOT_ICS} when the bytes are no iCalendar document
   */
  public List<BusySlot> parse(byte[] document, Instant from, Instant to, ZoneId zone) {
    var events = eventsOf(document);
    // A moved or cancelled single occurrence comes as an event of its own (RECURRENCE-ID); the
    // series must not also report the occurrence it replaces.
    var replaced = new HashMap<String, Set<Instant>>();
    for (var event : events) {
      var recurrenceId = dateOf(event, Property.RECURRENCE_ID);
      if (recurrenceId != null) {
        replaced
            .computeIfAbsent(uidOf(event), uid -> new HashSet<>())
            .add(toInstant(recurrenceId, zone));
      }
    }
    var slots = new TreeSet<>(BY_START);
    for (var event : events) {
      if (isFree(event) || isTooFrequent(event)) {
        continue;
      }
      var isOverride = dateOf(event, Property.RECURRENCE_ID) != null;
      var replacedHere =
          isOverride ? Set.<Instant>of() : replaced.getOrDefault(uidOf(event), Set.of());
      try {
        Set<Period<Temporal>> occurrences =
            event.calculateRecurrenceSet(
                windowFor(dateOf(event, Property.DTSTART), from, to, zone));
        for (var occurrence : occurrences) {
          var start = toInstant(occurrence.getStart(), zone);
          var end = toInstant(occurrence.getEnd(), zone);
          if (end.isAfter(start)
              && start.isBefore(to)
              && end.isAfter(from)
              && !replacedHere.contains(start)) {
            slots.add(new BusySlot(start, end));
          }
        }
      } catch (RuntimeException unreadable) {
        // One event that cannot be read does not cost the counsellor the rest of the calendar.
      }
    }
    return slots.stream().limit(MAX_SLOTS).toList();
  }

  /**
   * The window in the kind of time the event itself uses: ical4j compares like with like, so a
   * whole-day event (a date) or a time without a zone needs a window of dates or local times. A day
   * is added on both sides of those; the exact overlap is decided on the instants afterwards.
   */
  private static Period<? extends Temporal> windowFor(
      Temporal eventStart, Instant from, Instant to, ZoneId zone) {
    if (eventStart instanceof LocalDate) {
      return new Period<>(
          from.atZone(zone).toLocalDate().minusDays(1), to.atZone(zone).toLocalDate().plusDays(1));
    }
    if (eventStart instanceof LocalDateTime) {
      return new Period<>(
          from.atZone(zone).toLocalDateTime().minusDays(1),
          to.atZone(zone).toLocalDateTime().plusDays(1));
    }
    return new Period<>(from.atZone(zone), to.atZone(zone));
  }

  private static List<VEvent> eventsOf(byte[] document) {
    if (document == null
        || !new String(document, StandardCharsets.UTF_8).contains("BEGIN:VCALENDAR")) {
      throw new CalendarImportException(CalendarImportError.NOT_ICS);
    }
    try {
      Calendar calendar = new CalendarBuilder().build(new ByteArrayInputStream(document));
      return new ArrayList<>(calendar.<VEvent>getComponents(Component.VEVENT));
    } catch (IOException | ParserException | RuntimeException notIcs) {
      throw new CalendarImportException(CalendarImportError.NOT_ICS);
    }
  }

  private static boolean isFree(VEvent event) {
    return hasValue(event, Property.TRANSP, "TRANSPARENT")
        || hasValue(event, Property.STATUS, "CANCELLED");
  }

  /** A rule that repeats by the second or minute is no appointment; expanding it is refused. */
  private static boolean isTooFrequent(VEvent event) {
    for (Property property : event.getProperties(Property.RRULE)) {
      if (property instanceof RRule<?> rule) {
        var frequency = rule.getRecur().getFrequency();
        if (frequency == Frequency.SECONDLY || frequency == Frequency.MINUTELY) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasValue(VEvent event, String property, String value) {
    return event.getProperties(property).stream()
        .anyMatch(found -> value.equalsIgnoreCase(found.getValue()));
  }

  private static Temporal dateOf(VEvent event, String property) {
    for (Property found : event.getProperties(property)) {
      if (found instanceof DateProperty<?> date && date.getDate() != null) {
        return date.getDate();
      }
    }
    return null;
  }

  private static String uidOf(VEvent event) {
    return event.getProperties(Property.UID).stream()
        .map(Property::getValue)
        .findFirst()
        .orElse("");
  }

  private static Instant toInstant(Temporal temporal, ZoneId zone) {
    if (temporal instanceof Instant instant) {
      return instant;
    }
    if (temporal instanceof ZonedDateTime zoned) {
      return zoned.toInstant();
    }
    if (temporal instanceof OffsetDateTime offset) {
      return offset.toInstant();
    }
    if (temporal instanceof LocalDateTime local) {
      return local.atZone(zone).toInstant();
    }
    if (temporal instanceof LocalDate day) {
      return day.atStartOfDay(zone).toInstant();
    }
    throw new IllegalArgumentException("Unsupported time value");
  }
}
