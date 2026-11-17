package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.model.CalendarBookingSetting;
import de.caritas.cob.userservice.api.model.CalendarWorkingHoursSetting;
import de.caritas.cob.userservice.api.port.out.CalendarBookingSettingRepository;
import de.caritas.cob.userservice.api.port.out.CalendarConsultationHoursRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingDayExceptionRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursSettingRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.service.calendar.CalendarWorkingHoursService.Window;
import de.caritas.cob.userservice.api.service.calendar.FreeSlot.BookingMode;
import de.caritas.cob.userservice.api.service.calendar.FreeSlotGrid.Plan;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The time a counsellor can be booked for by an advice seeker (ORISO-Frontend#1050, #2287, ADR-020
 * §4): their Sprechstunden, the weekly windows they open for booking (not their working hours), cut
 * into slots of their slot length, without their days off (day exceptions without hours) and the
 * public holidays of their region, minus everything their own calendar holds: appointments of their
 * own and co-access conversations, requests waiting for them, their unopened direct invitations,
 * the group dates they own or take part in, blockers and imported busy times. A counsellor without
 * Sprechstunden offers no time at all.
 *
 * <p>Each slot is booked at once ({@link BookingMode#INSTANT}) when the counsellor accepts
 * automatically and it starts at least their notice period (in days) from now; otherwise booking it
 * sends a request the counsellor confirms ({@link BookingMode#REQUEST}). No slot starts before the
 * minimum lead time ({@code appointment.self-booking.lead-time}) or at or after the horizon. Every
 * read is bounded by the window and runs one query per source, whatever the number of slots.
 */
@Component
public class CounsellorFreeTime {

  private final CalendarConsultationHoursRepository consultationHours;
  private final CalendarBookingSettingRepository bookingSettings;
  private final CalendarWorkingHoursSettingRepository workingHoursSettings;
  private final CalendarWorkingDayExceptionRepository exceptions;
  private final CalendarEntryReader entries;
  private final SessionAppointmentRepository appointments;
  private final Clock clock;
  private final ZoneId zone;
  private final Duration leadTime;
  private final Duration step;
  private final Duration horizon;

  @Autowired
  public CounsellorFreeTime(
      CalendarConsultationHoursRepository consultationHours,
      CalendarBookingSettingRepository bookingSettings,
      CalendarWorkingHoursSettingRepository workingHoursSettings,
      CalendarWorkingDayExceptionRepository exceptions,
      CalendarEntryReader entries,
      SessionAppointmentRepository appointments,
      Clock clock,
      @Value("${calendar.zone:Europe/Berlin}") String zone,
      @Value("${appointment.self-booking.lead-time:PT2H}") Duration leadTime,
      @Value("${appointment.self-booking.step:PT30M}") Duration step,
      @Value("${appointment.self-booking.horizon:P14D}") Duration horizon) {
    this.consultationHours = consultationHours;
    this.bookingSettings = bookingSettings;
    this.workingHoursSettings = workingHoursSettings;
    this.exceptions = exceptions;
    this.entries = entries;
    this.appointments = appointments;
    this.clock = clock;
    this.zone = ZoneId.of(zone);
    this.leadTime = leadTime;
    this.step = step;
    this.horizon = horizon;
  }

  /** The earliest start a self-booking may have: now plus the minimum lead time. */
  public Instant earliestStart() {
    return Instant.now(clock).plus(leadTime);
  }

  /** The booking horizon: every offered or booked slot starts before now plus this. */
  public Instant latestStart() {
    return Instant.now(clock).plus(horizon);
  }

  /**
   * The free slots of the counsellor that start in [from, to), earliest first, never inside the
   * minimum lead time and never beyond the horizon, each with how it is booked.
   */
  public List<FreeSlot> slots(String consultantId, Instant from, Instant to) {
    var earliest = earliestStart();
    var latest = latestStart();
    if (to.isAfter(latest)) {
      to = latest;
    }
    var notBefore = earliest.isAfter(from) ? earliest : from;
    if (!notBefore.isBefore(to)) {
      return List.of();
    }
    var plan = planOf(consultantId);
    if (plan.weekly().isEmpty()) {
      return List.of();
    }
    var setting = settingOf(consultantId);
    var length = Duration.ofMinutes(setting.getSlotMinutes());
    var slotStep = step.compareTo(length) > 0 ? length : step;
    // A slot starting just before `to` runs past it; its busy time must be known as well.
    var busy = busyOf(consultantId, notBefore, to.plus(length), null);
    var instantFrom = Instant.now(clock).plus(Duration.ofDays(setting.getNoticeDays()));
    return FreeSlotGrid.slots(plan, busy, from, to, earliest, length, slotStep, zone).stream()
        .map(
            slot ->
                slot.withMode(
                    setting.isAutoAccept() && !slot.start().isBefore(instantFrom)
                        ? BookingMode.INSTANT
                        : BookingMode.REQUEST))
        .toList();
  }

  /** Exactly the slot [start, end) as it is offered right now, with its mode, if it is. */
  public Optional<FreeSlot> offeredSlot(String consultantId, Instant start, Instant end) {
    return slots(consultantId, start, start.plusNanos(1)).stream()
        .filter(slot -> slot.start().equals(start) && slot.end().equals(end))
        .findFirst();
  }

  /**
   * Whether nothing but the appointment itself takes the counsellor's time in [start, end): the
   * check before a counsellor confirms a request, whatever their Sprechstunden say now.
   */
  public boolean isFreeBesides(String consultantId, Instant start, Instant end, UUID appointment) {
    return busyOf(consultantId, start, end, appointment).stream()
        .noneMatch(taken -> taken.start().isBefore(end) && taken.end().isAfter(start));
  }

  private CalendarBookingSetting settingOf(String consultantId) {
    return bookingSettings
        .findById(consultantId)
        .orElseGet(() -> CalendarBookingSetting.defaults(consultantId));
  }

  /**
   * The Sprechstunden by weekday; the dates a day exception makes a day off have none. Exception
   * hours on other dates belong to the working hours and do not move the Sprechstunden.
   */
  private Plan planOf(String consultantId) {
    var weekly = new HashMap<Integer, List<Window>>();
    for (var row :
        consultationHours.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(consultantId)) {
      weekly
          .computeIfAbsent(row.getWeekday(), day -> new ArrayList<>())
          .add(new Window(row.getStartTime(), row.getEndTime()));
    }
    var daysOff = new HashMap<LocalDate, List<Window>>();
    var today = LocalDate.now(clock.withZone(zone));
    for (var row :
        exceptions.findByConsultantIdAndExceptionDateGreaterThanEqual(consultantId, today)) {
      if (row.isDayOff()) {
        daysOff.put(row.getExceptionDate(), List.of());
      }
    }
    var region =
        workingHoursSettings
            .findById(consultantId)
            .map(CalendarWorkingHoursSetting::getHolidayRegion)
            .orElse(null);
    return new Plan(weekly, daysOff, region);
  }

  /**
   * Everything that takes the counsellor's time in the window, but the appointment {@code besides}.
   * The asker's session settles who the counsellor is; their rows may lie in another Träger, as in
   * a colleague's calendar.
   */
  private List<BusySlot> busyOf(String consultantId, Instant from, Instant to, UUID besides) {
    var skip = besides == null ? null : besides.toString();
    return TenantContext.supplyAcrossTenants(
        () -> {
          var taken = new ArrayList<BusySlot>();
          var calendar = new ArrayList<CalendarEntry>();
          calendar.addAll(entries.appointmentsOfConsultant(consultantId, from, to));
          calendar.addAll(entries.groupOccurrencesOfConsultant(consultantId, from, to));
          calendar.addAll(entries.blockersOf(consultantId, from, to));
          for (var entry : calendar) {
            if (!entry.id().equals(skip)) {
              taken.add(new BusySlot(entry.start(), entry.end()));
            }
          }
          for (var request :
              appointments.findRequestedOfConsultantBetween(consultantId, from, to)) {
            if (!request.getId().equals(besides)) {
              taken.add(new BusySlot(request.getStartTime(), request.getEndTime()));
            }
          }
          return taken;
        });
  }
}
