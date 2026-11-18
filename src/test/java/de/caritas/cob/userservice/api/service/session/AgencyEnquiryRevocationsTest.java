package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRevocationTaskRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
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
  @Mock private AgencyMatrixCredentialClient agencyCredentials;
  @Mock private MatrixSynapseService matrix;
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
            agencyCredentials,
            matrix,
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
    var addressed = enquiry(21L, SessionStatus.NEW);
    addressed.setIsConsultantDirectlySet(true);
    var other = enquiry(22L, SessionStatus.INITIAL);
    other.setMatrixRoomId("!other:example.org");
    var recorded = enquiry(23L, SessionStatus.INITIAL);
    when(tasks.pendingSessionIds(RELATION_ID)).thenReturn(List.of(recorded.getId()));
    when(sessions.findByAgencyIdAndStatusAndConsultantIsNull(AGENCY_ID, SessionStatus.NEW))
        .thenReturn(List.of(enquiry(SESSION_ID, SessionStatus.NEW), addressed));
    when(sessions.findByAgencyIdAndStatusAndConsultantIsNull(AGENCY_ID, SessionStatus.INITIAL))
        .thenReturn(List.of(other, recorded));

    revocations.stage(relation);

    verify(tasks).saveAll(staged.capture());
    assertThat(staged.getValue())
        .extracting(AgencyEnquiryRevocationTask::getSessionId)
        .containsExactly(SESSION_ID, other.getId());
    assertThat(staged.getValue())
        .extracting(AgencyEnquiryRevocationTask::getMemberId)
        .containsOnly(MEMBER_ID);
    verifyNoInteractions(matrix, agencyCredentials);
  }

  @Test
  void unconfirmedRemovalStaysDueAndTheNextSweepCanCompleteIt() {
    var task = queuedTask();
    ready(task);
    when(sessions.findByIdForUpdate(SESSION_ID))
        .thenReturn(Optional.of(enquiry(SESSION_ID, SessionStatus.NEW)));
    when(matrix.removeUserFromRoom(ROOM, MEMBER_ID, OPERATOR_TOKEN)).thenReturn(false, true);

    revocations.retryDue();

    assertThat(task.getRetryAt()).isEqualTo(now().plus(AgencyEnquiryRevocations.RETRY_DELAY));
    verify(tasks, never()).delete(task);

    var later = NOW.plus(AgencyEnquiryRevocations.RETRY_DELAY);
    when(clock.instant()).thenReturn(later);
    when(tasks.dueIds(now(), PageRequest.of(0, AgencyEnquiryRevocations.BATCH_SIZE)))
        .thenReturn(List.of(TASK_ID));

    revocations.retryDue();

    var order = inOrder(consultants, sessions, matrix, tasks);
    order.verify(consultants).lockConsultant(CONSULTANT_ID);
    order.verify(sessions).findByIdForUpdate(SESSION_ID);
    order.verify(matrix).removeUserFromRoom(ROOM, MEMBER_ID, OPERATOR_TOKEN);
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
    verify(matrix, never()).removeUserFromRoom(any(), any(), any());
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
    when(matrix.loginAsUserAccessToken(MEMBER_ID)).thenReturn("member-token");
    when(matrix.isOutOfRoom(ROOM, MEMBER_ID)).thenReturn(Optional.of(false), Optional.of(true));

    revocations.retryDue();

    verify(matrix, never()).removeUserFromRoom(any(), any(), any());
    verify(matrix).leaveRoom(ROOM, "member-token");
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

    verify(matrix, never()).removeUserFromRoom(any(), any(), any());
    verify(matrix, never()).leaveRoom(any(), any());
    verify(tasks).delete(task);
  }

  @Test
  void certificateOrOperatorServiceFailureStillMovesTheRetryPastOtherTasks() {
    var task = queuedTask();
    ready(task);
    when(sessions.findByIdForUpdate(SESSION_ID))
        .thenReturn(Optional.of(enquiry(SESSION_ID, SessionStatus.NEW)));
    when(matrix.loginAsUserAccessToken(OPERATOR_ID)).thenThrow(new IllegalStateException());

    revocations.retryDue();

    assertThat(task.getRetryAt()).isEqualTo(now().plus(AgencyEnquiryRevocations.RETRY_DELAY));
    verify(tasks, never()).delete(task);
    verify(matrix, never()).removeUserFromRoom(any(), any(), any());
  }

  @Test
  void exceptionDuringRemovalMovesTheRetryPastOtherTasks() {
    var task = queuedTask();
    ready(task);
    when(sessions.findByIdForUpdate(SESSION_ID))
        .thenReturn(Optional.of(enquiry(SESSION_ID, SessionStatus.NEW)));
    when(matrix.removeUserFromRoom(ROOM, MEMBER_ID, OPERATOR_TOKEN))
        .thenThrow(new IllegalStateException());

    revocations.retryDue();

    assertThat(task.getRetryAt()).isEqualTo(now().plus(AgencyEnquiryRevocations.RETRY_DELAY));
    verify(tasks, never()).delete(task);
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
    verify(matrix).isOutOfRoom(ROOM, MEMBER_ID);
    assertThat(task.getRetryAt()).isEqualTo(now().plus(AgencyEnquiryRevocations.RETRY_DELAY));
    verify(matrix, never()).removeUserFromRoom(any(), any(), any());
  }

  @Test
  void confirmedAbsenceCompletesAnUnacknowledgedKick() {
    var task = queuedTask();
    ready(task);
    when(sessions.findByIdForUpdate(SESSION_ID))
        .thenReturn(Optional.of(enquiry(SESSION_ID, SessionStatus.INITIAL)));
    when(matrix.isOutOfRoom(ROOM, MEMBER_ID)).thenReturn(Optional.of(true));

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
    when(matrix.loginAsUserAccessToken(OPERATOR_ID)).thenReturn(OPERATOR_TOKEN);
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
