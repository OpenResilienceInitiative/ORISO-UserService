package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingDayExceptionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingDayExceptionWindowsDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursDTO.HolidayRegionEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursWindowDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingTimeWindowDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarWorkingDayException;
import de.caritas.cob.userservice.api.model.CalendarWorkingHours;
import de.caritas.cob.userservice.api.model.CalendarWorkingHoursSetting;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingDayExceptionRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursSettingRepository;
import de.caritas.cob.userservice.api.service.LogService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A counsellor's working hours (ADR-032, contract addenda v1.1 and v1.2): weekly windows of local
 * wall-clock time in the platform's zone, kept as the counsellor typed them, so 08:00 stays 08:00
 * across a clock change; the Bundesland whose public holidays count as days off; and exceptions on
 * single dates, whose windows take the place of the weekly ones (none = the whole day off). Display
 * only: the calendar shades the time outside the windows and warns when something is put there;
 * booking, the subscription feed and the busy import do not look at them. The server computes no
 * holidays, it only keeps the region. A PUT of the working hours replaces every weekly window and
 * the region at once; the exceptions change one date at a time.
 */
@Service
public class CalendarWorkingHoursService {

  static final int MAX_WINDOWS = 14;
  static final int MAX_DAY_WINDOWS = 4;
  static final int MAX_EXCEPTIONS = 400;
  static final int MAX_EXCEPTION_DISTANCE_DAYS = 400;
  static final int EXCEPTIONS_SHOWN_PAST_DAYS = 62;
  private static final Pattern CLOCK = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");
  private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
  private static final Comparator<CalendarWorkingHours> IN_ORDER =
      Comparator.comparingInt(CalendarWorkingHours::getWeekday)
          .thenComparing(CalendarWorkingHours::getStartTime)
          .thenComparing(CalendarWorkingHours::getEndTime);
  private static final Comparator<Window> BY_START =
      Comparator.comparing(Window::start).thenComparing(Window::end);

  private final AuthenticatedUser caller;
  private final CalendarWorkingHoursRepository workingHours;
  private final CalendarWorkingHoursSettingRepository settings;
  private final CalendarWorkingDayExceptionRepository exceptions;
  private final Clock clock;
  private final ZoneId zone;

  @Autowired
  public CalendarWorkingHoursService(
      AuthenticatedUser caller,
      CalendarWorkingHoursRepository workingHours,
      CalendarWorkingHoursSettingRepository settings,
      CalendarWorkingDayExceptionRepository exceptions,
      Clock clock,
      @Value("${calendar.zone:Europe/Berlin}") String zone) {
    this.caller = caller;
    this.workingHours = workingHours;
    this.settings = settings;
    this.exceptions = exceptions;
    this.clock = clock;
    this.zone = ZoneId.of(zone);
  }

  /**
   * The caller's weekly windows by weekday and start (none set is an empty list), their holiday
   * region and their day exceptions from 62 days before today onwards.
   */
  public CalendarWorkingHoursDTO workingHours() {
    requireConsultant();
    var consultantId = caller.getUserId();
    return toDto(
        workingHours.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(consultantId),
        regionOf(consultantId),
        exceptionsOf(consultantId));
  }

  /**
   * Replaces every weekly window and the holiday region of the caller (absent = holidays are no
   * days off); nothing changes when one window is invalid. Day exceptions in the request are
   * ignored.
   */
  @Transactional
  public CalendarWorkingHoursDTO replace(CalendarWorkingHoursDTO request) {
    requireConsultant();
    var consultantId = caller.getUserId();
    var rows = rowsOf(request, consultantId);
    workingHours.deleteByConsultantId(consultantId);
    workingHours.saveAll(rows);
    storeRegion(consultantId, request.getHolidayRegion());
    rows.sort(IN_ORDER);
    return toDto(rows, request.getHolidayRegion(), exceptionsOf(consultantId));
  }

