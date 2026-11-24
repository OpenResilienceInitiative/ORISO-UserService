package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryType;
import de.caritas.cob.userservice.api.model.CalendarBookingSetting;
import de.caritas.cob.userservice.api.model.CalendarConsultationOnce;
import de.caritas.cob.userservice.api.model.CalendarWorkingHoursSetting;
import de.caritas.cob.userservice.api.port.out.CalendarBookingSettingRepository;
import de.caritas.cob.userservice.api.port.out.CalendarConsultationHoursRepository;
import de.caritas.cob.userservice.api.port.out.CalendarConsultationOnceRepository;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The time a counsellor can be booked for by an advice seeker (ORISO-Frontend#1050, #2287, #2494,
 * ADR-020 §4): their Sprechstunden, the weekly windows they open for booking (not their working
 * hours), without their days off (day exceptions without hours) and the public holidays of their
 * region, plus their one-off Sprechstunden, single dated windows released once; both cut into slots
 * of their slot length on the same step, minus everything their own calendar holds: appointments of
 * their own and co-access conversations, requests waiting for them, their unopened direct
 * invitations, the group dates they own or take part in, blockers and imported busy times. A
 * counsellor with neither offers no time at all.
 *
 * <p>A weekly slot is booked at once ({@link BookingMode#INSTANT}) when the counsellor accepts
 * automatically and it starts at least their notice period (in days) from now; otherwise booking it
 * sends a request the counsellor confirms ({@link BookingMode#REQUEST}). No weekly slot starts
 * before the minimum lead time ({@code appointment.self-booking.lead-time}).
 *
 * <p>A one-off is an explicit release at short notice: days off and public holidays do not remove
 * it, its slots start no earlier than its own notice in minutes from now (in place of the minimum
 * lead time), and they are booked at once when the counsellor accepts automatically, else as a
 * request (the notice period in days does not apply). A one-off released over the counsellor's
 * Sperrzeit ({@code overBlocker}) is not taken by their own blockers; everything else still takes
 * it. A weekly and a one-off slot of the same start and end are offered once, as the one-off's.
 *
 * <p>No slot starts at or after the horizon or before the window. Every read is bounded by the
 * window and runs one query per source, whatever the number of slots.
 */
@Component
public class CounsellorFreeTime {

  private final CalendarConsultationHoursRepository consultationHours;
  private final CalendarConsultationOnceRepository consultationOnce;
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
      CalendarConsultationOnceRepository consultationOnce,
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
    this.consultationOnce = consultationOnce;
    this.bookingSettings = bookingSettings;
    this.workingHoursSettings = workingHoursSettings;
    this.exceptions = exceptions;
    this.entries = entries;
    this.appointments = appointments;
    this.clock = clock;
    this.zone = ZoneId.of(zone);
    this.leadTime = leadTime;
    // A step that does not move forward never leaves a working window: fail at startup instead.
    if (step == null || step.isZero() || step.isNegative()) {
      throw new IllegalArgumentException(
          "appointment.self-booking.step must be a positive duration, got " + step);
    }
    this.step = step;
    this.horizon = horizon;
  }

  /**
   * The earliest start a self-booking of a weekly Sprechstunde may have: now plus the minimum lead
   * time. A slot of a one-off may start earlier, see {@link #isInsideBookingWindow}.
   */
  public Instant earliestStart() {
    return Instant.now(clock).plus(leadTime);
  }

  /**
   * Whether a booking of [start, end) lies inside the window slots are offered in: before the
   * horizon, and after the minimum lead time or, inside a one-off of the counsellor, after that
   * one-off's own notice. Whether it is an offered slot is {@link #offeredSlot}'s question.
   */
  public boolean isInsideBookingWindow(String consultantId, Instant start, Instant end) {
    if (!start.isBefore(latestStart())) {
      return false;
    }
    if (!start.isBefore(earliestStart())) {
      return true;
    }
    var now = Instant.now(clock);
    return consultationOnce.findOverlapping(consultantId, start, end).stream()
        .anyMatch(
            once ->
                !start.isBefore(once.getStartAt())
                    && !end.isAfter(once.getEndAt())
                    && !start.isBefore(now.plus(Duration.ofMinutes(once.getNoticeMinutes()))));
  }

  /** The booking horizon: every offered or booked slot starts before now plus this. */
  public Instant latestStart() {
    return Instant.now(clock).plus(horizon);
  }

  /**
   * The free slots of the counsellor that start in [from, to), earliest first, never inside the
   * minimum lead time (a one-off's: its own notice) and never beyond the horizon, each with how it
   * is booked.
   */
  public List<FreeSlot> slots(String consultantId, Instant from, Instant to) {
    return slots(consultantId, from, to, settingOf(consultantId));
  }

  private List<FreeSlot> slots(
      String consultantId, Instant from, Instant to, CalendarBookingSetting setting) {
    var now = Instant.now(clock);
    var earliest = earliestStart();
    var latest = latestStart();
    if (to.isAfter(latest)) {
      to = latest;
    }
    // A one-off may start right now (notice 0); nothing starts in the past.
    var onceFrom = now.isAfter(from) ? now : from;
    if (!onceFrom.isBefore(to)) {
      return List.of();
    }
    var onces = consultationOnce.findOverlapping(consultantId, onceFrom, to);
    var weeklyFrom = earliest.isAfter(from) ? earliest : from;
    var plan = weeklyFrom.isBefore(to) ? planOf(consultantId) : null;
    var weekly = plan != null && !plan.weekly().isEmpty();
    if (!weekly && onces.isEmpty()) {
      return List.of();
    }
    var length = Duration.ofMinutes(setting.getSlotMinutes());
    var slotStep = step.compareTo(length) > 0 ? length : step;
    // A slot starting just before `to` runs past it; its busy time must be known as well.
    var busy = busyOf(consultantId, onces.isEmpty() ? weeklyFrom : onceFrom, to.plus(length), null);
    var offered = new LinkedHashMap<List<Instant>, FreeSlot>();
    if (weekly) {
      var instantFrom = now.plus(Duration.ofDays(setting.getNoticeDays()));
      for (var slot :
          FreeSlotGrid.slots(plan, busy.all(), from, to, earliest, length, slotStep, zone)) {
        offered.put(
            List.of(slot.start(), slot.end()),
            slot.withMode(
                setting.isAutoAccept() && !slot.start().isBefore(instantFrom)
                    ? BookingMode.INSTANT
                    : BookingMode.REQUEST));
      }
    }
    var onceMode = setting.isAutoAccept() ? BookingMode.INSTANT : BookingMode.REQUEST;
    for (var once : onces) {
      var taken = once.isOverBlocker() ? busy.besidesOwnBlockers() : busy.all();
      var notice = now.plus(Duration.ofMinutes(once.getNoticeMinutes()));
      for (var slot : onceSlotsOf(once, taken, from, to, notice, length, slotStep)) {
        // The one-off is the explicit release: its mode wins over a weekly slot of the same time.
        offered.put(List.of(slot.start(), slot.end()), slot.withMode(onceMode));
      }
    }
    return offered.values().stream()
        .sorted(Comparator.comparing(FreeSlot::start).thenComparing(FreeSlot::end))
        .toList();
  }

  private static List<FreeSlot> onceSlotsOf(
      CalendarConsultationOnce once,
      List<BusySlot> taken,
      Instant from,
      Instant to,
      Instant notice,
      Duration length,
      Duration step) {
    return FreeSlotGrid.onceSlots(
        once.getStartAt(), once.getEndAt(), taken, from, to, notice, length, step);
  }

  /**
   * Exactly the slot [start, end) as it is offered right now, with its mode, if it is. Called by a
   * booking that holds the counsellor's row: the booking setting is read with a lock, so it is the
   * one the counsellor committed last, also after the booking's snapshot was taken (their settings
   * change under the same row lock).
   */
  public Optional<FreeSlot> offeredSlot(String consultantId, Instant start, Instant end) {
    var setting =
        bookingSettings
            .lockById(consultantId)
            .orElseGet(() -> CalendarBookingSetting.defaults(consultantId));
    return slots(consultantId, start, start.plusNanos(1), setting).stream()
        .filter(slot -> slot.start().equals(start) && slot.end().equals(end))
        .findFirst();
  }

  /**
   * Whether nothing but the appointment itself takes the counsellor's time in [start, end): the
   * check before a counsellor confirms a request, whatever their Sprechstunden say now. Inside a
   * one-off released over their own blockers ({@code overBlocker}), those blockers do not take the
   * time, as when its slots were offered; everything else still does.
   */
  public boolean isFreeBesides(String consultantId, Instant start, Instant end, UUID appointment) {
    var busy = busyOf(consultantId, start, end, appointment);
    if (busy.besidesOwnBlockers().stream().anyMatch(taken -> overlaps(taken, start, end))) {
      return false;
    }
    var ownBlockers =
        busy.ownBlockers().stream().filter(taken -> overlaps(taken, start, end)).toList();
    if (ownBlockers.isEmpty()) {
      return true;
    }
    var releasedOver =
        consultationOnce.findOverlapping(consultantId, start, end).stream()
            .filter(CalendarConsultationOnce::isOverBlocker)
            .sorted(Comparator.comparing(CalendarConsultationOnce::getStartAt))
            .toList();
    return ownBlockers.stream()
        .allMatch(
            blocker ->
                isCovered(
                    later(blocker.start(), start), earlier(blocker.end(), end), releasedOver));
  }

  private static boolean overlaps(BusySlot taken, Instant start, Instant end) {
    return taken.start().isBefore(end) && taken.end().isAfter(start);
  }

  /** Whether the one-offs, sorted by start, cover [from, to) without a gap. */
  private static boolean isCovered(
      Instant from, Instant to, List<CalendarConsultationOnce> sortedOnces) {
    var cursor = from;
    for (var once : sortedOnces) {
      if (!cursor.isBefore(to)) {
        break;
      }
      if (!once.getStartAt().isAfter(cursor) && once.getEndAt().isAfter(cursor)) {
        cursor = once.getEndAt();
      }
    }
    return !cursor.isBefore(to);
  }

  private static Instant later(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }

  private static Instant earlier(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
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
   * Everything that takes the counsellor's time in the window, but the appointment {@code besides};
   * the counsellor's own blockers also apart, for a one-off released over them. The asker's session
   * settles who the counsellor is; their rows may lie in another Träger, as in a colleague's
   * calendar.
   */
  private Busy busyOf(String consultantId, Instant from, Instant to, UUID besides) {
    var skip = besides == null ? null : besides.toString();
    return TenantContext.supplyAcrossTenants(
        () -> {
          var all = new ArrayList<BusySlot>();
          var besidesOwnBlockers = new ArrayList<BusySlot>();
          var ownBlockers = new ArrayList<BusySlot>();
          var calendar = new ArrayList<CalendarEntry>();
          calendar.addAll(entries.appointmentsOfConsultant(consultantId, from, to));
          calendar.addAll(entries.groupOccurrencesOfConsultant(consultantId, from, to));
          calendar.addAll(entries.blockersOf(consultantId, from, to));
          for (var entry : calendar) {
            if (!entry.id().equals(skip)) {
              var taken = new BusySlot(entry.start(), entry.end());
              all.add(taken);
              if (entry.type() == CalendarEntryType.BLOCKER) {
                ownBlockers.add(taken);
              } else {
                besidesOwnBlockers.add(taken);
              }
            }
          }
          for (var request :
              appointments.findRequestedOfConsultantBetween(consultantId, from, to)) {
            if (!request.getId().equals(besides)) {
              var taken = new BusySlot(request.getStartTime(), request.getEndTime());
              all.add(taken);
              besidesOwnBlockers.add(taken);
            }
          }
          return new Busy(all, besidesOwnBlockers, ownBlockers);
        });
  }

  /**
   * The counsellor's busy time: all of it, all but their own blockers (imported busy times stay),
   * and their own blockers alone.
   */
  private record Busy(
      List<BusySlot> all, List<BusySlot> besidesOwnBlockers, List<BusySlot> ownBlockers) {}
}
