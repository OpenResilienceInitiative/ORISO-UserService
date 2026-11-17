package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.model.CalendarSync;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * One pass of the busy import over the configured calendars (ADR-032, ORISO-UserService#1645). The
 * calendars are read the least recently synced first, and none is started once the deadline has
 * passed; what is left is then the least recently synced, so the next run starts with it. Several
 * threads may work on one run: each takes the next calendar, and the list is read once, by the
 * first of them, so nothing is read before the work starts.
 */
@Slf4j
public final class CalendarImportRun {

  private final Supplier<List<CalendarSync>> due;
  private final Consumer<CalendarSync> importer;
  private final Instant deadline;
  private final Clock clock;
  private Deque<CalendarSync> pending;
  private boolean stopReported;

  CalendarImportRun(
      Supplier<List<CalendarSync>> due,
      Consumer<CalendarSync> importer,
      Instant deadline,
      Clock clock) {
    this.due = due;
    this.importer = importer;
    this.deadline = deadline;
    this.clock = clock;
  }

  /** Imports calendars until none is left or the deadline has passed; one failure stops none. */
  public void work() {
    for (var next = next(); next != null; next = next()) {
      try {
        importer.accept(next);
      } catch (RuntimeException failure) {
        // Neither the address nor a message that may quote it goes into the log.
        log.warn("A calendar import failed: {}", failure.getClass().getSimpleName());
      }
    }
  }

  private synchronized CalendarSync next() {
    if (!clock.instant().isBefore(deadline)) {
      if (!stopReported && pending != null && !pending.isEmpty()) {
        stopReported = true;
        log.info(
            "The calendar import used its time budget; {} calendars go first in the next run.",
            pending.size());
      }
      return null;
    }
    if (pending == null) {
      try {
        pending = new ArrayDeque<>(due.get());
      } catch (RuntimeException unreadable) {
        pending = new ArrayDeque<>();
        throw unreadable;
      }
    }
    return pending.poll();
  }
}
