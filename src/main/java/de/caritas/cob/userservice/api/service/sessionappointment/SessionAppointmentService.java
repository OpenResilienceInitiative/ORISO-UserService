package de.caritas.cob.userservice.api.service.sessionappointment;

import de.caritas.cob.userservice.api.adapters.web.dto.AppointmentKind;
import de.caritas.cob.userservice.api.adapters.web.dto.AppointmentSlotDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionAppointmentCounterpartDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionAppointmentDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionAppointmentTimeDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.SessionAppointment.Status;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverService;
import de.caritas.cob.userservice.api.service.calendar.CounsellorFreeTime;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle of the 1:1 appointments of a conversation (ADR-020, ORISO-Frontend#1960). Works from
 * this service's own table alone.
 *
 * <p>The responsible counsellors are the session's counsellor and whoever holds a live co-access
 * grant through a case handover. They create and move; they and the asker read and cancel. Anybody
 * else gets the same 404 as for a session that does not exist. A finished or archived session
 * refuses create and move with 409; cancel stays allowed. No Matrix notice is posted here: the
 * backend holds no Megolm keys and cannot post into an encrypted room.
 *
 * <p>Self-booking (ORISO-Frontend#1050, ADR-020 §4): the asker of a running conversation reads the
 * free slots of its counsellor and books one of them as a video or phone appointment. The slot is
 * checked again while the counsellor's row is locked, so two askers never get the same time. The
 * counsellor learns of it like the asker learns of a counsellor's appointment: from the notice the
 * Frontend posts into the conversation, and from the appointment in their calendar.
 */
@Service
@RequiredArgsConstructor
public class SessionAppointmentService {

  static final int SESSION_LIST_LIMIT = 100;
  static final int UPCOMING_LIMIT = 50;
  private static final String NOT_FOUND = "Session appointment not found.";
  private static final long LEGACY_TENANT = 1L;
  private static final Set<SessionStatus> FINISHED =
      Set.of(SessionStatus.DONE, SessionStatus.IN_ARCHIVE);
  static final Duration MAX_SLOT_WINDOW = Duration.ofDays(14);
  private static final Set<AppointmentKind> SELF_BOOKABLE =
      Set.of(AppointmentKind.VIDEO, AppointmentKind.PHONE);

  private final SessionAppointmentRepository appointments;
  private final SessionRepository sessions;
  private final AuthenticatedUser caller;
  private final CaseHandoverService caseHandovers;
  private final CounsellorFreeTime freeTime;
  private final ConsultantRepository consultants;
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
    requireResponsibleCounsellor(session);
    requireNotFinished(session);
    var appointment = new SessionAppointment();
    appointment.setSession(session);
    appointment.setStatus(Status.SCHEDULED);
    applyTime(appointment, time);
    applyKind(appointment, time);
    return toDto(appointments.save(appointment));
  }

  @Transactional
  public SessionAppointmentDTO reschedule(
      Long sessionId, UUID appointmentId, SessionAppointmentTimeDTO time) {
    var session = sessionOfParticipant(sessionId);
    requireResponsibleCounsellor(session);
    requireNotFinished(session);
    var appointment = appointmentOf(session, appointmentId);
    if (appointment.getStatus() == Status.CANCELLED) {
      throw new ConflictException("A cancelled appointment cannot be moved.");
    }
    applyTime(appointment, time);
    applyKind(appointment, time);
    return toDto(appointment);
  }

  @Transactional
  public SessionAppointmentDTO cancel(Long sessionId, UUID appointmentId) {
    var session = sessionOfParticipant(sessionId);
    var appointment = appointmentOf(session, appointmentId);
    appointment.setStatus(Status.CANCELLED);
    return toDto(appointment);
  }

  /** The slots the asker may book with the counsellor of the session, earliest first. */
  @Transactional(readOnly = true)
  public List<AppointmentSlotDTO> freeSlots(
      Long sessionId, OffsetDateTime from, OffsetDateTime to) {
    if (from == null || to == null || !from.isBefore(to)) {
      throw new BadRequestException("The slot window must start before it ends.");
    }
    if (Duration.between(from, to).compareTo(MAX_SLOT_WINDOW) > 0) {
      throw new BadRequestException("The slot window must not exceed 14 days.");
    }
    var session = selfBookableSession(sessionId);
    return freeTime
        .slots(session.getConsultant().getId(), from.toInstant(), to.toInstant())
        .stream()
        .map(
            slot ->
                new AppointmentSlotDTO()
                    .startTime(slot.start().atOffset(ZoneOffset.UTC))
                    .endTime(slot.end().atOffset(ZoneOffset.UTC)))
        .toList();
  }

  /**
   * Books one offered slot for the asker. The counsellor's row is locked first, so bookings with
   * one counsellor run one after another; the slot is then checked against the free time and, with
   * a locking read, against the appointments another booking committed meanwhile.
   */
  @Transactional
  public SessionAppointmentDTO book(Long sessionId, SessionAppointmentTimeDTO time) {
    var session = selfBookableSession(sessionId);
    if (time.getKind() == null || !SELF_BOOKABLE.contains(time.getKind())) {
      throw new BadRequestException("A booked appointment takes place by video or phone.");
    }
    var start = time.getStartTime().toInstant();
    var end = time.getEndTime().toInstant();
    if (!end.isAfter(start)) {
      throw new BadRequestException("The end of an appointment must be after its start.");
    }
    if (start.isBefore(freeTime.earliestStart()) || !start.isBefore(freeTime.latestStart())) {
      throw new ConflictException(
          "The start lies inside the lead time or beyond the booking horizon.",
          HttpStatusExceptionReason.SLOT_OUTSIDE_BOOKING_WINDOW);
    }
    var consultantId = session.getConsultant().getId();
    consultants.lockConsultant(consultantId);
    // One upcoming appointment per conversation: an asker cannot flood the counsellor's calendar.
    if (!appointments.lockUpcomingOfSession(session.getId(), Instant.now()).isEmpty()) {
      throw new ConflictException(
          "This conversation already has an upcoming appointment.",
          HttpStatusExceptionReason.SELF_BOOKING_LIMIT_REACHED);
    }
    if (!freeTime.isFreeSlot(consultantId, start, end)
        || !appointments.lockScheduledOfConsultantBetween(consultantId, start, end).isEmpty()) {
      throw new ConflictException("This time is not free any more.");
    }
    var appointment = new SessionAppointment();
    appointment.setSession(session);
    appointment.setStatus(Status.SCHEDULED);
    appointment.setStartTime(start);
    appointment.setEndTime(end);
    appointment.setKind(SessionAppointment.Kind.valueOf(time.getKind().name()));
    return toDto(appointments.save(appointment));
  }

  @Transactional(readOnly = true)
  public List<SessionAppointmentDTO> upcoming() {
    var page = PageRequest.of(0, UPCOMING_LIMIT);
    var now = Instant.now();
    var rows =
        caller.isConsultant()
            ? appointments.findUpcomingOfConsultant(
                caller.getUserId(),
                caseHandovers.sessionIdsWithActiveCoAccess(caller.getUserId()),
                now,
                page)
            : appointments.findUpcomingOfAsker(caller.getUserId(), now, page);
    return rows.stream().map(this::toDto).toList();
  }

  private Session sessionOfParticipant(Long sessionId) {
    return sessions
        .findById(sessionId)
        .filter(this::isInCallersTenant)
        .filter(found -> isResponsibleCounsellor(found) || isAsker(found))
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

  private boolean isResponsibleCounsellor(Session session) {
    if (!caller.isConsultant()) {
      return false;
    }
    return (session.getConsultant() != null
            && Objects.equals(session.getConsultant().getId(), caller.getUserId()))
        || caseHandovers.hasActiveCoAccess(session.getId(), caller.getUserId());
  }

  private boolean isAsker(Session session) {
    return !caller.isConsultant()
        && Objects.equals(session.getUser().getUserId(), caller.getUserId());
  }

  private void requireResponsibleCounsellor(Session session) {
    if (!isResponsibleCounsellor(session)) {
      throw new ForbiddenException("Only a responsible counsellor schedules appointments.");
    }
  }

  /**
   * The session of the calling asker, running and with a counsellor. Strangers get the 404 of a
   * session that does not exist, its counsellors a 403: they schedule, they do not self-book.
   */
  private Session selfBookableSession(Long sessionId) {
    var session = sessionOfParticipant(sessionId);
    if (!isAsker(session)) {
      throw new ForbiddenException("Only the asker of the conversation books a free slot.");
    }
    if (session.getStatus() != SessionStatus.IN_PROGRESS || session.getConsultant() == null) {
      throw new ConflictException("Only a running conversation with a counsellor takes bookings.");
    }
    return session;
  }

  private void requireNotFinished(Session session) {
    if (FINISHED.contains(session.getStatus())) {
      throw new ConflictException("A finished conversation takes no new or moved appointments.");
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

  /** A request without a kind leaves the stored one alone, so a plain move keeps it. */
  private void applyKind(SessionAppointment appointment, SessionAppointmentTimeDTO time) {
    if (time.getKind() != null) {
      appointment.setKind(SessionAppointment.Kind.valueOf(time.getKind().name()));
    }
  }

  private SessionAppointmentDTO toDto(SessionAppointment appointment) {
    return new SessionAppointmentDTO()
        .id(appointment.getId())
        .sessionId(appointment.getSession().getId())
        .startTime(appointment.getStartTime().atOffset(ZoneOffset.UTC))
        .endTime(appointment.getEndTime().atOffset(ZoneOffset.UTC))
        .status(SessionAppointmentDTO.StatusEnum.fromValue(appointment.getStatus().name()))
        .kind(
            appointment.getKind() == null
                ? null
                : AppointmentKind.valueOf(appointment.getKind().name()))
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
