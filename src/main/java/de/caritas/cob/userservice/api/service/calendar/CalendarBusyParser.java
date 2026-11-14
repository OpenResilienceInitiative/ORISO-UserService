package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.service.calendar.CalendarRecurrenceCost.Verdict;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.TemporalAmountAdapter;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.Value;
import net.fortuna.ical4j.model.property.DateListProperty;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.ExRule;
import net.fortuna.ical4j.model.property.RDate;
import net.fortuna.ical4j.model.property.RRule;

/**
 * Reads the busy times out of an iCalendar document (ADR-032, ORISO-UserService#1645). Of every
 * event only start and end are taken; summary, description, location and attendees are never looked
 * at. Events marked free (TRANSP:TRANSPARENT) or cancelled are no busy time. Repeating events are
 * expanded inside the window; times without a zone and whole-day events are read in the platform's
 * zone.
 *
 * <p>The document comes from an address the counsellor typed in, so its author may be hostile.
 * Nothing is expanded without a bound decided beforehand ({@link CalendarRecurrenceCost}): a rule
 * that repeats more often than hourly or costs too much to walk makes its event unread; a rule that
 * alone would yield more than {@value #MAX_SLOTS} busy times, more than {@value #MAX_SLOTS} busy
 * times in all, more than {@value #MAX_EVENTS} events or more than the time budget refuse the whole
 * document as {@link CalendarImportError#TOO_LARGE}.
 */
@org.springframework.stereotype.Component
public class CalendarBusyParser {

  static final int MAX_SLOTS = 2000;
  static final int MAX_EVENTS = 10_000;
  static final Duration TIME_BUDGET = Duration.ofSeconds(5);

  /**
   * Dates of one rule that may fall outside the window without being busy times: the window of a
   * whole-day or zoneless event reaches a day further on both sides.
   */
  private static final int MARGIN = 2 * CalendarRecurrenceCost.MAX_PER_DAY + 2;

  private static final Comparator<BusySlot> BY_START =
      Comparator.comparing(BusySlot::start).thenComparing(BusySlot::end);

  private final Duration timeBudget;

  public CalendarBusyParser() {
    this(TIME_BUDGET);
  }

  CalendarBusyParser(Duration timeBudget) {
    this.timeBudget = timeBudget;
  }

