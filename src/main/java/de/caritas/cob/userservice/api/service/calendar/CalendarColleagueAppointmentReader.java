package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryType;
import de.caritas.cob.userservice.api.model.CalendarColleagueAppointment;
import de.caritas.cob.userservice.api.model.CalendarColleagueAppointment.Status;
import de.caritas.cob.userservice.api.port.out.CalendarColleagueAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads the appointments between two counsellors (ORISO-Frontend#2305) as calendar entries of one
 * of them. It decides nothing about who may see what; {@link CalendarService} does.
 */
@Component
@RequiredArgsConstructor
public class CalendarColleagueAppointmentReader {

  private static final Set<Status> SHOWN = Set.of(Status.PENDING, Status.CONFIRMED);
  private static final Set<Status> FIXED = Set.of(Status.CONFIRMED);

  private final CalendarColleagueAppointmentRepository appointments;
  private final ConsultantRepository consultants;

  /** The appointments of {@code consultantId}; with {@code confirmedOnly} the fixed ones only. */
  List<CalendarEntry> entriesOf(
      String consultantId, Instant from, Instant to, boolean confirmedOnly) {
    // The other counsellor may belong to another Träger where the calendar reach allows it.
    List<CalendarColleagueAppointment> found =
        TenantContext.supplyAcrossTenants(
            () ->
                appointments.findOverlapping(
                    consultantId, confirmedOnly ? FIXED : SHOWN, from, to));
    var names = new HashMap<String, String>();
    return found.stream().map(a -> entryOf(a, consultantId, names)).toList();
  }

  private CalendarEntry entryOf(
      CalendarColleagueAppointment appointment, String consultantId, Map<String, String> names) {
    var other = appointment.otherThan(consultantId);
    return entryOf(appointment, consultantId, names.computeIfAbsent(other, this::nameOf));
  }

  /** One appointment as the calendar of {@code consultantId} shows it; the other side named. */
  static CalendarEntry entryOf(
      CalendarColleagueAppointment appointment, String consultantId, String otherName) {
    return new CalendarEntry(
        appointment.getId().toString(),
        CalendarEntryType.APPOINTMENT,
        appointment.getStartTime(),
        appointment.getEndTime(),
        appointment.getKind(),
        otherName,
        null,
        null,
        false,
        new CalendarEntry.Meeting(
            appointment.otherThan(consultantId),
            otherName,
            appointment.getStatus() == Status.CONFIRMED,
            appointment.getInviteeId().equals(consultantId),
            appointment.getMatrixRoomId(),
            appointment.getChatId()));
  }

  private String nameOf(String consultantId) {
    return TenantContext.supplyAcrossTenants(
            () -> consultants.findByIdAndDeleteDateIsNull(consultantId))
        .map(CalendarColleagueScope::nameOf)
        .orElse("");
  }
}
