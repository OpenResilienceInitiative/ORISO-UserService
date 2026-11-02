package de.caritas.cob.userservice.api.service.sessionappointment;

import de.caritas.cob.userservice.api.adapters.web.dto.SessionAppointmentCounterpartDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionAppointmentDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionAppointmentTimeDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.SessionAppointment.Status;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle of the 1:1 appointments of a conversation (ADR-020, ORISO-Frontend#1960). Works from
 * this service's own table alone: no cal.com, no AppointmentService, independent of {@code
 * feature.appointment.enabled}.
 *
 * <p>Only the counsellor assigned to the session creates and moves; that counsellor and the asker
 * read and cancel. Anybody else gets the same 404 as for a session that does not exist. No Matrix
 * notice is posted here: the backend holds no Megolm keys and cannot post into an encrypted room.
 */
@Service
@RequiredArgsConstructor
public class SessionAppointmentService {

  static final int SESSION_LIST_LIMIT = 100;
  static final int UPCOMING_LIMIT = 50;
  private static final String NOT_FOUND = "Session appointment not found.";
  private static final long LEGACY_TENANT = 1L;

  private final SessionAppointmentRepository appointments;
  private final SessionRepository sessions;
  private final AuthenticatedUser caller;
  private final UsernameTranscoder usernameTranscoder = new UsernameTranscoder();

  @Transactional(readOnly = true)
  public List<SessionAppointmentDTO> list(Long sessionId) {
    var session = sessionOfParticipant(sessionId);
    return appointments
        .findNewestOfSession(session.getId(), PageRequest.of(0, SESSION_LIST_LIMIT))
        .stream()
        .map(this::toDto)
        .toList();
  }

  @Transactional
  public SessionAppointmentDTO create(Long sessionId, SessionAppointmentTimeDTO time) {
    var session = sessionOfParticipant(sessionId);
    requireAssignedCounsellor(session);
    var appointment = new SessionAppointment();
    appointment.setSession(session);
    appointment.setStatus(Status.SCHEDULED);
    applyTime(appointment, time);
    return toDto(appointments.save(appointment));
  }

  @Transactional
  public SessionAppointmentDTO reschedule(
      Long sessionId, UUID appointmentId, SessionAppointmentTimeDTO time) {
    var session = sessionOfParticipant(sessionId);
    requireAssignedCounsellor(session);
    var appointment = appointmentOf(session, appointmentId);
    if (appointment.getStatus() == Status.CANCELLED) {
      throw new ConflictException("A cancelled appointment cannot be moved.");
    }
    applyTime(appointment, time);
    return toDto(appointment);
  }

  @Transactional
  public SessionAppointmentDTO cancel(Long sessionId, UUID appointmentId) {
    var session = sessionOfParticipant(sessionId);
    var appointment = appointmentOf(session, appointmentId);
    appointment.setStatus(Status.CANCELLED);
    return toDto(appointment);
  }

  @Transactional(readOnly = true)
  public List<SessionAppointmentDTO> upcoming() {
    var page = PageRequest.of(0, UPCOMING_LIMIT);
    var now = Instant.now();
    var rows =
        caller.isConsultant()
            ? appointments.findUpcomingOfConsultant(caller.getUserId(), now, page)
            : appointments.findUpcomingOfAsker(caller.getUserId(), now, page);
    return rows.stream().map(this::toDto).toList();
  }

  private Session sessionOfParticipant(Long sessionId) {
    return sessions
        .findById(sessionId)
        .filter(this::isInCallersTenant)
        .filter(found -> isAssignedCounsellor(found) || isAsker(found))
        .orElseThrow(() -> new NotFoundException(NOT_FOUND));
  }

  private boolean isInCallersTenant(Session session) {
    var callerTenant = caller.getTenantId();
    if (callerTenant == null) {
      return true;
    }
    var sessionTenant = session.getTenantId() == null ? LEGACY_TENANT : session.getTenantId();
    return callerTenant.equals(sessionTenant);
  }

  private boolean isAssignedCounsellor(Session session) {
    return caller.isConsultant()
        && session.getConsultant() != null
        && Objects.equals(session.getConsultant().getId(), caller.getUserId());
  }

  private boolean isAsker(Session session) {
    return !caller.isConsultant()
        && Objects.equals(session.getUser().getUserId(), caller.getUserId());
  }

  private void requireAssignedCounsellor(Session session) {
    if (!isAssignedCounsellor(session)) {
      throw new ForbiddenException("Only the assigned counsellor schedules appointments.");
    }
  }

  private SessionAppointment appointmentOf(Session session, UUID appointmentId) {
    return appointments
        .findOfSessionForUpdate(appointmentId, session.getId())
        .orElseThrow(() -> new NotFoundException(NOT_FOUND));
  }

  private void applyTime(SessionAppointment appointment, SessionAppointmentTimeDTO time) {
    var start = time.getStartTime().toInstant();
    var end = time.getEndTime().toInstant();
    if (!end.isAfter(start)) {
      throw new BadRequestException("The end of an appointment must be after its start.");
    }
    if (!start.isAfter(Instant.now())) {
      throw new BadRequestException("An appointment must start in the future.");
    }
    appointment.setStartTime(start);
    appointment.setEndTime(end);
  }

  private SessionAppointmentDTO toDto(SessionAppointment appointment) {
    return new SessionAppointmentDTO()
        .id(appointment.getId())
        .sessionId(appointment.getSession().getId())
        .startTime(appointment.getStartTime().atOffset(ZoneOffset.UTC))
        .endTime(appointment.getEndTime().atOffset(ZoneOffset.UTC))
        .status(SessionAppointmentDTO.StatusEnum.fromValue(appointment.getStatus().name()))
        .counterpart(counterpartOf(appointment.getSession()));
  }

  private SessionAppointmentCounterpartDTO counterpartOf(Session session) {
    if (caller.isConsultant()) {
      return new SessionAppointmentCounterpartDTO()
          .username(usernameTranscoder.decodeUsername(session.getUser().getUsername()));
    }
    var consultant = session.getConsultant();
    return consultant == null
        ? null
        : new SessionAppointmentCounterpartDTO()
            .username(usernameTranscoder.decodeUsername(consultant.getUsername()))
            .displayName(consultant.getDisplayName());
  }
}