  /**
   * @return the busy times overlapping {@code [from, to)}, earliest first, at most {@value
   *     #MAX_SLOTS}
   * @throws CalendarImportException {@code NOT_ICS} when the bytes are no iCalendar document,
   *     {@code TOO_LARGE} when it holds more busy times or events than are kept, or takes too long
   *     to read
   */
  public List<BusySlot> parse(byte[] document, Instant from, Instant to, ZoneId zone) {
    var deadline = new Deadline(timeBudget);
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
      deadline.check();
      if (isFree(event)) {
        continue;
      }
      var isOverride = dateOf(event, Property.RECURRENCE_ID) != null;
      var replacedHere =
          isOverride ? Set.<Instant>of() : replaced.getOrDefault(uidOf(event), Set.of());
      try {
        slots.addAll(new EventExpansion(event, from, to, zone, replacedHere, deadline).slots());
      } catch (CalendarImportException refused) {
        throw refused;
      } catch (RuntimeException unreadable) {
        // One event that cannot be read, or whose rule is refused, does not cost the counsellor
        // the rest of the calendar.
      }
      if (slots.size() > MAX_SLOTS) {
        throw tooLarge();
      }
    }
    return List.copyOf(slots);
  }

  /** The busy times of one event; every expansion bounded before it starts. */
  private static final class EventExpansion {

    private final VEvent event;
    private final Instant from;
    private final Instant to;
    private final ZoneId zone;
    private final Deadline deadline;
    private final Set<Instant> excluded;
    private final TreeSet<BusySlot> found = new TreeSet<>(BY_START);

    EventExpansion(
        VEvent event,
        Instant from,
        Instant to,
        ZoneId zone,
        Set<Instant> replaced,
        Deadline deadline) {
      this.event = event;
      this.from = from;
      this.to = to;
      this.zone = zone;
      this.deadline = deadline;
      this.excluded = new HashSet<>(replaced);
    }

    /** Mirrors ical4j's recurrence set (RRULE or DTSTART, plus RDATE, minus EXDATE and EXRULE). */
    Set<BusySlot> slots() {
      var start = dateOf(event, Property.DTSTART);
      if (start == null) {
        return Set.of();
      }
      var length = lengthOf(event, start);
      var window = windowFor(start, from, to, zone);
      // An occurrence that starts up to one length before the window still reaches into it.
      var expandFrom = window.start().minus(length);
      var expandTo = window.end();

      for (Property exdate : event.getProperties(Property.EXDATE)) {
        if (exdate instanceof DateListProperty<?> dates) {
          for (var date : dates.getDates()) {
            excluded.add(toInstant(date, zone));
          }
        }
      }
      for (var rule : rulesOf(Property.EXRULE)) {
        var verdict = judge(rule, start, expandFrom, expandTo);
        if (verdict == Verdict.NONE) {
          continue;
        }
        if (verdict != Verdict.EXPAND) {
          throw new UnreadableEvent();
        }
        var cap = MAX_SLOTS + MARGIN;
        var dates = datesOf(rule, start, expandFrom, expandTo, cap);
        if (dates.size() >= cap) {
          throw new UnreadableEvent();
        }
        for (var date : dates) {
          excluded.add(toInstant(date, zone));
        }
      }

      // Without a length none of these is a busy time; their dates need not be built at all.
      if (toInstant(start.plus(length), zone).isAfter(toInstant(start, zone))) {
        var rules = rulesOf(Property.RRULE);
        if (rules.isEmpty()) {
          add(start, start.plus(length));
        }
        for (var rule : rules) {
          var verdict = judge(rule, start, expandFrom, expandTo);
          if (verdict == Verdict.NONE) {
            continue;
          }
          if (verdict == Verdict.REFUSED) {
            throw new UnreadableEvent();
          }
          if (verdict == Verdict.TOO_MANY) {
            throw tooLarge();
          }
          // The cap holds even if the estimate were wrong: reaching it means more busy times than
          // are kept, since at most the excluded dates and the margin are no busy time.
          var cap = MAX_SLOTS + excluded.size() + MARGIN;
          var dates = datesOf(rule, start, expandFrom, expandTo, cap);
          if (dates.size() >= cap) {
            throw tooLarge();
          }
          for (var date : dates) {
            add(date, date.plus(length));
          }
        }
      }
      for (Property property : event.getProperties(Property.RDATE)) {
        if (!(property instanceof RDate<?> rdate)) {
          continue;
        }
        if (Optional.of(Value.PERIOD).equals(property.getParameter(Parameter.VALUE))) {
          for (var period : rdate.getPeriods().orElse(Set.of())) {
            add(period.getStart(), period.getEnd());
          }
        } else {
          for (var date : rdate.getDates()) {
            add(date, date.plus(length));
          }
        }
      }
      return found;
    }

    private Verdict judge(Recur<?> rule, Temporal start, Temporal expandFrom, Temporal expandTo) {
      deadline.check();
      var until = rule.getUntil() == null ? null : toInstant(rule.getUntil(), zone);
      return CalendarRecurrenceCost.judge(
          rule,
          toInstant(start, zone),
          until,
          toInstant(expandFrom, zone),
          toInstant(expandTo, zone),
          MAX_SLOTS);
    }

    private void add(Temporal start, Temporal end) {
      deadline.check();
      var startsAt = toInstant(start, zone);
      var endsAt = toInstant(end, zone);
      if (endsAt.isAfter(startsAt)
          && startsAt.isBefore(to)
          && endsAt.isAfter(from)
          && !excluded.contains(startsAt)) {
        found.add(new BusySlot(startsAt, endsAt));
        if (found.size() > MAX_SLOTS) {
          throw tooLarge();
        }
      }
    }

    private List<Recur<?>> rulesOf(String name) {
      var rules = new ArrayList<Recur<?>>();
      for (Property property : event.getProperties(name)) {
        if (property instanceof RRule<?> rule) {
          rules.add(rule.getRecur());
        } else if (property instanceof ExRule<?> rule) {
          rules.add(rule.getRecur());
        }
      }
      return rules;
    }
  }

  /**
   * At most {@code maxCount} dates of the rule in the span; ical4j stops building once it has that
   * many.
   */
  @SuppressWarnings("unchecked")
  private static List<Temporal> datesOf(
      Recur<?> rule, Temporal seed, Temporal from, Temporal to, int maxCount) {
    return ((Recur<Temporal>) rule).getDates(seed, from, to, maxCount);
  }

  /** As ical4j measures an event: DTEND minus DTSTART, else DURATION, else nothing. */
  private static TemporalAmount lengthOf(VEvent event, Temporal start) {
    var end = dateOf(event, Property.DTEND);
    if (end != null) {
      return TemporalAmountAdapter.between(start, end).getDuration();
    }
    for (Property property : event.getProperties(Property.DURATION)) {
      if (property instanceof net.fortuna.ical4j.model.property.Duration duration
          && duration.getDuration() != null) {
        return duration.getDuration();
      }
    }
    return Duration.ZERO;
  }

  /**
   * The window in the kind of time the event itself uses: ical4j compares like with like, so a
   * whole-day event (a date) or a time without a zone needs a window of dates or local times. A day
   * is added on both sides of those; the exact overlap is decided on the instants afterwards.
   */
  private static Span windowFor(Temporal eventStart, Instant from, Instant to, ZoneId zone) {
    if (eventStart instanceof LocalDate) {
      return new Span(
          from.atZone(zone).toLocalDate().minusDays(1), to.atZone(zone).toLocalDate().plusDays(1));
    }
    if (eventStart instanceof LocalDateTime) {
      return new Span(
          from.atZone(zone).toLocalDateTime().minusDays(1),
          to.atZone(zone).toLocalDateTime().plusDays(1));
    }
    return new Span(from.atZone(zone), to.atZone(zone));
  }

  private record Span(Temporal start, Temporal end) {}

  private static List<VEvent> eventsOf(byte[] document) {
    var text = document == null ? null : new String(document, StandardCharsets.UTF_8);
    if (text == null || !text.contains("BEGIN:VCALENDAR")) {
      throw new CalendarImportException(CalendarImportError.NOT_ICS);
    }
    // Counted before ical4j builds anything: two megabytes of tiny events must not become a model.
    if (countOf(text.toUpperCase(Locale.ROOT), "BEGIN:VEVENT") > MAX_EVENTS) {
      throw tooLarge();
    }
    List<VEvent> events;
    try {
      Calendar calendar = new CalendarBuilder().build(new ByteArrayInputStream(document));
      events = new ArrayList<>(calendar.<VEvent>getComponents(Component.VEVENT));
    } catch (IOException | ParserException | RuntimeException notIcs) {
      throw new CalendarImportException(CalendarImportError.NOT_ICS);
    }
    // Folded lines escape the count above.
    if (events.size() > MAX_EVENTS) {
      throw tooLarge();
    }
    return events;
  }

  private static int countOf(String text, String needle) {
    var count = 0;
    for (var at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
      count++;
    }
    return count;
  }

  private static boolean isFree(VEvent event) {
    return hasValue(event, Property.TRANSP, "TRANSPARENT")
        || hasValue(event, Property.STATUS, "CANCELLED");
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

  private static CalendarImportException tooLarge() {
    return new CalendarImportException(CalendarImportError.TOO_LARGE);
  }

  /** An event that is not read; the rest of the calendar is. */
  private static final class UnreadableEvent extends RuntimeException {

    UnreadableEvent() {
      super(null, null, false, false);
    }
  }

  /** The wall-clock budget of one document; reading stops once it is spent. */
  private static final class Deadline {

    private final long end;

    Deadline(Duration budget) {
      this.end = System.nanoTime() + budget.toNanos();
    }

    void check() {
      if (System.nanoTime() - end > 0) {
        throw tooLarge();
      }
    }
  }
}
