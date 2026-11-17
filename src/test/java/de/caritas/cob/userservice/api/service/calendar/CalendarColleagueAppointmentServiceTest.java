package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AppointmentKind;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarAutoAcceptDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarEntryType;
import de.caritas.cob.userservice.api.adapters.web.dto.ColleagueAppointmentCreateDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.InternalSubsetChatDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.InternalSubsetChatFacade;
import de.caritas.cob.userservice.api.facade.InternalSubsetChatFacade.OpenedRoom;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarAutoAccept;
import de.caritas.cob.userservice.api.model.CalendarColleagueAppointment;
import de.caritas.cob.userservice.api.model.CalendarColleagueAppointment.Status;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.SessionAppointment.Kind;
import de.caritas.cob.userservice.api.port.out.CalendarAutoAcceptRepository;
import de.caritas.cob.userservice.api.port.out.CalendarColleagueAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.calendar.CalendarColleagueScope.Colleague;
import de.caritas.cob.userservice.api.service.calendar.CalendarColleagueScope.Relation;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
class CalendarColleagueAppointmentServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
  private static final String ME = "me";
  private static final String COLLEAGUE = "colleague";
  private static final OffsetDateTime START = OffsetDateTime.parse("2026-11-19T09:00:00Z");
  private static final OffsetDateTime END = OffsetDateTime.parse("2026-11-19T10:00:00Z");

  @Mock CalendarColleagueAppointmentRepository appointments;
  @Mock CalendarAutoAcceptRepository autoAccept;
  @Mock CalendarColleagueScope colleagues;
  @Mock ConsultantRepository consultants;
  @Mock InternalSubsetChatFacade rooms;

  private final AuthenticatedUser caller = new AuthenticatedUser();
  private final List<CalendarColleagueAppointment> saved = new ArrayList<>();
  private final Consultant me = consultant(ME, "Mia Me");
  private final Consultant colleague = consultant(COLLEAGUE, "Kai Colleague");
  private CalendarColleagueAppointmentService service;

  @BeforeEach
  void setUp() {
    caller.setUserId(ME);
    caller.setTenantId(1L);
    caller.setRoles(Set.of(UserRole.CONSULTANT.getValue()));
    service =
        new CalendarColleagueAppointmentService(
            caller,
            appointments,
            autoAccept,
            colleagues,
            consultants,
            rooms,
            Clock.fixed(NOW, ZoneOffset.UTC));
    lenient()
        .when(appointments.save(any()))
        .thenAnswer(
            call -> {
              CalendarColleagueAppointment appointment = call.getArgument(0);
              if (appointment.getId() == null) {
                appointment.setId(UUID.randomUUID());
              }
              saved.add(appointment);
              return appointment;
            });
    lenient().when(consultants.findByIdAndDeleteDateIsNull(ME)).thenReturn(Optional.of(me));
    lenient()
        .when(consultants.findByIdAndDeleteDateIsNull(COLLEAGUE))
        .thenReturn(Optional.of(colleague));
    lenient()
        .when(colleagues.find(ME, COLLEAGUE))
        .thenReturn(
            Optional.of(
                new Colleague(colleague, "Kai Colleague", Relation.SAME_AGENCY, 5L, false)));
    lenient().when(rooms.open(any(), eq(me))).thenReturn(new OpenedRoom("!room:oriso", 77L, true));
  }

  private ColleagueAppointmentCreateDTO request() {
    return new ColleagueAppointmentCreateDTO()
        .colleagueId(COLLEAGUE)
        .start(START)
        .end(END)
        .kind(AppointmentKind.VIDEO);
  }

  @Test
  void create_Should_OpenTheInternalChatOfTheTwoAndWaitForTheColleague() {
    CalendarEntryDTO created = service.create(request());

    var room = ArgumentCaptor.forClass(InternalSubsetChatDTO.class);
    verify(rooms).open(room.capture(), eq(me));
    assertThat(room.getValue().getConsultantIds()).containsExactly(COLLEAGUE);
    assertThat(room.getValue().getAgencyId()).isNull();

    assertThat(saved).hasSize(1);
    var stored = saved.get(0);
    assertThat(stored.getOrganizerId()).isEqualTo(ME);
    assertThat(stored.getInviteeId()).isEqualTo(COLLEAGUE);
    assertThat(stored.getChatId()).isEqualTo(77L);
    assertThat(stored.getMatrixRoomId()).isEqualTo("!room:oriso");
    assertThat(stored.getKind()).isEqualTo(Kind.VIDEO);
    assertThat(stored.getStatus()).isEqualTo(Status.PENDING);
    assertThat(stored.getStartTime()).isEqualTo(START.toInstant());

    assertThat(created.getType()).isEqualTo(CalendarEntryType.APPOINTMENT);
    assertThat(created.getOwnerId()).isEqualTo(ME);
    assertThat(created.getKind()).isEqualTo(AppointmentKind.VIDEO);
    assertThat(created.getTitle()).isEqualTo("Kai Colleague");
    assertThat(created.getColleagueId()).isEqualTo(COLLEAGUE);
    assertThat(created.getColleagueName()).isEqualTo("Kai Colleague");
    assertThat(created.getColleagueStatus())
        .isEqualTo(CalendarEntryDTO.ColleagueStatusEnum.PENDING);
    assertThat(created.getInvited()).isFalse();
    assertThat(created.getMatrixRoomId()).isEqualTo("!room:oriso");
    assertThat(created.getSessionId()).isNull();
    assertThat(created.getMovable()).isFalse();
  }

  @Test
  void create_Should_BeConfirmedAtOnce_When_TheColleagueAcceptsTheCallerAutomatically() {
    when(autoAccept.existsByConsultantIdAndColleagueId(COLLEAGUE, ME)).thenReturn(true);

    var created = service.create(request());

    assertThat(saved.get(0).getStatus()).isEqualTo(Status.CONFIRMED);
    assertThat(created.getColleagueStatus())
        .isEqualTo(CalendarEntryDTO.ColleagueStatusEnum.CONFIRMED);
  }

  @Test
  void create_Should_AnswerNotFound_When_TheColleagueIsOutsideTheCalendarReach() {
    when(colleagues.find(ME, COLLEAGUE)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(request())).isInstanceOf(NotFoundException.class);
    verify(rooms, never()).open(any(), any());
    assertThat(saved).isEmpty();
  }

  @Test
  void create_Should_StoreNothing_When_TheInternalChatIsRefused() {
    when(rooms.open(any(), eq(me))).thenThrow(new ForbiddenException("out of chat reach"));

    assertThatThrownBy(() -> service.create(request())).isInstanceOf(ForbiddenException.class);
    assertThat(saved).isEmpty();
  }

  @Test
  void create_Should_RefuseTimesThatAreReversedOrPast() {
    assertThatThrownBy(() -> service.create(request().end(START)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                service.create(
                    request()
                        .start(OffsetDateTime.parse("2026-11-17T09:00:00Z"))
                        .end(OffsetDateTime.parse("2026-11-17T09:30:00Z"))))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.create(request().colleagueId(ME)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.create(request().kind(null)))
        .isInstanceOf(BadRequestException.class);
    assertThat(saved).isEmpty();
  }

  @Test
  void create_Should_BeRefused_When_TheCallerIsNoCounsellor() {
    caller.setRoles(Set.of(UserRole.USER.getValue()));

    assertThatThrownBy(() -> service.create(request())).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void accept_Should_ConfirmAnInvitationOfTheCaller() {
    var invitation = stored(COLLEAGUE, ME, Status.PENDING);
    when(appointments.findByIdForUpdate(invitation.getId())).thenReturn(Optional.of(invitation));

    var accepted = service.accept(invitation.getId());

    assertThat(invitation.getStatus()).isEqualTo(Status.CONFIRMED);
    assertThat(accepted.getOwnerId()).isEqualTo(ME);
    assertThat(accepted.getInvited()).isTrue();
    assertThat(accepted.getColleagueId()).isEqualTo(COLLEAGUE);
    assertThat(accepted.getColleagueStatus())
        .isEqualTo(CalendarEntryDTO.ColleagueStatusEnum.CONFIRMED);
  }

  @Test
  void accept_Should_AnswerNotFound_When_TheCallerWasNotInvited() {
    var mine = stored(ME, COLLEAGUE, Status.PENDING);
    when(appointments.findByIdForUpdate(mine.getId())).thenReturn(Optional.of(mine));

    assertThatThrownBy(() -> service.accept(mine.getId())).isInstanceOf(NotFoundException.class);
    assertThat(mine.getStatus()).isEqualTo(Status.PENDING);
  }

  @Test
  void accept_Should_Conflict_When_TheInvitationWasAnsweredOrCancelled() {
    var cancelled = stored(COLLEAGUE, ME, Status.CANCELLED);
    when(appointments.findByIdForUpdate(cancelled.getId())).thenReturn(Optional.of(cancelled));

    assertThatThrownBy(() -> service.accept(cancelled.getId()))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void decline_Should_MarkTheInvitationDeclined() {
    var invitation = stored(COLLEAGUE, ME, Status.PENDING);
    when(appointments.findByIdForUpdate(invitation.getId())).thenReturn(Optional.of(invitation));

    service.decline(invitation.getId());

    assertThat(invitation.getStatus()).isEqualTo(Status.DECLINED);
  }

  @Test
  void cancel_Should_BeAllowedToBothCounsellors() {
    var mine = stored(ME, COLLEAGUE, Status.CONFIRMED);
    var theirs = stored(COLLEAGUE, ME, Status.PENDING);
    when(appointments.findByIdForUpdate(mine.getId())).thenReturn(Optional.of(mine));
    when(appointments.findByIdForUpdate(theirs.getId())).thenReturn(Optional.of(theirs));

    service.cancel(mine.getId());
    service.cancel(theirs.getId());

    assertThat(mine.getStatus()).isEqualTo(Status.CANCELLED);
    assertThat(theirs.getStatus()).isEqualTo(Status.CANCELLED);
  }

  @Test
  void cancel_Should_AnswerNotFound_When_TheCallerIsNeitherOfTheTwo() {
    var others = stored(COLLEAGUE, "third", Status.CONFIRMED);
    when(appointments.findByIdForUpdate(others.getId())).thenReturn(Optional.of(others));

    assertThatThrownBy(() -> service.cancel(others.getId())).isInstanceOf(NotFoundException.class);
    assertThat(others.getStatus()).isEqualTo(Status.CONFIRMED);
  }

  @Test
  void autoAccept_Should_ListTheCallersColleagues() {
    when(autoAccept.findByConsultantId(ME)).thenReturn(List.of(row(ME, "b"), row(ME, "a")));

    assertThat(service.autoAccept().getColleagueIds()).containsExactly("a", "b");
  }

  @Test
  void replaceAutoAccept_Should_ReplaceTheListWithColleaguesInReach() {
    when(colleagues.find(ME, "a"))
        .thenReturn(Optional.of(new Colleague(colleague, "A", Relation.SAME_AGENCY, 5L, false)));
    when(autoAccept.findByConsultantId(ME)).thenReturn(List.of(row(ME, "a")));

    var result =
        service.replaceAutoAccept(new CalendarAutoAcceptDTO().colleagueIds(List.of("a", "a")));

    verify(autoAccept).deleteByConsultantId(ME);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<CalendarAutoAccept>> rows = ArgumentCaptor.forClass(List.class);
    verify(autoAccept).saveAll(rows.capture());
    assertThat(rows.getValue()).extracting(CalendarAutoAccept::getColleagueId).containsExactly("a");
    assertThat(rows.getValue()).extracting(CalendarAutoAccept::getConsultantId).containsOnly(ME);
    assertThat(result.getColleagueIds()).containsExactly("a");
  }

  @Test
  void replaceAutoAccept_Should_RefuseColleaguesOutsideTheReach() {
    when(colleagues.find(ME, "far")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.replaceAutoAccept(new CalendarAutoAcceptDTO().colleagueIds(List.of("far"))))
        .isInstanceOf(BadRequestException.class);
    verify(autoAccept, never()).deleteByConsultantId(any());
  }

  private CalendarColleagueAppointment stored(String organizer, String invitee, Status status) {
    var appointment = new CalendarColleagueAppointment();
    appointment.setId(UUID.randomUUID());
    appointment.setOrganizerId(organizer);
    appointment.setInviteeId(invitee);
    appointment.setStartTime(START.toInstant());
    appointment.setEndTime(END.toInstant());
    appointment.setKind(Kind.CHAT);
    appointment.setStatus(status);
    appointment.setMatrixRoomId("!room:oriso");
    appointment.setChatId(77L);
    appointment.setCreatedAt(NOW);
    return appointment;
  }

  private static CalendarAutoAccept row(String consultantId, String colleagueId) {
    var row = new CalendarAutoAccept();
    row.setConsultantId(consultantId);
    row.setColleagueId(colleagueId);
    return row;
  }

  private static Consultant consultant(String id, String name) {
    var consultant = new Consultant();
    consultant.setId(id);
    var parts = name.split(" ");
    consultant.setFirstName(parts[0]);
    consultant.setLastName(parts[1]);
    consultant.setTenantId(1L);
    return consultant;
  }
}