  /**
   * Creates or replaces the caller's exception on one date; an empty list makes the whole day off.
   * The date lies at most 400 days from today, and a counsellor keeps at most 400 exceptions; one
   * more date is a conflict, a date they already keep may always be replaced. Exceptions on dates
   * that can no longer be written are dropped first. Writes of one counsellor run one at a time.
   */
  @Transactional
  public CalendarWorkingDayExceptionDTO replaceException(
      LocalDate date, CalendarWorkingDayExceptionWindowsDTO request) {
    requireConsultant();
    var consultantId = caller.getUserId();
    var windows = dayWindowsOf(request);
    var today = today();
    requireWithinReach(date, today);
    lockExceptionsOf(consultantId);
    exceptions.deleteByConsultantIdBefore(
        consultantId, today.minusDays(MAX_EXCEPTION_DISTANCE_DAYS));
    if (!exceptions.existsByConsultantIdAndExceptionDate(consultantId, date)
        && exceptions.countDatesByConsultantId(consultantId) >= MAX_EXCEPTIONS) {
      throw new ConflictException(
          "At most " + MAX_EXCEPTIONS + " day exceptions.",
          ignored -> LogService.logInfo("A counsellor reached the limit of day exceptions."));
    }
    exceptions.deleteByConsultantIdAndExceptionDate(consultantId, date);
    exceptions.saveAll(exceptionRowsOf(consultantId, date, windows));
    return exceptionDto(date, windows);
  }

  /** Removes the caller's exception on one date, if there is one; the weekly windows apply. */
  @Transactional
  public void removeException(LocalDate date) {
    requireConsultant();
    if (date == null) {
      throw new BadRequestException("A day exception names its date.");
    }
    var consultantId = caller.getUserId();
    lockExceptionsOf(consultantId);
    exceptions.deleteByConsultantIdAndExceptionDate(consultantId, date);
  }

  /**
   * Waits for and holds the counsellor's row until the transaction ends, so that the limit check of
   * one write sees what a parallel one stored.
   */
  private void lockExceptionsOf(String consultantId) {
    if (exceptions.lockConsultant(consultantId).isEmpty()) {
      throw new ForbiddenException("Only a counsellor keeps working hours.");
    }
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

  /** The windows of one date, by start; at most four, each starting before it ends, apart. */
  static List<Window> dayWindowsOf(CalendarWorkingDayExceptionWindowsDTO request) {
    if (request == null || request.getWindows() == null) {
      throw new BadRequestException("A day exception names its windows; none is a day off.");
    }
    if (request.getWindows().size() > MAX_DAY_WINDOWS) {
      throw new BadRequestException("At most " + MAX_DAY_WINDOWS + " windows on one day.");
    }
    var windows = new ArrayList<Window>();
    for (var window : request.getWindows()) {
      if (window == null) {
        throw new BadRequestException("A window gives its times as HH:mm.");
      }
      var start = clockOf(window.getStart());
      var end = clockOf(window.getEnd());
      if (!start.isBefore(end)) {
        throw new BadRequestException("A window starts before it ends.");
      }
      windows.add(new Window(start, end));
    }
    windows.sort(BY_START);
    for (var i = 1; i < windows.size(); i++) {
      if (windows.get(i).start().isBefore(windows.get(i - 1).end())) {
        throw new BadRequestException("The windows of one day do not overlap.");
      }
    }
    return windows;
  }

  private static void requireWithinReach(LocalDate date, LocalDate today) {
    if (date == null
        || date.isBefore(today.minusDays(MAX_EXCEPTION_DISTANCE_DAYS))
        || date.isAfter(today.plusDays(MAX_EXCEPTION_DISTANCE_DAYS))) {
      throw new BadRequestException(
          "A day exception lies at most " + MAX_EXCEPTION_DISTANCE_DAYS + " days from today.");
    }
  }

  private static LocalTime clockOf(String value) {
    if (value == null || !CLOCK.matcher(value).matches()) {
      throw new BadRequestException("A window gives its times as HH:mm.");
    }
    return LocalTime.parse(value, HH_MM);
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(zone));
  }

