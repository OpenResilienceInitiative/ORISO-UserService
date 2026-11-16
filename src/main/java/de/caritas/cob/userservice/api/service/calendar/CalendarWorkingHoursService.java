package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursWindowDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarWorkingHours;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursRepository;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A counsellor's working hours (ADR-032, contract addendum v1.1): weekly windows of local
 * wall-clock time in the platform's zone, kept as the counsellor typed them, so 08:00 stays 08:00
 * across a clock change. Display only: the calendar shades the time outside the windows and warns
 * when something is put there; booking, the subscription feed and the busy import do not look at
 * them. A PUT replaces every window at once.
 */
@Service
@RequiredArgsConstructor
public class CalendarWorkingHoursService {

  static final int MAX_WINDOWS = 14;
  private static final Pattern CLOCK = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");
  private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
  private static final Comparator<CalendarWorkingHours> IN_ORDER =
      Comparator.comparingInt(CalendarWorkingHours::getWeekday)
          .thenComparing(CalendarWorkingHours::getStartTime)
          .thenComparing(CalendarWorkingHours::getEndTime);

  private final AuthenticatedUser caller;
  private final CalendarWorkingHoursRepository workingHours;

  /** The caller's windows, by weekday and start; none set is an empty list. */
  public CalendarWorkingHoursDTO workingHours() {
    requireConsultant();
    return toDto(
        workingHours.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(caller.getUserId()));
  }

  /** Replaces every window of the caller; nothing changes when one window is invalid. */
  @Transactional
  public CalendarWorkingHoursDTO replace(CalendarWorkingHoursDTO request) {
    requireConsultant();
    var consultantId = caller.getUserId();
    var rows = rowsOf(request, consultantId);
    workingHours.deleteByConsultantId(consultantId);
    workingHours.saveAll(rows);
    rows.sort(IN_ORDER);
    return toDto(rows);
  }

  private static List<CalendarWorkingHours> rowsOf(
      CalendarWorkingHoursDTO request, String consultantId) {
    if (request == null || request.getWindows() == null) {
      throw new BadRequestException("Working hours name their windows.");
    }
    if (request.getWindows().size() > MAX_WINDOWS) {
      throw new BadRequestException("At most " + MAX_WINDOWS + " working hour windows.");
    }
    var rows = new ArrayList<CalendarWorkingHours>();
    for (var window : request.getWindows()) {
      if (window == null
          || window.getWeekday() == null
          || window.getWeekday() < 0
          || window.getWeekday() > 6) {
        throw new BadRequestException("A window names a weekday from 0 (Sunday) to 6 (Saturday).");
      }
      var start = clockOf(window.getStart());
      var end = clockOf(window.getEnd());
      if (!start.isBefore(end)) {
        throw new BadRequestException("A window starts before it ends.");
      }
      var row = new CalendarWorkingHours();
      row.setConsultantId(consultantId);
      row.setWeekday(window.getWeekday());
      row.setStartTime(start);
      row.setEndTime(end);
      rows.add(row);
    }
    return rows;
  }

  private static LocalTime clockOf(String value) {
    if (value == null || !CLOCK.matcher(value).matches()) {
      throw new BadRequestException("A window gives its times as HH:mm.");
    }
    return LocalTime.parse(value, HH_MM);
  }

  private static CalendarWorkingHoursDTO toDto(List<CalendarWorkingHours> rows) {
    var windows = new ArrayList<CalendarWorkingHoursWindowDTO>();
    for (var row : rows) {
      windows.add(
          new CalendarWorkingHoursWindowDTO()
              .weekday(row.getWeekday())
              .start(HH_MM.format(row.getStartTime()))
              .end(HH_MM.format(row.getEndTime())));
    }
    return new CalendarWorkingHoursDTO().windows(windows);
  }

  private void requireConsultant() {
    if (!caller.isConsultant()) {
      throw new ForbiddenException("Only a counsellor keeps working hours.");
    }
  }
}
