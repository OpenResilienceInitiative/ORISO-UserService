package de.caritas.cob.userservice.api.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService.JoinAttempt;
import java.util.List;
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

  @Mock private AgencySilentMembershipService membership;
  @Mock private SessionRoomGateway sessionRoomGateway;
  @Mock private MatrixRateLimitPause pause;
  @Mock private Executor executor;

  private AgencyDepartmentMembershipBackfill underTest;

  @BeforeEach
  void setUp() {
    underTest =
        new AgencyDepartmentMembershipBackfill(membership, sessionRoomGateway, pause, executor);
  }

  private static Consultant consultant(String id) {
    var consultant = new Consultant();
    consultant.setId(id);
    return consultant;
  }

  @Test
  @DisplayName("schedule hands the work to the executor and returns without touching Matrix")
  void schedule_runsOnExecutor() {
    underTest.schedule(AGENCY_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(consultant("a")));

    verify(executor).execute(any(Runnable.class));
    verifyNoInteractions(membership, sessionRoomGateway, pause);
  }

  @Test
  @DisplayName("a full queue is logged, never run on the caller's thread")
  void schedule_survivesRejection() {
    org.mockito.Mockito.doThrow(new RejectedExecutionException("full"))
        .when(executor)
        .execute(any(Runnable.class));

    underTest.schedule(AGENCY_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(consultant("a")));

    verifyNoInteractions(membership, sessionRoomGateway);
  }

  @Test
  @DisplayName("nothing deferred, nothing queued")
  void schedule_ignoresEmptyList() {
    underTest.schedule(AGENCY_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of());

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

    var joined = underTest.backfill(AGENCY_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(consultant));

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

    underTest.backfill(AGENCY_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(consultant));

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

    var joined = underTest.backfill(AGENCY_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(stuck, fine));

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

    assertEquals(0, underTest.backfill(AGENCY_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(consultant)));
    verify(membership, times(1)).tryJoinConsultantIntoRoom(consultant, ROOM_ID, AGENCY_TOKEN);
  }

  @Test
  @DisplayName("without an agency token the consultant is retried, never joined with a blank token")
  void backfill_retriesWhenAgencyTokenCannotBeMinted() {
    var consultant = consultant("a");
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(null);
    when(pause.pause(anyLong())).thenReturn(true);

    assertEquals(0, underTest.backfill(AGENCY_ID, ROOM_ID, AGENCY_MATRIX_ID, List.of(consultant)));
    verify(membership, never()).tryJoinConsultantIntoRoom(any(), any(), any());
  }
}
