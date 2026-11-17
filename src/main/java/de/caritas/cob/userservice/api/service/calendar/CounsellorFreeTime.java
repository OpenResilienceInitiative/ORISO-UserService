package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.model.CalendarWorkingHoursSetting;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingDayExceptionRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursSettingRepository;
import de.caritas.cob.userservice.api.service.calendar.CalendarWorkingHoursService.Window;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The time a counsellor can be booked for by an advice seeker (ORISO-Frontend#1050, ADR-020 §4):
 * their working hours (ADR-032 contract v1.1/v1.2, with day exceptions and the public holidays of
 * their region) minus everything their own calendar holds: appointments of their own and co-access
 * conversations, their unopened direct invitations, the group dates they own or take part in,
 * blockers and imported busy times. A counsellor without working hours offers no time at all.
 *
 * <p>Only slots that start at least the lead time from now are offered. ADR-020 §4 confirms such a
 * request automatically; a request inside the lead time would need the counsellor's approval, which
 * does not exist yet, so none is offered. Every read here is bounded by the window and runs one
 * query per source, whatever the number of slots.
 */
@Component
public class CounsellorFreeTime {

  private final CalendarWorkingHoursRepository workingHours;
  private final CalendarWorkingHoursSettingRepository settings;
  private final CalendarWorkingDayExceptionRepository exceptions;
  private final CalendarEntryReader entries;
  private final Clock clock;
  private final ZoneId zone;
  private final Duration leadTime;
  private final Duration slotLength;
  private final Duration step;

  @Autowired
  public CounsellorFreeTime(
      CalendarWorkingHoursRepository workingHours,
      CalendarWorkingHoursSettingRepository settings,
      CalendarWorkingDayExceptionRepository exceptions,
      CalendarEntryReader entries,
      Clock clock,
      @Value("${calendar.zone:Europe/Berlin}") String zone,
      @Value("${appointment.self-booking.lead-time:PT24H}") Duration leadTime,
      @Value("${appointment.self-booking.slot-length:PT60M}") Duration slotLength,
      @Value("${appointment.self-booking.step:PT30M}") Duration step) {
    this.workingHours = workingHours;
    this.settings = settings;
    this.exceptions = exceptions;
    this.entries = entries;
    this.clock = clock;
    this.zone = ZoneId.of(zone);
    this.leadTime = leadTime;
    this.slotLength = slotLength;
    this.step = step;
  }

  /** The free slots of the counsellor that start in [from, to), earliest first. */
  public List<FreeSlot> slots(String consultantId, Instant from, Instant to) {
    var earliest = Instant.now(clock).plus(leadTime);
    var notBefore = earliest.isAfter(from) ? earliest : from;
    if (!notBefore.isBefore(to)) {
      return List.of();
    }
    var plan = planOf(consultantId);
    if (plan.weekly().isEmpty() && plan.exceptions().isEmpty()) {
      return List.of();
    }
    // A slot starting just before `to` runs past it; its busy time must be known as well.
    var busy = busyOf(consultantId, notBefore, to.plus(slotLength));
    return FreeSlotGrid.slots(plan, busy, from, to, earliest, slotLength, step, zone);
  }

  /** Whether exactly [start, end) is one of the counsellor's free slots right now. */
  public boolean isFreeSlot(String consultantId, Instant start, Instant end) {
    return slots(consultantId, start, start.plusNanos(1)).stream()
        .anyMatch(slot -> slot.start().equals(start) && slot.end().equals(end));
  }

  private Plan planOf(String consultantId) {
    var weekly = new HashMap<Integer, List<Window>>();
    for (var row :
        workingHours.findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(consultantId)) {
      weekly
          .computeIfAbsent(row.getWeekday(), day -> new ArrayList<>())
          .add(new Window(row.getStartTime(), row.getEndTime()));
    }
    var byDate = new HashMap<LocalDate, List<Window>>();
    var today = LocalDate.now(clock.withZone(zone));
    for (var row :
        exceptions.findByConsultantIdAndExceptionDateGreaterThanEqual(consultantId, today)) {
      var windows = byDate.computeIfAbsent(row.getExceptionDate(), date -> new ArrayList<>());
      if (!row.isDayOff()) {
        windows.add(new Window(row.getStartTime(), row.getEndTime()));
      }
    }
    var region =
        settings
            .findById(consultantId)
            .map(CalendarWorkingHoursSetting::getHolidayRegion)
            .orElse(null);
    return new Plan(weekly, byDate, region);
  }

  /**
   * Everything that takes the counsellor's time in the window. The asker's session settles who the
   * counsellor is; their rows may lie in another Träger, as in a colleague's calendar.
   */
  private List<BusySlot> busyOf(String consultantId, Instant from, Instant to) {
    return TenantContext.supplyAcrossTenants(
        () -> {
          var taken = new ArrayList<CalendarEntry>();
          taken.addAll(entries.appointmentsOfConsultant(consultantId, from, to));
          taken.addAll(entries.groupOccurrencesOfConsultant(consultantId, from, to));
          taken.addAll(entries.blockersOf(consultantId, from, to));
          return taken.stream().map(entry -> new BusySlot(entry.start(), entry.end())).toList();
        });
  }
}
