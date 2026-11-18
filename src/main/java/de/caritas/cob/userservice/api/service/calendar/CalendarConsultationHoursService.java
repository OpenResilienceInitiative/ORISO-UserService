package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarConsultationHoursDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursWindowDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarBookingSetting;
import de.caritas.cob.userservice.api.model.CalendarConsultationHours;
import de.caritas.cob.userservice.api.port.out.CalendarBookingSettingRepository;
import de.caritas.cob.userservice.api.port.out.CalendarConsultationHoursRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A counsellor's Sprechstunden (ORISO-Frontend#2287, Frank 17 Nov 2026): the weekly windows advice
 * seekers can book, separate from the working hours, in local wall-clock time of the platform's
 * zone; and how they are booked: auto-accept, the notice period in days and the slot length. Only
 * the counsellor themself reads and writes them; a PUT replaces windows and settings at once.
 */
@Service
@RequiredArgsConstructor
public class CalendarConsultationHoursService {

  static final int MAX_WINDOWS = 14;
  static final int MAX_NOTICE_DAYS = 30;
  static final Set<Integer> SLOT_MINUTES = Set.of(30, 60);

  private static final Pattern CLOCK = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");
  private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
  private static final Comparator<CalendarConsultationHours> IN_ORDER =
      Comparator.comparingInt(CalendarConsultationHours::getWeekday)
          .thenComparing(CalendarConsultationHours::getStartTime)
          .thenComparing(CalendarConsultationHours::getEndTime);

  private final AuthenticatedUser caller;
  private final CalendarConsultationHoursRepository hours;
  private final CalendarBookingSettingRepository settings;
  private final ConsultantRepository consultants;

  /** The caller's windows by weekday and start, and their settings or the defaults. */
  @Transactional(readOnly = true)
  public CalendarConsultationHoursDTO consultationHours() {
    requireConsultant();
    var consultantId = caller.getUserId();
    return toDto(
        hours.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(consultantId),
        settingOf(consultantId));
  }

  /**
   * Replaces every window and the settings of the caller; nothing changes when anything is invalid.
   * Writes of one counsellor run one at a time, and a booking that holds the counsellor's row sees
   * either the old or the new Sprechstunden, never a half.
   */
  @Transactional
  public CalendarConsultationHoursDTO replace(CalendarConsultationHoursDTO request) {
    requireConsultant();
    var consultantId = caller.getUserId();
    var rows = rowsOf(request, consultantId);
    var setting = settingOf(request, consultantId);
    if (consultants.lockConsultant(consultantId).isEmpty()) {
      throw new ForbiddenException("Only a counsellor keeps Sprechstunden.");
    }
    hours.deleteByConsultantId(consultantId);
    hours.saveAll(rows);
    var stored = settings.findById(consultantId).orElseGet(CalendarBookingSetting::new);
    stored.setConsultantId(consultantId);
    stored.setAutoAccept(setting.isAutoAccept());
    stored.setNoticeDays(setting.getNoticeDays());
    stored.setSlotMinutes(setting.getSlotMinutes());
    settings.save(stored);
    rows.sort(IN_ORDER);
    return toDto(rows, setting);
  }

  private CalendarBookingSetting settingOf(String consultantId) {
    return settings
        .findById(consultantId)
        .orElseGet(() -> CalendarBookingSetting.defaults(consultantId));
  }

  private static CalendarBookingSetting settingOf(
      CalendarConsultationHoursDTO request, String consultantId) {
    var setting = CalendarBookingSetting.defaults(consultantId);
    if (request.getAutoAccept() != null) {
      setting.setAutoAccept(request.getAutoAccept());
    }
    if (request.getNoticeDays() != null) {
      if (request.getNoticeDays() < 0 || request.getNoticeDays() > MAX_NOTICE_DAYS) {
        throw new BadRequestException("The notice period lies from 0 to 30 days.");
      }
      setting.setNoticeDays(request.getNoticeDays());
    }
    if (request.getSlotMinutes() != null) {
      if (!SLOT_MINUTES.contains(request.getSlotMinutes())) {
        throw new BadRequestException("A slot lasts 30 or 60 minutes.");
      }
      setting.setSlotMinutes(request.getSlotMinutes());
    }
    return setting;
  }

  private static List<CalendarConsultationHours> rowsOf(
      CalendarConsultationHoursDTO request, String consultantId) {
    if (request == null || request.getWindows() == null) {
      throw new BadRequestException("Sprechstunden name their windows.");
    }
    if (request.getWindows().size() > MAX_WINDOWS) {
      throw new BadRequestException("At most " + MAX_WINDOWS + " Sprechstunden.");
    }
    var rows = new ArrayList<CalendarConsultationHours>();
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
      var row = new CalendarConsultationHours();
      row.setConsultantId(consultantId);
      row.setWeekday(window.getWeekday());
      row.setStartTime(start);
      row.setEndTime(end);
      rows.add(row);
    }
    var ordered = new ArrayList<>(rows);
    ordered.sort(IN_ORDER);
    for (var i = 1; i < ordered.size(); i++) {
      var before = ordered.get(i - 1);
      var current = ordered.get(i);
      if (before.getWeekday() == current.getWeekday()
          && current.getStartTime().isBefore(before.getEndTime())) {
        throw new BadRequestException("The Sprechstunden of one day do not overlap.");
      }
    }
    return rows;
  }

  private static LocalTime clockOf(String value) {
    if (value == null || !CLOCK.matcher(value).matches()) {
      throw new BadRequestException("A window gives its times as HH:mm.");
    }
    return LocalTime.parse(value, HH_MM);
  }

  private static CalendarConsultationHoursDTO toDto(
      List<CalendarConsultationHours> rows, CalendarBookingSetting setting) {
    var windows = new ArrayList<CalendarWorkingHoursWindowDTO>();
    for (var row : rows) {
      windows.add(
          new CalendarWorkingHoursWindowDTO()
              .weekday(row.getWeekday())
              .start(HH_MM.format(row.getStartTime()))
              .end(HH_MM.format(row.getEndTime())));
    }
    return new CalendarConsultationHoursDTO()
        .windows(windows)
        .autoAccept(setting.isAutoAccept())
        .noticeDays(setting.getNoticeDays())
        .slotMinutes(setting.getSlotMinutes());
  }

  private void requireConsultant() {
    if (!caller.isConsultant()) {
      throw new ForbiddenException("Only a counsellor keeps Sprechstunden.");
    }
  }
}
