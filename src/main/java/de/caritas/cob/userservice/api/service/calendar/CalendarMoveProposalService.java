package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarMoveProposalCreateDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarMoveProposalDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarMoveProposalsDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionAppointmentTimeDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarMoveProposal;
import de.caritas.cob.userservice.api.model.CalendarMoveProposal.Status;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import de.caritas.cob.userservice.api.port.out.CalendarMoveProposalRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverService;
import de.caritas.cob.userservice.api.service.notification.CalendarMoveProposalEmailNotification;
import de.caritas.cob.userservice.api.service.sessionappointment.SessionAppointmentService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Move proposals between counsellors (ADR-032, move proposals v1).
 *
 * <p>A counsellor who sees a colleague's 1:1 appointment in that colleague's calendar may propose a
 * new time for it. The colleague whose conversation it is (the session's counsellor) decides: an
 * accepted proposal moves the appointment through {@link SessionAppointmentService#reschedule}, the
 * same path and the same checks as a move of one's own, so the advice seeker learns about it the
 * same way. Who may see the appointment is {@link CalendarColleagueScope}'s rule, the one that
 * answers {@code GET /users/calendar/colleagues/{id}/entries}.
 *
 * <p>A proposal is void once its appointment was moved or cancelled after it was made, once the
 * appointment or the proposed time has begun, or once the conversation went to another counsellor:
 * it is not listed and cannot be accepted. Decisions lock the appointment first and the proposal
 * second, the order a new proposal takes them in too, so two of them cannot wait on each other.
 */
@Service
@RequiredArgsConstructor
public class CalendarMoveProposalService {

  private static final String NOT_FOUND = "Move proposal not found.";
  private static final String NO_APPOINTMENT = "Appointment not found.";

  private final AuthenticatedUser caller;
  private final CalendarMoveProposalRepository proposals;
  private final SessionAppointmentRepository appointments;
  private final CalendarBlockerRepository blockers;
  private final ConsultantRepository consultants;
  private final CalendarColleagueScope colleagues;
  private final CaseHandoverService caseHandovers;
  private final SessionAppointmentService sessionAppointments;
  private final CalendarEntryReader reader;
  private final CalendarMoveProposalEmailNotification mail;
  private final Clock clock;

  /** The caller's open proposals, sent and received; void ones are left out. */
  @Transactional(readOnly = true)
  public CalendarMoveProposalsDTO open() {
    requireConsultant();
    var me = caller.getUserId();
    var now = Instant.now(clock);
    // A proposal may cross Träger where the setting allows it; the rows are the caller's own.
    List<CalendarMoveProposal> sent =
        TenantContext.supplyAcrossTenants(() -> proposals.findSent(me, Status.OPEN)).stream()
            .filter(proposal -> !isVoid(proposal, proposal.getAppointment(), now))
            .toList();
    List<CalendarMoveProposal> received =
        TenantContext.supplyAcrossTenants(() -> proposals.findReceived(me, Status.OPEN)).stream()
            .filter(proposal -> !isVoid(proposal, proposal.getAppointment(), now))
            .toList();
    var names = namesOf(sent, received);
    return new CalendarMoveProposalsDTO()
        .sent(sent.stream().map(proposal -> toDto(proposal, names)).toList())
        .received(received.stream().map(proposal -> toDto(proposal, names)).toList());
  }

  /** Proposes a new time; replaces the caller's open proposal for the same appointment. */
  @Transactional
  public CalendarMoveProposalDTO create(CalendarMoveProposalCreateDTO request) {
    requireConsultant();
    var me = caller.getUserId();
    var now = Instant.now(clock);
    if (request.getStart() == null || request.getEnd() == null) {
      throw new BadRequestException("A move proposal names a start and an end.");
    }
    var start = request.getStart().toInstant();
    var end = request.getEnd().toInstant();
    if (!end.isAfter(start)) {
      throw new BadRequestException("The end of a proposed time must be after its start.");
    }
    if (!start.isAfter(now)) {
      throw new BadRequestException("A proposed time must start in the future.");
    }
    var appointmentId = appointmentIdOf(request.getEntryId());
    // Locked: two proposals of one counsellor for one appointment must not both stay open.
    var appointment =
        TenantContext.supplyAcrossTenants(() -> appointments.findByIdForUpdate(appointmentId))
            .orElse(null);
    if (appointment == null) {
      if (Boolean.TRUE.equals(
          TenantContext.supplyAcrossTenants(() -> blockers.existsById(appointmentId)))) {
        throw notAnAppointment();
      }
      throw new NotFoundException(NO_APPOINTMENT);
    }
    if (appointment.getStatus() != SessionAppointment.Status.SCHEDULED) {
      // A cancelled appointment is in nobody's calendar.
      throw new NotFoundException(NO_APPOINTMENT);
    }
    var session = appointment.getSession();
    if (isResponsibleCounsellor(session, me)) {
      throw new BadRequestException("One's own appointment is moved directly, not proposed.");
    }
    var approver = session.getConsultant();
    var colleague = approver == null ? null : colleagues.find(me, approver.getId()).orElse(null);
    if (colleague == null) {
      throw new ForbiddenException("The appointment is not in a calendar the caller may see.");
    }
    if (!appointment.getStartTime().isAfter(now)) {
      throw new BadRequestException("An appointment that has begun is not moved any more.");
    }
    for (var previous : proposals.findOfProposer(appointmentId, me, Status.OPEN)) {
      previous.setStatus(Status.WITHDRAWN);
      previous.setDecidedAt(now);
    }
    var proposal = new CalendarMoveProposal();
    proposal.setAppointment(appointment);
    proposal.setProposerId(me);
    proposal.setApproverId(approver.getId());
    proposal.setProposedStart(start);
    proposal.setProposedEnd(end);
    proposal.setEntryStart(appointment.getStartTime());
    proposal.setEntryEnd(appointment.getEndTime());
    proposal.setStatus(Status.OPEN);
    proposal.setCreatedAt(now);
    proposal = proposals.save(proposal);
    var proposerName =
        consultants.findByIdAndDeleteDateIsNull(me).map(CalendarColleagueScope::nameOf).orElse("");
    mail.proposed(
        new CalendarMoveProposalEmailNotification.Mail(
            proposal.getId(), approver.getId(), proposerName, appointment.getStartTime(), start));
    return toDto(proposal, Map.of(me, proposerName, approver.getId(), colleague.name()));
  }

  /** The proposer takes their proposal back; a decided one stays as it is. */
  @Transactional
  public void withdraw(UUID proposalId) {
    requireConsultant();
    var proposal = proposals.findByIdForUpdate(proposalId).orElseThrow(this::notFound);
    if (!Objects.equals(proposal.getProposerId(), caller.getUserId())) {
      throw new ForbiddenException("Only who proposed it withdraws a move proposal.");
    }
    decide(proposal, Status.WITHDRAWN);
  }

  /** The colleague asked says no; the appointment stays. A decided proposal stays as it is. */
  @Transactional
  public void decline(UUID proposalId) {
    requireConsultant();
    var proposal = proposals.findByIdForUpdate(proposalId).orElseThrow(this::notFound);
    requireApprover(proposal.getApproverId());
    decide(proposal, Status.DECLINED);
  }

  /** The colleague asked says yes: the appointment moves, every other open proposal for it ends. */
  @Transactional
  public CalendarEntryDTO accept(UUID proposalId) {
    requireConsultant();
    var me = caller.getUserId();
    var reference = proposals.findReferenceById(proposalId).orElseThrow(this::notFound);
    requireApprover(reference.getApproverId());
    var appointmentId = reference.getAppointmentId();
    var appointment = appointments.findByIdForUpdate(appointmentId).orElse(null);
    var proposal = proposals.findByIdForUpdate(proposalId).orElseThrow(this::notFound);
    var now = Instant.now(clock);
    if (proposal.getStatus() != Status.OPEN) {
      throw new ConflictException("The move proposal is no longer open.");
    }
    if (appointment == null || isVoid(proposal, appointment, now)) {
      throw new ConflictException("The appointment changed meanwhile; the proposal is void.");
    }
    sessionAppointments.reschedule(
        appointment.getSession().getId(),
        appointmentId,
        new SessionAppointmentTimeDTO()
            .startTime(proposal.getProposedStart().atOffset(ZoneOffset.UTC))
            .endTime(proposal.getProposedEnd().atOffset(ZoneOffset.UTC)));
    proposal.setStatus(Status.ACCEPTED);
    proposal.setDecidedAt(now);
    proposals.supersedeOthers(appointmentId, proposalId, now, Status.OPEN, Status.SUPERSEDED);
    // The bulk update above cleared the persistence context: read the moved appointment again.
    var moved = appointments.findById(appointmentId).orElseThrow(this::notFound);
    return CalendarService.own(
        reader.appointmentOfConsultant(moved), me, moved.getStartTime().isAfter(now));
  }

  /**
   * Void: the appointment is gone, cancelled or at another time than when the proposal was made; it
   * or the proposed time has begun; or the conversation now belongs to another counsellor.
   */
  static boolean isVoid(
      CalendarMoveProposal proposal, SessionAppointment appointment, Instant now) {
    return appointment == null
        || appointment.getStatus() != SessionAppointment.Status.SCHEDULED
        || !sameInstant(appointment.getStartTime(), proposal.getEntryStart())
        || !sameInstant(appointment.getEndTime(), proposal.getEntryEnd())
        || !appointment.getStartTime().isAfter(now)
        || !proposal.getProposedStart().isAfter(now)
        || appointment.getSession() == null
        || appointment.getSession().getConsultant() == null
        || !Objects.equals(
            appointment.getSession().getConsultant().getId(), proposal.getApproverId());
  }

  /** The database keeps microseconds; a value read back must still match the one written. */
  private static boolean sameInstant(Instant stored, Instant kept) {
    return stored != null
        && kept != null
        && stored.truncatedTo(ChronoUnit.MICROS).equals(kept.truncatedTo(ChronoUnit.MICROS));
  }

  private void decide(CalendarMoveProposal proposal, Status outcome) {
    if (proposal.getStatus() == Status.OPEN) {
      proposal.setStatus(outcome);
      proposal.setDecidedAt(Instant.now(clock));
    }
  }

  private boolean isResponsibleCounsellor(Session session, String consultantId) {
    return (session.getConsultant() != null
            && Objects.equals(session.getConsultant().getId(), consultantId))
        || caseHandovers.hasActiveCoAccess(session.getId(), consultantId);
  }

  /** The calendar lists appointments by their id; anything else (a group, a blocker) is not one. */
  private static UUID appointmentIdOf(String entryId) {
    if (entryId == null || entryId.isBlank()) {
      throw notAnAppointment();
    }
    try {
      return UUID.fromString(entryId.trim());
    } catch (IllegalArgumentException notAUuid) {
      throw notAnAppointment();
    }
  }

  private static BadRequestException notAnAppointment() {
    return new BadRequestException("Only a 1:1 appointment can be proposed to move.");
  }

  private Map<String, String> namesOf(
      List<CalendarMoveProposal> sent, List<CalendarMoveProposal> received) {
    Set<String> ids = new HashSet<>();
    for (var list : List.of(sent, received)) {
      for (var proposal : list) {
        ids.add(proposal.getProposerId());
        ids.add(proposal.getApproverId());
      }
    }
    var names = new HashMap<String, String>();
    if (ids.isEmpty()) {
      return names;
    }
    Iterable<Consultant> found =
        TenantContext.supplyAcrossTenants(() -> consultants.findAllById(List.copyOf(ids)));
    found.forEach(
        consultant -> names.put(consultant.getId(), CalendarColleagueScope.nameOf(consultant)));
    return names;
  }

  private static CalendarMoveProposalDTO toDto(
      CalendarMoveProposal proposal, Map<String, String> names) {
    return new CalendarMoveProposalDTO()
        .id(proposal.getId())
        .entryId(proposal.getAppointment().getId().toString())
        .start(proposal.getProposedStart().atOffset(ZoneOffset.UTC))
        .end(proposal.getProposedEnd().atOffset(ZoneOffset.UTC))
        .entryStart(proposal.getEntryStart().atOffset(ZoneOffset.UTC))
        .entryEnd(proposal.getEntryEnd().atOffset(ZoneOffset.UTC))
        .proposerId(proposal.getProposerId())
        .proposerName(names.getOrDefault(proposal.getProposerId(), ""))
        .approverId(proposal.getApproverId())
        .approverName(names.getOrDefault(proposal.getApproverId(), ""))
        .createdAt(proposal.getCreatedAt().atOffset(ZoneOffset.UTC));
  }

  private void requireApprover(String approverId) {
    if (!Objects.equals(approverId, caller.getUserId())) {
      throw new ForbiddenException("Only the counsellor asked decides a move proposal.");
    }
  }

  private void requireConsultant() {
    if (!caller.isConsultant()) {
      throw new ForbiddenException("Only a counsellor proposes moves.");
    }
  }

  private NotFoundException notFound() {
    return new NotFoundException(NOT_FOUND);
  }
}
