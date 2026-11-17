package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.adapters.web.dto.BlockerCreateDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.BlockerRepeatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.BlockerTimeDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarBlocker;
import de.caritas.cob.userservice.api.model.CalendarBlocker.Source;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A counsellor's own blockers (ADR-032, ORISO-UserService#1000). A repeat is expanded here, once,
 * into single blockers that share a series id: the same clock time in the platform's zone on the
 * first day and on every chosen weekday through the last day, so a series keeps its hour across a
 * clock change. Only the owner moves or removes a blocker; anybody else gets the 404 of a blocker
 * that does not exist. Imported busy times are no blockers and cannot be moved or removed here.
 */
@Service
public class CalendarBlockerService {

  static final int MAX_SERIES = 120;
  static final int MAX_NOTE = 255;
  static final Duration MAX_PAST = Duration.ofDays(1);
  private static final String NOT_FOUND = "Blocker not found.";

  private final AuthenticatedUser caller;
  private final CalendarBlockerRepository blockers;
  private final Clock clock;
  private final ZoneId zone;

  @Autowired
  public CalendarBlockerService(
      AuthenticatedUser caller,
      CalendarBlockerRepository blockers,
      Clock clock,
      @Value("${calendar.zone:Europe/Berlin}") String zone) {
    this.caller = caller;
    this.blockers = blockers;
    this.clock = clock;
    this.zone = ZoneId.of(zone);
  }

  @Transactional
  public List<CalendarEntryDTO> create(BlockerCreateDTO request) {
    requireConsultant();
    var start = request.getStart().toInstant();
    var end = request.getEnd().toInstant();
    requireValidTime(start, end);
    var note = noteOf(request.getNote());
    var slots = expand(start, end, request.getRepeat(), zone);
    var seriesId = request.getRepeat() == null ? null : UUID.randomUUID();
    var now = Instant.now(clock);
    var created = new ArrayList<CalendarBlocker>();
    for (var slot : slots) {
      var blocker = new CalendarBlocker();
      blocker.setConsultantId(caller.getUserId());
      blocker.setTenantId(caller.getTenantId());
      blocker.setStartTime(slot.start());
      blocker.setEndTime(slot.end());
      blocker.setAllDay(Boolean.TRUE.equals(request.getAllDay()));
      blocker.setNote(note);
      blocker.setSeriesId(seriesId);
      blocker.setSource(Source.ORISO);
      blocker.setCreateDate(now);
      created.add(blockers.save(blocker));
    }
    return created.stream().map(this::toDto).toList();
  }

  @Transactional
  public CalendarEntryDTO move(UUID blockerId, BlockerTimeDTO time) {
    requireConsultant();
    var blocker = ownBlocker(blockerId);
    var start = time.getStart().toInstant();
    var end = time.getEnd().toInstant();
    requireValidTime(start, end);
    blocker.setStartTime(start);
    blocker.setEndTime(end);
    return toDto(blockers.save(blocker));
  }

  @Transactional
  public void delete(UUID blockerId) {
    requireConsultant();
    blockers.delete(ownBlocker(blockerId));
  }

  /** One slot without a repeat, else the first day plus every chosen weekday through until. */
  static List<Slot> expand(Instant start, Instant end, BlockerRepeatDTO repeat, ZoneId zone) {
    var slots = new ArrayList<Slot>();
    slots.add(new Slot(start, end));
    if (repeat == null) {
      return slots;
    }
    var weekdays = repeat.getWeekdays() == null ? List.<Integer>of() : repeat.getWeekdays();
    if (weekdays.isEmpty()
        || weekdays.stream().anyMatch(day -> day == null || day < 0 || day > 6)) {
      throw new BadRequestException("A repeat names weekdays from 0 (Sunday) to 6 (Saturday).");
    }
    var localStart = start.atZone(zone).toLocalDateTime();
    var localEnd = end.atZone(zone).toLocalDateTime();
    var firstDay = localStart.toLocalDate();
    if (repeat.getUntil() == null || repeat.getUntil().isBefore(firstDay)) {
      throw new BadRequestException("A repeat ends on or after its first day.");
    }
    var chosen = new HashSet<>(weekdays);
    var length = Duration.between(start, end);
    for (var day = firstDay.plusDays(1); !day.isAfter(repeat.getUntil()); day = day.plusDays(1)) {
      // java.time counts Monday = 1 .. Sunday = 7; the API counts Sunday = 0 .. Saturday = 6.
      if (!chosen.contains(day.getDayOfWeek().getValue() % 7)) {
        continue;
      }
      if (slots.size() >= MAX_SERIES) {
        throw new BadRequestException("A series has at most 120 blockers.");
      }
      var offset = ChronoUnit.DAYS.between(firstDay, day);
      var slotStart = localStart.plusDays(offset).atZone(zone).toInstant();
      var slotEnd = localEnd.plusDays(offset).atZone(zone).toInstant();
      // A clock time skipped by the change to summer time would leave nothing: keep the length.
      slots.add(new Slot(slotStart, slotEnd.isAfter(slotStart) ? slotEnd : slotStart.plus(length)));
    }
    return slots;
  }

  private void requireValidTime(Instant start, Instant end) {
    if (!end.isAfter(start)) {
      throw new BadRequestException("The end of a blocker must be after its start.");
    }
    if (start.isBefore(Instant.now(clock).minus(MAX_PAST))) {
      throw new BadRequestException("A blocker starts at most one day in the past.");
    }
  }

  private static String noteOf(String note) {
    if (note == null || note.isBlank()) {
      return null;
    }
    var trimmed = note.trim();
    if (trimmed.length() > MAX_NOTE) {
      throw new BadRequestException("A blocker note has at most 255 characters.");
    }
    return trimmed;
  }

  private CalendarBlocker ownBlocker(UUID blockerId) {
    return blockers
        .findByIdAndConsultantIdAndSource(blockerId, caller.getUserId(), Source.ORISO)
        .orElseThrow(() -> new NotFoundException(NOT_FOUND));
  }

  private CalendarEntryDTO toDto(CalendarBlocker blocker) {
    return CalendarService.own(CalendarEntryReader.blocker(blocker), caller.getUserId(), true);
  }

  private void requireConsultant() {
    if (!caller.isConsultant()) {
      throw new ForbiddenException("Only a counsellor blocks time in a calendar.");
    }
  }

  record Slot(Instant start, Instant end) {}
}
