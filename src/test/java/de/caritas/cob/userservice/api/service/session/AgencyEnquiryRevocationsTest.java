package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRevocationTaskRepository;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRevocationTaskRepository.PendingRoomBinding;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class AgencyEnquiryRevocationsTest {

  private static final String CONSULTANT_ID = "c-1";
  private static final String MEMBER_ID = "@consultant:example.org";
  private static final String ROOM = "!enquiry:example.org";
  private static final String OPERATOR_ID = "@agency:example.org";
  private static final String OPERATOR_TOKEN = "agency-token";
  private static final long AGENCY_ID = 10L;
  private static final long TASK_ID = 17L;
  private static final long SESSION_ID = 20L;
  private static final long RELATION_ID = 30L;
  private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

  @Mock private AgencyEnquiryRevocationTaskRepository tasks;
  @Mock private ConsultantAgencyRepository assignments;
  @Mock private ConsultantRepository consultants;
  @Mock private SessionRepository sessions;
  @Mock private AgencyEnquiryRoomRetirements retiredRooms;
  @Mock private AgencyEnquiryRoomDecisions roomDecisions;
  @Mock private AgencyMatrixCredentialClient agencyCredentials;
  @Mock private SessionRoomGateway rooms;
  @Mock private PlatformTransactionManager transactionManager;
  @Mock private Clock clock;
  @Captor private ArgumentCaptor<Iterable<AgencyEnquiryRevocationTask>> staged;
  private AgencyEnquiryRevocations revocations;

  @BeforeEach
  void setUp() {
    revocations =
        new AgencyEnquiryRevocations(
            tasks,
            assignments,
            consultants,
            sessions,
            retiredRooms,
            roomDecisions,
            agencyCredentials,
            rooms,
            transactionManager,
            clock);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(clock.instant()).thenReturn(NOW);
  }

  @Test
  void stagesOneTaskPerOpenAgencyWideRoomWithoutCallingMatrix() {
    var consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    consultant.setMatrixUserId(MEMBER_ID);
    var relation =
        ConsultantAgency.builder()
            .id(RELATION_ID)
            .consultant(consultant)
            .agencyId(AGENCY_ID)
            .build();
    var first = roomBinding(SESSION_ID, ROOM);
    var other = roomBinding(22L, "!other:example.org");
    var recorded = roomBinding(23L, "!original:example.org");
    var replacement = roomBinding(23L, "!replacement:example.org");
    var pending = org.mockito.Mockito.mock(PendingRoomBinding.class);
    when(pending.getSessionId()).thenReturn(23L);
    when(pending.getRoomId()).thenReturn("!original:example.org");
    when(tasks.pendingRoomBindings(RELATION_ID)).thenReturn(List.of(pending));
    when(sessions.lockOpenEnquiryBindings(AGENCY_ID, AgencyOpenEnquiryMembership.OPEN_STATUSES))
        .thenReturn(List.of(first, other, recorded, replacement));

    revocations.stage(relation);

    verify(tasks).saveAll(staged.capture());
    assertThat(staged.getValue())
        .extracting(AgencyEnquiryRevocationTask::getRoomId)
        .containsExactly(ROOM, other.getRoomId(), replacement.getRoomId());
    assertThat(staged.getValue())
        .extracting(AgencyEnquiryRevocationTask::getMemberId)
        .containsOnly(MEMBER_ID);
    verifyNoInteractions(rooms, agencyCredentials);
  }

  private SessionRepository.OpenEnquiryRoomBinding roomBinding(Long sessionId, String roomId) {
    var binding = org.mockito.Mockito.mock(SessionRepository.OpenEnquiryRoomBinding.class);
    when(binding.getSessionId()).thenReturn(sessionId);
    when(binding.getRoomId()).thenReturn(roomId);
    return binding;
  }

  @Test
  void unconfirmedRemovalStaysDueAndTheNextSweepCanCompleteIt() {
    var task = queuedTask();
    ready(task);
    when(sessions.findByIdForUpdate(SESSION_ID))
        .thenReturn(Optional.of(enquiry(SESSION_ID, SessionStatus.NEW)));
    when(rooms.removeUserFromRoom(ROOM, MEMBER_ID, OPERATOR_TOKEN)).thenReturn(false, true);

    revocations.retryDue();

    assertThat(task.getRetryAt()).isEqualTo(now().plus(AgencyEnquiryRevocations.RETRY_DELAY));
    verify(tasks, never()).delete(task);

    var later = NOW.plus(AgencyEnquiryRevocations.RETRY_DELAY);
    when(clock.instant()).thenReturn(later);
    when(tasks.dueIds(now(), PageRequest.of(0, AgencyEnquiryRevocations.BATCH_SIZE)))
        .thenReturn(List.of(TASK_ID));

    revocations.retryDue();

    var order = inOrder(consultants, sessions, rooms, tasks);
    order.verify(consultants).lockConsultant(CONSULTANT_ID);
    order.verify(sessions).findByIdForUpdate(SESSION_ID);
    order.verify(rooms).removeUserFromRoom(ROOM, MEMBER_ID, OPERATOR_TOKEN);
    order.verify(tasks).delete(task);
  }

  @Test
  void anAlreadyReassignedCounsellorIsNotKickedByAnOldTask() {
    var task = queuedTask();
    ready(task);
    when(assignments.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(CONSULTANT_ID, AGENCY_ID))
        .thenReturn(true);

    revocations.retryDue();

    verify(tasks).delete(task);
    verify(rooms, never()).removeUserFromRoom(any(), any(), any());
  }

  @Test
  void acceptedEnquiryKeepsRevocationWhenAgencyOperatorHasLeft() {
    var task = queuedTask();
    ready(task);
    var accepted = enquiry(SESSION_ID, SessionStatus.IN_PROGRESS);
    var owner = new Consultant();
    owner.setId("new-owner");
    accepted.setConsultant(owner);
    when(sessions.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(accepted));
    when(rooms.loginAsUser(MEMBER_ID)).thenReturn("member-token");
    when(rooms.isConfirmedOutOfRoom(ROOM, MEMBER_ID)).thenReturn(false, true);

    revocations.retryDue();

    verify(rooms, never()).removeUserFromRoom(any(), any(), any());
    verify(rooms).leaveRoom(ROOM, "member-token");
    verify(tasks).delete(task);
  }

  @Test
  void oldTaskNeverRemovesTheNewOwnerOfTheSameCase() {
    var task = queuedTask();
    ready(task);
    var accepted = enquiry(SESSION_ID, SessionStatus.IN_PROGRESS);
    var owner = new Consultant();
    owner.setId(CONSULTANT_ID);
    accepted.setConsultant(owner);
    when(sessions.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(accepted));

    revocations.retryDue();

    verify(rooms, never()).removeUserFromRoom(any(), any(), any());
    verify(rooms, never()).leaveRoom(any(), any());
    verify(tasks).delete(task);
  }

  @Test
  void certificateOrOperatorServiceFailureStillMovesTheRetryPastOtherTasks() {
    var task = queuedTask();
    ready(task);
    when(sessions.findByIdForUpdate(SESSION_ID))
        .thenReturn(Optional.of(enquiry(SESSION_ID, SessionStatus.NEW)));
    when(rooms.loginAsUser(OPERATOR_ID)).thenThrow(new IllegalStateException());

    revocations.retryDue();

    assertThat(task.getRetryAt()).isEqualTo(now().plus(AgencyEnquiryRevocations.RETRY_DELAY));
    verify(tasks, never()).delete(task);
    verify(rooms, never()).removeUserFromRoom(any(), any(), any());
  }

  @Test
  void exceptionDuringRemovalMovesTheRetryPastOtherTasks() {
    var task = queuedTask();
    ready(task);
    when(sessions.findByIdForUpdate(SESSION_ID))
        .thenReturn(Optional.of(enquiry(SESSION_ID, SessionStatus.NEW)));
    when(rooms.removeUserFromRoom(ROOM, MEMBER_ID, OPERATOR_TOKEN))
        .thenThrow(new IllegalStateException());

    revocations.retryDue();

    assertThat(task.getRetryAt()).isEqualTo(now().plus(AgencyEnquiryRevocations.RETRY_DELAY));
    verify(tasks, never()).delete(task);
  }

  @Test
  void aRetiredEnquiryRoomStillLosesTheDepartedMember() {
    var task = queuedTask();
    ready(task);
    var accepted = enquiry(SESSION_ID, SessionStatus.IN_PROGRESS);
    accepted.setMatrixRoomId("!replacement:example.org");
    when(sessions.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(accepted));
    when(retiredRooms.provesRetirement(task, accepted)).thenReturn(true);
    when(rooms.loginAsUser(MEMBER_ID)).thenReturn("member-token");
    when(rooms.isConfirmedOutOfRoom(ROOM, MEMBER_ID)).thenReturn(false, true);

    revocations.retryDue();

    verify(rooms).leaveRoom(ROOM, "member-token");
    verify(rooms, never()).leaveRoom(eq(accepted.getMatrixRoomId()), any());
    verify(rooms, never()).removeUserFromRoom(any(), any(), any());
    verify(tasks).delete(task);
  }

  @Test
  void aRetiredRoomWithUnconfirmedAbsenceStaysPending() {
    var task = queuedTask();
    ready(task);
    var accepted = enquiry(SESSION_ID, SessionStatus.IN_PROGRESS);
    accepted.setMatrixRoomId("!replacement:example.org");
    when(sessions.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(accepted));
    when(retiredRooms.provesRetirement(task, accepted)).thenReturn(true);
    when(rooms.loginAsUser(MEMBER_ID)).thenReturn("member-token");

    revocations.retryDue();

    verify(rooms).leaveRoom(ROOM, "member-token");
    verify(tasks, never()).delete(task);
    assertThat(task.getRetryAt()).isEqualTo(now().plus(AgencyEnquiryRevocations.RETRY_DELAY));
  }

  @Test
  void oldTaskDoesNotRemoveTheCurrentOwnerAfterAReplacement() {
    var task = queuedTask();
    ready(task);
    var accepted = enquiry(SESSION_ID, SessionStatus.IN_PROGRESS);
    accepted.setMatrixRoomId("!replacement:example.org");
    var owner = new Consultant();
    owner.setId(CONSULTANT_ID);
    accepted.setConsultant(owner);
    when(sessions.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(accepted));

    revocations.retryDue();

    verifyNoInteractions(retiredRooms);
    verify(rooms, never()).leaveRoom(any(), any());
    verify(tasks).delete(task);
  }

  @Test
  void anEnquiryMovedToAnotherRoomDoesNotUseAnOldTask() {
    var task = queuedTask();
    ready(task);
    var moved = enquiry(SESSION_ID, SessionStatus.NEW);
    moved.setMatrixRoomId("!replacement:example.org");
    when(sessions.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(moved));

    revocations.retryDue();

    verify(tasks, never()).delete(task);
    verify(rooms).isConfirmedOutOfRoom(ROOM, MEMBER_ID);
    assertThat(task.getRetryAt()).isEqualTo(now().plus(AgencyEnquiryRevocations.RETRY_DELAY));
    verify(rooms, never()).removeUserFromRoom(any(), any(), any());
  }

  @Test
  void confirmedAbsenceCompletesAnUnacknowledgedKick() {
    var task = queuedTask();
    ready(task);
    when(sessions.findByIdForUpdate(SESSION_ID))
        .thenReturn(Optional.of(enquiry(SESSION_ID, SessionStatus.INITIAL)));
    when(rooms.isConfirmedOutOfRoom(ROOM, MEMBER_ID)).thenReturn(true);

    revocations.retryDue();

    verify(tasks).delete(task);
  }

  private void ready(AgencyEnquiryRevocationTask task) {
    when(tasks.dueIds(now(), PageRequest.of(0, AgencyEnquiryRevocations.BATCH_SIZE)))
        .thenReturn(List.of(TASK_ID));
    when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task));
    when(tasks.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
    var credentials = new AgencyMatrixCredentialsDTO();
    credentials.setMatrixUserId(OPERATOR_ID);
    when(agencyCredentials.fetchMatrixCredentials(AGENCY_ID)).thenReturn(Optional.of(credentials));
    when(rooms.loginAsUser(OPERATOR_ID)).thenReturn(OPERATOR_TOKEN);
    when(transactionManager.getTransaction(any()))
        .thenAnswer(ignored -> new SimpleTransactionStatus());
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  private AgencyEnquiryRevocationTask queuedTask() {
    var task =
        AgencyEnquiryRevocationTask.builder()
            .consultantId(CONSULTANT_ID)
            .agencyId(AGENCY_ID)
            .sessionId(SESSION_ID)
            .roomId(ROOM)
            .memberId(MEMBER_ID)
            .retryAt(now())
            .build();
    ReflectionTestUtils.setField(task, "id", TASK_ID);
    return task;
  }

  private Session enquiry(Long id, SessionStatus status) {
    var session = new Session();
    session.setId(id);
    session.setAgencyId(AGENCY_ID);
    session.setStatus(status);
    session.setMatrixRoomId(ROOM);
    return session;
  }
}
