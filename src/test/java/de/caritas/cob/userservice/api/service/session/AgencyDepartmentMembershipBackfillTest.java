package de.caritas.cob.userservice.api.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService.JoinAttempt;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The background half of the department fan-out: counsellors the registration request could not
 * join without waiting are joined here, waiting exactly as long as Synapse asks, and the ones that
 * still fail are named in the log instead of disappearing.
 */
@ExtendWith(MockitoExtension.class)
class AgencyDepartmentMembershipBackfillTest {

  private static final Long AGENCY_ID = 2L;
  private static final String ROOM_ID = "!holding:example.org";
  private static final String AGENCY_MATRIX_ID = "@agency-2-service:example.org";
  private static final String AGENCY_TOKEN = "agency-token";
  private static final Long SESSION_ID = 371L;

  @Mock private AgencySilentMembershipService membership;
  @Mock private SessionRoomGateway sessionRoomGateway;
  @Mock private MatrixRateLimitPause pause;
  @Mock private Executor executor;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private SessionRepository sessionRepository;

  private AgencyDepartmentMembershipBackfill underTest;
  private Session openEnquiry;

  @BeforeEach
  void setUp() {
    underTest =
        new AgencyDepartmentMembershipBackfill(
            membership,
            sessionRoomGateway,
            pause,
            executor,
            consultantRepository,
            consultantAgencyRepository,
            sessionRepository);
    openEnquiry = new Session();
    openEnquiry.setId(SESSION_ID);
    openEnquiry.setStatus(SessionStatus.INITIAL);
    openEnquiry.setMatrixRoomId(ROOM_ID);
    lenient().when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(openEnquiry));
    lenient()
        .when(
            consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
                anyString(), any()))
        .thenReturn(true);
  }

  /** Queued consultant; the repository hands back the same (still undeleted) instance. */
  private Consultant consultant(String id) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setMatrixUserId("@" + id + ":example.org");
    lenient()
        .when(consultantRepository.findByIdAndDeleteDateIsNull(id))
        .thenReturn(Optional.of(consultant));
    return consultant;
  }

  @Test
  @DisplayName("schedule hands the work to the executor and returns without touching Matrix")
  void schedule_runsOnExecutor() {
    underTest.schedule(AGENCY_ID, SESSION_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(consultant("a")));

    verify(executor).execute(any(Runnable.class));
    verifyNoInteractions(membership, sessionRoomGateway, pause, sessionRepository);
  }

  @Test
  @DisplayName("a full queue is logged, never run on the caller's thread")
  void schedule_survivesRejection() {
    org.mockito.Mockito.doThrow(new RejectedExecutionException("full"))
        .when(executor)
        .execute(any(Runnable.class));

    underTest.schedule(AGENCY_ID, SESSION_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(consultant("a")));

    verifyNoInteractions(membership, sessionRoomGateway);
  }

  @Test
  @DisplayName("nothing deferred, nothing queued")
  void schedule_ignoresEmptyList() {
    underTest.schedule(AGENCY_ID, SESSION_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of());

    verifyNoInteractions(executor);
  }

  @Test
  @DisplayName("a 429 is retried after the server's retry_after_ms until the counsellor joins")
  void backfill_honoursRetryAfterOnRateLimit() {
    var consultant = consultant("a");
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(AGENCY_TOKEN);
    when(pause.pause(anyLong())).thenReturn(true);
    when(membership.tryJoinConsultantIntoRoom(consultant, ROOM_ID, AGENCY_TOKEN))
        .thenReturn(JoinAttempt.rateLimited(3_300L))
        .thenReturn(JoinAttempt.rateLimited(1_500L))
        .thenReturn(JoinAttempt.JOINED);

    var joined =
        underTest.backfill(AGENCY_ID, SESSION_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(consultant));

    assertEquals(1, joined);
    var order = inOrder(pause);
    order.verify(pause).pause(3_300L);
    order.verify(pause).pause(1_500L);
    verify(membership, times(3)).tryJoinConsultantIntoRoom(consultant, ROOM_ID, AGENCY_TOKEN);
  }

  @Test
  @DisplayName("an excessive retry_after_ms is capped so one counsellor cannot stall the room")
  void backfill_capsServerDelay() {
    var consultant = consultant("a");
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(AGENCY_TOKEN);
    when(pause.pause(anyLong())).thenReturn(true);
    when(membership.tryJoinConsultantIntoRoom(consultant, ROOM_ID, AGENCY_TOKEN))
        .thenReturn(JoinAttempt.rateLimited(333_000L))
        .thenReturn(JoinAttempt.JOINED);

    underTest.backfill(AGENCY_ID, SESSION_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(consultant));

    verify(pause).pause(AgencyDepartmentMembershipBackfill.MAX_RATE_LIMIT_WAIT_MS);
  }

  @Test
  @DisplayName(
      "a counsellor that keeps failing is given up after MAX_ATTEMPTS; the others still join")
  void backfill_givesUpAfterMaxAttemptsAndContinues() {
    var stuck = consultant("stuck");
    var fine = consultant("fine");
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(AGENCY_TOKEN);
    when(pause.pause(anyLong())).thenReturn(true);
    when(membership.tryJoinConsultantIntoRoom(stuck, ROOM_ID, AGENCY_TOKEN))
        .thenReturn(JoinAttempt.FAILED);
    when(membership.tryJoinConsultantIntoRoom(fine, ROOM_ID, AGENCY_TOKEN))
        .thenReturn(JoinAttempt.JOINED);

    var joined =
        underTest.backfill(AGENCY_ID, SESSION_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(stuck, fine));

    assertEquals(1, joined);
    verify(membership, times(AgencyDepartmentMembershipBackfill.MAX_ATTEMPTS))
        .tryJoinConsultantIntoRoom(stuck, ROOM_ID, AGENCY_TOKEN);
    // Doubling backoff for non-rate-limit failures, one wait fewer than attempts.
    var order = inOrder(pause);
    order.verify(pause).pause(2_000L);
    order.verify(pause).pause(4_000L);
    order.verify(pause).pause(8_000L);
    order.verify(pause).pause(16_000L);
  }

  @Test
  @DisplayName("an interrupted wait stops the backfill instead of spinning")
  void backfill_stopsWhenInterrupted() {
    var consultant = consultant("a");
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(AGENCY_TOKEN);
    when(pause.pause(anyLong())).thenReturn(false);
    when(membership.tryJoinConsultantIntoRoom(consultant, ROOM_ID, AGENCY_TOKEN))
        .thenReturn(JoinAttempt.rateLimited(1_000L));

    assertEquals(
        0,
        underTest.backfill(AGENCY_ID, SESSION_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(consultant)));
    verify(membership, times(1)).tryJoinConsultantIntoRoom(consultant, ROOM_ID, AGENCY_TOKEN);
  }

  @Test
  @DisplayName("without an agency token the consultant is retried, never joined with a blank token")
  void backfill_retriesWhenAgencyTokenCannotBeMinted() {
    var consultant = consultant("a");
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(null);
    when(pause.pause(anyLong())).thenReturn(true);

    assertEquals(
        0,
        underTest.backfill(AGENCY_ID, SESSION_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(consultant)));
    verify(membership, never()).tryJoinConsultantIntoRoom(any(), any(), any());
  }

  @Test
  @DisplayName("a counsellor detached from the agency while queued is never joined")
  void backfill_skipsConsultantNoLongerInAgency() {
    var detached = consultant("detached");
    when(consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            "detached", AGENCY_ID))
        .thenReturn(false);

    assertEquals(
        0, underTest.backfill(AGENCY_ID, SESSION_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(detached)));
    verifyNoInteractions(membership, pause);
  }

  @Test
  @DisplayName("a deleted counsellor is never joined")
  void backfill_skipsDeletedConsultant() {
    var deleted = new Consultant();
    deleted.setId("deleted");
    when(consultantRepository.findByIdAndDeleteDateIsNull("deleted")).thenReturn(Optional.empty());

    assertEquals(
        0, underTest.backfill(AGENCY_ID, SESSION_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(deleted)));
    verifyNoInteractions(membership);
  }

  @Test
  @DisplayName("once the enquiry is accepted, the department backfill stops")
  void backfill_stopsWhenEnquiryWasAccepted() {
    var queued = consultant("a");
    openEnquiry.setStatus(SessionStatus.IN_PROGRESS);
    openEnquiry.setConsultant(new Consultant());

    assertEquals(
        0, underTest.backfill(AGENCY_ID, SESSION_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(queued)));
    verifyNoInteractions(membership);
  }

  @Test
  @DisplayName("entitlement lost during the join is revoked again right away")
  void backfill_revokesWhenEntitlementEndsDuringJoin() {
    var consultant = consultant("a");
    when(consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            "a", AGENCY_ID))
        .thenReturn(true)
        .thenReturn(false);
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(AGENCY_TOKEN);
    when(membership.tryJoinConsultantIntoRoom(consultant, ROOM_ID, AGENCY_TOKEN))
        .thenReturn(JoinAttempt.JOINED);
    when(sessionRoomGateway.removeUserFromRoom(ROOM_ID, "@a:example.org", AGENCY_TOKEN))
        .thenReturn(true);

    assertEquals(
        0,
        underTest.backfill(AGENCY_ID, SESSION_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(consultant)));
    verify(sessionRoomGateway).removeUserFromRoom(ROOM_ID, "@a:example.org", AGENCY_TOKEN);
  }

  @Test
  @DisplayName(
      "inside a transaction the worker is queued only after commit, so it sees the room id")
  void schedule_waitsForCommit() {
    org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
    try {
      underTest.schedule(
          AGENCY_ID, SESSION_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(consultant("a")));
      verifyNoInteractions(executor);

      org.springframework.transaction.support.TransactionSynchronizationManager
          .getSynchronizations()
          .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
      verify(executor).execute(any(Runnable.class));
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .clearSynchronization();
    }
  }
}