  private HolidayRegionEnum regionOf(String consultantId) {
    return settings
        .findById(consultantId)
        .map(CalendarWorkingHoursSetting::getHolidayRegion)
        .map(HolidayRegionEnum::fromValue)
        .orElse(null);
  }

  private void storeRegion(String consultantId, HolidayRegionEnum region) {
    var stored = settings.findById(consultantId);
    if (stored.isEmpty() && region == null) {
      return;
    }
    var setting =
        stored.orElseGet(
            () -> {
              var created = new CalendarWorkingHoursSetting();
              created.setConsultantId(consultantId);
              return created;
            });
    setting.setHolidayRegion(region == null ? null : region.getValue());
    settings.save(setting);
  }

  /** The exceptions from 62 days before today onwards, by date, at most 400. */
  private List<CalendarWorkingDayExceptionDTO> exceptionsOf(String consultantId) {
    var byDate = new TreeMap<LocalDate, List<Window>>();
    for (var row :
        exceptions.findByConsultantIdAndExceptionDateGreaterThanEqual(
            consultantId, today().minusDays(EXCEPTIONS_SHOWN_PAST_DAYS))) {
      var windows = byDate.computeIfAbsent(row.getExceptionDate(), date -> new ArrayList<>());
      if (!row.isDayOff()) {
        windows.add(new Window(row.getStartTime(), row.getEndTime()));
      }
    }
    var result = new ArrayList<CalendarWorkingDayExceptionDTO>();
    for (var entry : byDate.entrySet()) {
      if (result.size() == MAX_EXCEPTIONS) {
        break;
      }
      entry.getValue().sort(BY_START);
      result.add(exceptionDto(entry.getKey(), entry.getValue()));
    }
    return result;
  }

  private static List<CalendarWorkingDayException> exceptionRowsOf(
      String consultantId, LocalDate date, List<Window> windows) {
    var rows = new ArrayList<CalendarWorkingDayException>();
    if (windows.isEmpty()) {
      rows.add(exceptionRow(consultantId, date, null, null));
    }
    for (var window : windows) {
      rows.add(exceptionRow(consultantId, date, window.start(), window.end()));
    }
    return rows;
  }

  private static CalendarWorkingDayException exceptionRow(
      String consultantId, LocalDate date, LocalTime start, LocalTime end) {
    var row = new CalendarWorkingDayException();
    row.setConsultantId(consultantId);
    row.setExceptionDate(date);
    row.setStartTime(start);
    row.setEndTime(end);
    return row;
  }

  private static CalendarWorkingDayExceptionDTO exceptionDto(LocalDate date, List<Window> windows) {
    var dtos = new ArrayList<CalendarWorkingTimeWindowDTO>();
    for (var window : windows) {
      dtos.add(
          new CalendarWorkingTimeWindowDTO()
              .start(HH_MM.format(window.start()))
              .end(HH_MM.format(window.end())));
    }
    return new CalendarWorkingDayExceptionDTO().date(date).windows(dtos);
  }

  private static CalendarWorkingHoursDTO toDto(
      List<CalendarWorkingHours> rows,
      HolidayRegionEnum region,
      List<CalendarWorkingDayExceptionDTO> dayExceptions) {
    var windows = new ArrayList<CalendarWorkingHoursWindowDTO>();
    for (var row : rows) {
      windows.add(
          new CalendarWorkingHoursWindowDTO()
              .weekday(row.getWeekday())
              .start(HH_MM.format(row.getStartTime()))
              .end(HH_MM.format(row.getEndTime())));
    }
    return new CalendarWorkingHoursDTO()
        .windows(windows)
        .holidayRegion(region)
        .exceptions(dayExceptions);
  }

  private void requireConsultant() {
    if (!caller.isConsultant()) {
      throw new ForbiddenException("Only a counsellor keeps working hours.");
    }
  }

  /** One working window of a date, in local wall-clock time. */
  record Window(LocalTime start, LocalTime end) {}
}
