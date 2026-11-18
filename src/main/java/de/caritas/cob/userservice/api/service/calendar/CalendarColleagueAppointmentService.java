package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarAutoAcceptDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ColleagueAppointmentCreateDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.InternalSubsetChatDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.InternalSubsetChatFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarAutoAccept;
import de.caritas.cob.userservice.api.model.CalendarColleagueAppointment;
import de.caritas.cob.userservice.api.model.CalendarColleagueAppointment.Status;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.port.out.CalendarAutoAcceptRepository;
import de.caritas.cob.userservice.api.port.out.CalendarColleagueAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appointments between two counsellors (ORISO-Frontend#2305, Frank's decisions of 2026-11-17).
 *
 * <ul>
 *   <li>Every kind is allowed; the appointment belongs to the private internal chat of the two,
 *       which is opened (or reused) through {@link InternalSubsetChatFacade}.
 *   <li>The colleague must be inside the caller's calendar reach ({@link CalendarColleagueScope}).
 *       Where the Träger's internal-chat reach does not include them, only an on-site appointment
 *       is possible, without a chat.
 *   <li>It is fixed at once when the colleague accepts the caller automatically ({@code
 *       calendar_auto_accept}); otherwise it waits for the colleague's answer.
 *   <li>Either of the two may cancel it; only the invited one answers.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CalendarColleagueAppointmentService {

  static final int MAX_AUTO_ACCEPT = 200;
  private static final String NOT_FOUND = "Appointment not found.";

  private final AuthenticatedUser caller;
  private final CalendarColleagueAppointmentRepository appointments;
  private final CalendarAutoAcceptRepository autoAccept;
  private final CalendarColleagueScope colleagues;
  private final ConsultantRepository consultants;
  private final InternalSubsetChatFacade rooms;
  private final Clock clock;

  /** Makes the appointment and opens the internal chat of the two. */
  @Transactional
  public CalendarEntryDTO create(ColleagueAppointmentCreateDTO request) {
    requireConsultant();
    var me = caller.getUserId();
    if (request.getStart() == null || request.getEnd() == null || request.getKind() == null) {
      throw new BadRequestException("An appointment names a start, an end and a kind.");
    }
    var start = request.getStart().toInstant();
    var end = request.getEnd().toInstant();
    if (!end.isAfter(start)) {
      throw new BadRequestException("The end of an appointment must be after its start.");
    }
    if (!start.isAfter(Instant.now(clock))) {
      throw new BadRequestException("An appointment must start in the future.");
    }
    if (request.getColleagueId() == null || request.getColleagueId().equals(me)) {
      throw new BadRequestException("An appointment with a colleague names another counsellor.");
    }
    var colleague =
        colleagues
            .find(me, request.getColleagueId())
            .orElseThrow(() -> new NotFoundException("Colleague not found."));
    var creator =
        consultants
            .findByIdAndDeleteDateIsNull(me)
            .orElseThrow(() -> new ForbiddenException("The caller is no active counsellor."));
    var kind = SessionAppointment.Kind.valueOf(request.getKind().name());
    var withChat =
        rooms
            .reachableColleagues(creator, List.of(colleague.consultant()))
            .contains(colleague.consultant().getId());
    // Without an internal chat only an on-site appointment is possible (Frank, 2026-11-17).
    if (!withChat && kind != SessionAppointment.Kind.ONSITE) {
      throw new ForbiddenException(
          "Without an internal chat with this colleague only an on-site appointment is possible.");
    }
    var appointment = new CalendarColleagueAppointment();
    appointment.setOrganizerId(me);
    appointment.setInviteeId(colleague.consultant().getId());
    appointment.setStartTime(start);
    appointment.setEndTime(end);
    appointment.setKind(kind);
    var acceptsMe =
        TenantContext.supplyAcrossTenants(
            () ->
                autoAccept.existsByConsultantIdAndColleagueId(colleague.consultant().getId(), me));
    appointment.setStatus(Boolean.TRUE.equals(acceptsMe) ? Status.CONFIRMED : Status.PENDING);
    appointment.setCreatedAt(Instant.now(clock));
    // The Matrix room is the one step a rollback cannot undo, so it comes last, after every check
    // and read. Opening is find-or-create: should the save still fail, a retry reuses the room.
    if (withChat) {
      var room =
          rooms.open(
              new InternalSubsetChatDTO().consultantIds(List.of(colleague.consultant().getId())),
              creator);
      appointment.setChatId(room.chatId());
      appointment.setMatrixRoomId(room.matrixRoomId());
    }
    var saved = appointments.save(appointment);
    return view(saved, me, colleague.name());
  }

  /** The invited counsellor fixes the appointment. */
  @Transactional
  public CalendarEntryDTO accept(UUID appointmentId) {
    var appointment = invitationOfCaller(appointmentId);
    appointment.setStatus(Status.CONFIRMED);
    appointments.save(appointment);
    return view(appointment, caller.getUserId(), nameOf(appointment.getOrganizerId()));
  }

  /** The invited counsellor turns the appointment down; it leaves both calendars. */
  @Transactional
  public void decline(UUID appointmentId) {
    var appointment = invitationOfCaller(appointmentId);
    appointment.setStatus(Status.DECLINED);
    appointments.save(appointment);
  }

  /** Either of the two cancels; it leaves both calendars. */
  @Transactional
  public void cancel(UUID appointmentId) {
    requireConsultant();
    var appointment = ofCaller(appointmentId);
    if (appointment.getStatus() == Status.CANCELLED || appointment.getStatus() == Status.DECLINED) {
      return;
    }
    appointment.setStatus(Status.CANCELLED);
    appointments.save(appointment);
  }

  /** The colleagues whose appointments the caller accepts automatically, by id. */
  @Transactional(readOnly = true)
  public CalendarAutoAcceptDTO autoAccept() {
    requireConsultant();
    return new CalendarAutoAcceptDTO()
        .colleagueIds(
            autoAccept.findByConsultantId(caller.getUserId()).stream()
                .map(CalendarAutoAccept::getColleagueId)
                .sorted()
                .toList());
  }

  /** Replaces the list; every colleague must be inside the caller's calendar reach. */
  @Transactional
  public CalendarAutoAcceptDTO replaceAutoAccept(CalendarAutoAcceptDTO request) {
    requireConsultant();
    var me = caller.getUserId();
    var ids = new LinkedHashSet<>(Objects.requireNonNullElse(request.getColleagueIds(), List.of()));
    if (ids.size() > MAX_AUTO_ACCEPT) {
      throw new BadRequestException("At most " + MAX_AUTO_ACCEPT + " colleagues.");
    }
    for (var id : ids) {
      if (id == null || colleagues.find(me, id).isEmpty()) {
        throw new BadRequestException("Every colleague must be inside the calendar reach.");
      }
    }
    autoAccept.deleteByConsultantId(me);
    autoAccept.saveAll(
        ids.stream()
            .map(
                id -> {
                  var row = new CalendarAutoAccept();
                  row.setConsultantId(me);
                  row.setColleagueId(id);
                  return row;
                })
            .toList());
    return autoAccept();
  }

  private CalendarColleagueAppointment invitationOfCaller(UUID appointmentId) {
    requireConsultant();
    var appointment = ofCaller(appointmentId);
    if (!appointment.getInviteeId().equals(caller.getUserId())) {
      throw new NotFoundException(NOT_FOUND);
    }
    if (appointment.getStatus() != Status.PENDING) {
      throw new ConflictException("The appointment no longer waits for an answer.");
    }
    return appointment;
  }

  /** An appointment of the caller; another's answers like one that does not exist. */
  private CalendarColleagueAppointment ofCaller(UUID appointmentId) {
    return TenantContext.supplyAcrossTenants(() -> appointments.findByIdForUpdate(appointmentId))
        .filter(appointment -> appointment.involves(caller.getUserId()))
        .orElseThrow(() -> new NotFoundException(NOT_FOUND));
  }

  private String nameOf(String consultantId) {
    return TenantContext.supplyAcrossTenants(
            () -> consultants.findByIdAndDeleteDateIsNull(consultantId))
        .map(CalendarColleagueScope::nameOf)
        .orElse("");
  }

  private static CalendarEntryDTO view(
      CalendarColleagueAppointment appointment, String consultantId, String otherName) {
    return CalendarService.own(
        CalendarColleagueAppointmentReader.entryOf(appointment, consultantId, otherName),
        consultantId,
        false);
  }

  private void requireConsultant() {
    if (!caller.isConsultant()) {
      throw new ForbiddenException("Only a counsellor makes appointments with colleagues.");
    }
  }
}
