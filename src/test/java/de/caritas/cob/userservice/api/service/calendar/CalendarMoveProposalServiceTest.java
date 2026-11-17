package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryType;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarMoveProposalCreateDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionAppointmentTimeDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
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
import de.caritas.cob.userservice.api.service.calendar.CalendarColleagueScope.Colleague;
import de.caritas.cob.userservice.api.service.calendar.CalendarColleagueScope.Relation;
import de.caritas.cob.userservice.api.service.notification.CalendarMoveProposalEmailNotification;
import de.caritas.cob.userservice.api.service.sessionappointment.SessionAppointmentService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarMoveProposalServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
  private static final Instant ENTRY_START = NOW.plus(Duration.ofDays(2));
  private static final Instant ENTRY_END = ENTRY_START.plus(Duration.ofHours(1));
  private static final Instant NEW_START = NOW.plus(Duration.ofDays(3));
  private static final Instant NEW_END = NEW_START.plus(Duration.ofHours(1));
  private static final String ME = "proposer";
  private static final String OWNER = "owner";
  private static final long SESSION_ID = 77L;

  @Mock CalendarMoveProposalRepository proposals;
  @Mock SessionAppointmentRepository appointments;
  @Mock CalendarBlockerRepository blockers;
  @Mock ConsultantRepository consultants;
  @Mock CalendarColleagueScope colleagues;
  @Mock CaseHandoverService caseHandovers;
  @Mock SessionAppointmentService sessionAppointments;
  @Mock CalendarEntryReader reader;
  @Mock CalendarMoveProposalEmailNotification mail;

  private final AuthenticatedUser caller = new AuthenticatedUser();
  private CalendarMoveProposalService service;
  private Consultant owner;
  private SessionAppointment appointment;

  @BeforeEach
  void setUp() {
    actAs(ME);
    service =
        new CalendarMoveProposalService(
            caller,
            proposals,
            appointments,
            blockers,
            consultants,
            colleagues,
            caseHandovers,
            sessionAppointments,
            reader,
            mail,
            Clock.fixed(NOW, ZoneOffset.UTC));
    owner = consultant(OWNER, "Owner Name");
    var session = new Session();
    session.setId(SESSION_ID);
    session.setConsultant(owner);
    appointment = new SessionAppointment();
    appointment.setId(UUID.randomUUID());
    appointment.setSession(session);
    appointment.setStartTime(ENTRY_START);
    appointment.setEndTime(ENTRY_END);
    appointment.setStatus(SessionAppointment.Status.SCHEDULED);
    lenient()
        .when(proposals.save(any()))
        .thenAnswer(
            invocation -> {
              CalendarMoveProposal saved = invocation.getArgument(0);
              saved.setId(UUID.randomUUID());
              return saved;
            });
  }

  @Test
  void create_Should_StoreAnOpenProposal_And_TellTheOwnerAfterCommit() {
    stageVisibleAppointment();
    when(consultants.findByIdAndDeleteDateIsNull(ME))
        .thenReturn(Optional.of(consultant(ME, "Proposer Name")));

    var created = service.create(request(appointment.getId().toString(), NEW_START, NEW_END));

    var stored = ArgumentCaptor.forClass(CalendarMoveProposal.class);
    verify(proposals).save(stored.capture());
    assertThat(stored.getValue().getStatus()).isEqualTo(Status.OPEN);
    assertThat(stored.getValue().getProposerId()).isEqualTo(ME);
    assertThat(stored.getValue().getApproverId()).isEqualTo(OWNER);
    assertThat(stored.getValue().getEntryStart()).isEqualTo(ENTRY_START);
    assertThat(stored.getValue().getEntryEnd()).isEqualTo(ENTRY_END);
    assertThat(stored.getValue().getProposedStart()).isEqualTo(NEW_START);
    assertThat(stored.getValue().getCreatedAt()).isEqualTo(NOW);
    assertThat(created.getEntryId()).isEqualTo(appointment.getId().toString());
    assertThat(created.getProposerName()).isEqualTo("Proposer Name");
    assertThat(created.getApproverName()).isEqualTo("Owner Name");
    assertThat(created.getEntryStart().toInstant()).isEqualTo(ENTRY_START);
    var told = ArgumentCaptor.forClass(CalendarMoveProposalEmailNotification.Mail.class);
    verify(mail).proposed(told.capture());
    assertThat(told.getValue().approverId()).isEqualTo(OWNER);
    assertThat(told.getValue().proposerName()).isEqualTo("Proposer Name");
    assertThat(told.getValue().currentStart()).isEqualTo(ENTRY_START);
    assertThat(told.getValue().proposedStart()).isEqualTo(NEW_START);
  }

  @Test
  void create_Should_ReplaceTheCallersOpenProposal_ForTheSameAppointment() {
    stageVisibleAppointment();
    var previous = proposal(Status.OPEN, ME);
    when(proposals.findOfProposer(appointment.getId(), ME, Status.OPEN))
        .thenReturn(List.of(previous));

    service.create(request(appointment.getId().toString(), NEW_START, NEW_END));

    assertThat(previous.getStatus()).isEqualTo(Status.WITHDRAWN);
    assertThat(previous.getDecidedAt()).isEqualTo(NOW);
    verify(proposals).save(any());
  }

  @Test
  void create_Should_BeRefused_When_TheTimeIsNoFutureSpan() {
    var id = appointment.getId().toString();

    assertThatThrownBy(() -> service.create(request(id, NEW_START, NEW_START)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.create(request(id, NEW_END, NEW_START)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.create(request(id, NOW.minusSeconds(60), NEW_END)))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(appointments, proposals, mail);
  }

  @Test
  void create_Should_BeRefused_When_TheEntryIsNoAppointment() {
    assertThatThrownBy(() -> service.create(request("chat-12-0", NEW_START, NEW_END)))
        .isInstanceOf(BadRequestException.class);
    var blockerId = UUID.randomUUID();
    when(blockers.existsById(blockerId)).thenReturn(true);

    assertThatThrownBy(() -> service.create(request(blockerId.toString(), NEW_START, NEW_END)))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(proposals, mail);
  }

  @Test
  void create_Should_BeRefused_ForOnesOwnAppointment() {
    when(appointments.findByIdForUpdate(appointment.getId())).thenReturn(Optional.of(appointment));
    actAs(OWNER);

    assertThatThrownBy(
            () -> service.create(request(appointment.getId().toString(), NEW_START, NEW_END)))
        .isInstanceOf(BadRequestException.class);

    actAs(ME);
    when(caseHandovers.hasActiveCoAccess(SESSION_ID, ME)).thenReturn(true);
    assertThatThrownBy(
            () -> service.create(request(appointment.getId().toString(), NEW_START, NEW_END)))
        .as("a co-access appointment is in the caller's own calendar")
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(proposals, mail);
  }

  @Test
  void create_Should_BeRefused_When_TheAppointmentHasBegun() {
    appointment.setStartTime(NOW.minusSeconds(60));
    stageVisibleAppointment();

    assertThatThrownBy(
            () -> service.create(request(appointment.getId().toString(), NEW_START, NEW_END)))
        .isInstanceOf(BadRequestException.class);
    verify(proposals, never()).save(any());
  }

  @Test
  void create_Should_BeForbidden_When_TheOwnersCalendarIsOutOfTheCallersScope() {
    when(appointments.findByIdForUpdate(appointment.getId())).thenReturn(Optional.of(appointment));
    when(colleagues.find(ME, OWNER)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.create(request(appointment.getId().toString(), NEW_START, NEW_END)))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(proposals, mail);
  }

  @Test
  void create_Should_NotFind_AnUnknownOrCancelledAppointment() {
    var unknown = UUID.randomUUID();

    assertThatThrownBy(() -> service.create(request(unknown.toString(), NEW_START, NEW_END)))
        .isInstanceOf(NotFoundException.class);
    appointment.setStatus(SessionAppointment.Status.CANCELLED);
    when(appointments.findByIdForUpdate(appointment.getId())).thenReturn(Optional.of(appointment));
    assertThatThrownBy(
            () -> service.create(request(appointment.getId().toString(), NEW_START, NEW_END)))
        .isInstanceOf(NotFoundException.class);
    verifyNoInteractions(proposals, mail);
  }

  @Test
  void everyCall_Should_BeForbidden_ForAnAsker() {
    caller.setRoles(Set.of(UserRole.USER.getValue()));
    var id = UUID.randomUUID();

    assertThatThrownBy(() -> service.open()).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.create(request(id.toString(), NEW_START, NEW_END)))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.withdraw(id)).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.accept(id)).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.decline(id)).isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(proposals, appointments);
  }

  @Test
  void open_Should_ListOnlyLiveProposals_SentAndReceived() {
    var live = proposal(Status.OPEN, ME);
    var moved = proposal(Status.OPEN, ME);
    moved.setEntryStart(ENTRY_START.minusSeconds(3600));
    var lapsed = proposal(Status.OPEN, ME);
    lapsed.setProposedStart(NOW.minusSeconds(1));
    var cancelled = proposal(Status.OPEN, ME);
    var cancelledAppointment = copyOf(appointment);
    cancelledAppointment.setStatus(SessionAppointment.Status.CANCELLED);
    cancelled.setAppointment(cancelledAppointment);
    var handedOver = proposal(Status.OPEN, ME);
    handedOver.setApproverId("former-owner");
    when(proposals.findSent(ME, Status.OPEN))
        .thenReturn(List.of(live, moved, lapsed, cancelled, handedOver));
    var received = proposal(Status.OPEN, "someone");
    received.setApproverId(ME);
    var receivedAppointment = copyOf(appointment);
    receivedAppointment.getSession().setConsultant(consultant(ME, "Me"));
    received.setAppointment(receivedAppointment);
    when(proposals.findReceived(ME, Status.OPEN)).thenReturn(List.of(received));
    when(consultants.findAllById(any()))
        .thenReturn(
            List.of(
                consultant(ME, "Me"),
                owner,
                consultant("someone", "Someone"),
                consultant("former-owner", "Former")));

    var listing = service.open();

    assertThat(listing.getSent()).extracting(dto -> dto.getId()).containsExactly(live.getId());
    assertThat(listing.getSent().get(0).getApproverName()).isEqualTo("Owner Name");
    assertThat(listing.getReceived())
        .extracting(dto -> dto.getId())
        .containsExactly(received.getId());
    assertThat(listing.getReceived().get(0).getProposerName()).isEqualTo("Someone");
  }

  @Test
  void withdraw_Should_EndAnOpenProposal_OnlyForItsProposer_AndLeaveADecidedOne() {
    var open = proposal(Status.OPEN, ME);
    when(proposals.findByIdForUpdate(open.getId())).thenReturn(Optional.of(open));

    service.withdraw(open.getId());

    assertThat(open.getStatus()).isEqualTo(Status.WITHDRAWN);
    var declined = proposal(Status.DECLINED, ME);
    when(proposals.findByIdForUpdate(declined.getId())).thenReturn(Optional.of(declined));
    service.withdraw(declined.getId());
    assertThat(declined.getStatus()).as("idempotent, no change").isEqualTo(Status.DECLINED);
    var foreign = proposal(Status.OPEN, "someone-else");
    when(proposals.findByIdForUpdate(foreign.getId())).thenReturn(Optional.of(foreign));
    assertThatThrownBy(() -> service.withdraw(foreign.getId()))
        .isInstanceOf(ForbiddenException.class);
    assertThat(foreign.getStatus()).isEqualTo(Status.OPEN);
    assertThatThrownBy(() -> service.withdraw(UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void decline_Should_EndAnOpenProposal_OnlyForTheCounsellorAsked() {
    var proposal = proposal(Status.OPEN, "someone");
    when(proposals.findByIdForUpdate(proposal.getId())).thenReturn(Optional.of(proposal));

    assertThatThrownBy(() -> service.decline(proposal.getId()))
        .isInstanceOf(ForbiddenException.class);
    assertThat(proposal.getStatus()).isEqualTo(Status.OPEN);

    actAs(OWNER);
    service.decline(proposal.getId());
    assertThat(proposal.getStatus()).isEqualTo(Status.DECLINED);
    assertThat(proposal.getDecidedAt()).isEqualTo(NOW);
    verifyNoInteractions(sessionAppointments);
    assertThatThrownBy(() -> service.decline(UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void accept_Should_MoveThroughTheAppointmentPath_And_SupersedeTheOthers() {
    actAs(OWNER);
    var proposal = proposal(Status.OPEN, ME);
    stageDecision(proposal);
    when(appointments.findById(appointment.getId())).thenReturn(Optional.of(appointment));
    when(reader.appointmentOfConsultant(appointment))
        .thenReturn(
            new CalendarEntry(
                appointment.getId().toString(),
                CalendarEntryType.APPOINTMENT,
                ENTRY_START,
                ENTRY_END,
                null,
                "asker",
                SESSION_ID,
                null));

    var entry = service.accept(proposal.getId());

    var time = ArgumentCaptor.forClass(SessionAppointmentTimeDTO.class);
    verify(sessionAppointments).reschedule(eq(SESSION_ID), eq(appointment.getId()), time.capture());
    assertThat(time.getValue().getStartTime().toInstant()).isEqualTo(NEW_START);
    assertThat(time.getValue().getEndTime().toInstant()).isEqualTo(NEW_END);
    assertThat(time.getValue().getKind()).as("a move keeps the kind").isNull();
    assertThat(proposal.getStatus()).isEqualTo(Status.ACCEPTED);
    assertThat(proposal.getDecidedAt()).isEqualTo(NOW);
    verify(proposals)
        .supersedeOthers(
            appointment.getId(), proposal.getId(), NOW, Status.OPEN, Status.SUPERSEDED);
    assertThat(entry.getOwnerId()).isEqualTo(OWNER);
    assertThat(entry.getSessionId()).as("the app tells the advice seeker").isEqualTo(SESSION_ID);
  }

  @Test
  void accept_Should_Conflict_When_TheAppointmentMovedMeanwhile_OrTheProposalIsNotOpen() {
    actAs(OWNER);
    var proposal = proposal(Status.OPEN, ME);
    stageDecision(proposal);
    appointment.setStartTime(ENTRY_START.plusSeconds(1800));

    assertThatThrownBy(() -> service.accept(proposal.getId()))
        .isInstanceOf(ConflictException.class);
    appointment.setStartTime(ENTRY_START);
    proposal.setStatus(Status.WITHDRAWN);
    assertThatThrownBy(() -> service.accept(proposal.getId()))
        .isInstanceOf(ConflictException.class);
    proposal.setStatus(Status.OPEN);
    proposal.setProposedStart(NOW.minusSeconds(1));
    assertThatThrownBy(() -> service.accept(proposal.getId()))
        .as("the proposed time has passed")
        .isInstanceOf(ConflictException.class);
    verifyNoInteractions(sessionAppointments);
    verify(proposals, never()).supersedeOthers(any(), any(), any(), any(), any());
  }

  @Test
  void accept_Should_BeForbidden_ForAnyoneButTheCounsellorAsked() {
    var proposal = proposal(Status.OPEN, ME);
    when(proposals.findReferenceById(proposal.getId()))
        .thenReturn(Optional.of(reference(proposal)));

    assertThatThrownBy(() -> service.accept(proposal.getId()))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.accept(UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
    verifyNoInteractions(sessionAppointments, appointments);
  }

  private void stageVisibleAppointment() {
    when(appointments.findByIdForUpdate(appointment.getId())).thenReturn(Optional.of(appointment));
    when(colleagues.find(ME, OWNER))
        .thenReturn(
            Optional.of(new Colleague(owner, "Owner Name", Relation.SAME_AGENCY, 1L, false)));
    lenient().when(caseHandovers.hasActiveCoAccess(SESSION_ID, ME)).thenReturn(false);
  }

  private void stageDecision(CalendarMoveProposal proposal) {
    when(proposals.findReferenceById(proposal.getId()))
        .thenReturn(Optional.of(reference(proposal)));
    when(appointments.findByIdForUpdate(appointment.getId())).thenReturn(Optional.of(appointment));
    when(proposals.findByIdForUpdate(proposal.getId())).thenReturn(Optional.of(proposal));
  }

  private static CalendarMoveProposalRepository.Reference reference(CalendarMoveProposal proposal) {
    return new CalendarMoveProposalRepository.Reference() {
      @Override
      public UUID getAppointmentId() {
        return proposal.getAppointment().getId();
      }

      @Override
      public String getApproverId() {
        return proposal.getApproverId();
      }
    };
  }

  private CalendarMoveProposal proposal(Status status, String proposerId) {
    var proposal = new CalendarMoveProposal();
    proposal.setId(UUID.randomUUID());
    proposal.setAppointment(appointment);
    proposal.setProposerId(proposerId);
    proposal.setApproverId(OWNER);
    proposal.setProposedStart(NEW_START);
    proposal.setProposedEnd(NEW_END);
    proposal.setEntryStart(ENTRY_START);
    proposal.setEntryEnd(ENTRY_END);
    proposal.setStatus(status);
    proposal.setCreatedAt(NOW.minusSeconds(60));
    return proposal;
  }

  private static SessionAppointment copyOf(SessionAppointment original) {
    var session = new Session();
    session.setId(original.getSession().getId());
    session.setConsultant(original.getSession().getConsultant());
    var copy = new SessionAppointment();
    copy.setId(UUID.randomUUID());
    copy.setSession(session);
    copy.setStartTime(original.getStartTime());
    copy.setEndTime(original.getEndTime());
    copy.setStatus(original.getStatus());
    return copy;
  }

  private static CalendarMoveProposalCreateDTO request(String entryId, Instant start, Instant end) {
    return new CalendarMoveProposalCreateDTO()
        .entryId(entryId)
        .start(start.atOffset(ZoneOffset.UTC))
        .end(end.atOffset(ZoneOffset.UTC));
  }

  private static Consultant consultant(String id, String name) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setFirstName(name);
    consultant.setLastName("");
    consultant.setInternalDisplayName(name);
    return consultant;
  }

  private void actAs(String userId) {
    caller.setUserId(userId);
    caller.setTenantId(1L);
    caller.setRoles(Set.of(UserRole.CONSULTANT.getValue()));
    lenient().when(caseHandovers.hasActiveCoAccess(eq(SESSION_ID), anyString())).thenReturn(false);
  }
}
