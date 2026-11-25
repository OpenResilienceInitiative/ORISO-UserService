package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.CaseSharingModel;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository.UnstampedCase;
import de.caritas.cob.userservice.api.service.agency.CaseSharingGate;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** FE#2494 *4: cases created before the stamp keep the model in force at the snapshot. */
class CaseSharingModelSnapshotSchedulerTest {

  private final SessionRepository sessions = mock(SessionRepository.class);
  private final CaseSharingGate gate = mock(CaseSharingGate.class);
  private final ScheduledTaskClaimService claims = mock(ScheduledTaskClaimService.class);
  private final MutableClock clock = new MutableClock();
  private final CaseSharingModelSnapshotScheduler scheduler =
      new CaseSharingModelSnapshotScheduler(sessions, gate, claims, clock, Duration.ofHours(6));
  private final ScheduledTaskClaimService.ClaimLease lease =
      new ScheduledTaskClaimService.ClaimLease(
          CaseSharingModelSnapshotScheduler.TASK, java.time.LocalDateTime.now().plusMinutes(2));

  @BeforeEach
  void setUp() {
    when(claims.tryClaimLease(any(), any())).thenReturn(Optional.of(lease));
    when(claims.runIfHeld(any(), any()))
        .thenAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(1).run();
              return true;
            });
  }

  @Test
  void stampsEachCaseWithItsCentresModelAndReadsEachCentreOnce() {
    givenUnstamped(case_(1L, 5L, false), case_(2L, 5L, false));
    when(gate.currentModel(5L, 1L)).thenReturn(Optional.of(CaseSharingModel.CASE_HANDOVER));

    scheduler.snapshot();

    verify(sessions).stampCaseSharingModel(1L, CaseSharingModel.CASE_HANDOVER);
    verify(sessions).stampCaseSharingModel(2L, CaseSharingModel.CASE_HANDOVER);
    verify(gate, times(1)).currentModel(5L, 1L);
    verify(sessions, never()).findById(any());
  }

  @Test
  void aRunningCaseIsNotTurnedIntoATeamCaseByATeamCentreNorGivenAHandoverRight() {
    givenUnstamped(case_(1L, 5L, false));
    when(gate.currentModel(5L, 1L)).thenReturn(Optional.of(CaseSharingModel.TEAM_COUNSELLING));

    scheduler.snapshot();

    verify(sessions).stampCaseSharingModel(1L, CaseSharingModel.NONE);
  }

  @Test
  void aTeamCaseIsStampedAsTeamberatungWithoutReadingTheCentre() {
    givenUnstamped(case_(1L, 5L, true));

    scheduler.snapshot();

    verify(sessions).stampCaseSharingModel(1L, CaseSharingModel.TEAM_COUNSELLING);
    verify(gate, never()).currentModel(any(), any());
  }

  @Test
  void anUnreachableCentreIsRetriedAndStampedNoneAfterTheRetryLimit() {
    givenUnstamped(case_(1L, 5L, false));
    when(gate.currentModel(5L, 1L)).thenReturn(Optional.empty());

    for (int run = 1; run < CaseSharingModelSnapshotScheduler.MAX_UNREACHABLE_RUNS; run++) {
      scheduler.snapshot();
      scheduler.snapshot(); // end of pass: the pending case is found again, no rest
    }
    verify(sessions, never()).stampCaseSharingModel(any(), any());

    scheduler.snapshot();

    verify(sessions).stampCaseSharingModel(1L, CaseSharingModel.NONE);
  }

  /** CodeRabbit: the budget stays used up, so every later batch of the same centre is stamped. */
  @Test
  void anUnreachableCentreStampsEveryLaterBatchOnceTheBudgetIsUsedUp() {
    var first = case_(1L, 5L, false);
    var second = case_(2L, 5L, false);
    when(sessions.findUnstampedCases(
            0L, RegistrationType.ANONYMOUS, CaseSharingModelSnapshotScheduler.BATCH))
        .thenReturn(List.of(first));
    when(sessions.findUnstampedCases(
            1L, RegistrationType.ANONYMOUS, CaseSharingModelSnapshotScheduler.BATCH))
        .thenReturn(List.of(second));
    when(sessions.findUnstampedCases(
            2L, RegistrationType.ANONYMOUS, CaseSharingModelSnapshotScheduler.BATCH))
        .thenReturn(List.of());
    when(gate.currentModel(5L, 1L)).thenReturn(Optional.empty());

    var stamped = new java.util.ArrayList<Long>();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              stamped.add(invocation.getArgument(0));
              return 1;
            })
        .when(sessions)
        .stampCaseSharingModel(any(), any());

    int runs = 0;
    while (stamped.isEmpty() && runs++ < 50) {
      scheduler.snapshot();
    }
    var firstStamped = stamped.getFirst();

    // The very next batch (at most one pass end in between) is stamped too: the budget stays used
    // up instead of starting again at one.
    scheduler.snapshot();
    scheduler.snapshot();

    assertThat(stamped).contains(firstStamped == 1L ? 2L : 1L);
  }

  /** CodeRabbit: a lease lost before a row is processed neither stamps nor skips that row. */
  @Test
  void aLostLeaseLeavesTheRowForTheNextRun() {
    givenUnstamped(case_(1L, 5L, false));
    when(gate.currentModel(5L, 1L)).thenReturn(Optional.of(CaseSharingModel.CASE_HANDOVER));
    org.mockito.Mockito.doReturn(false).when(claims).runIfHeld(any(), any());

    scheduler.snapshot();

    verify(sessions, never()).stampCaseSharingModel(any(), any());

    org.mockito.Mockito.doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(1).run();
              return true;
            })
        .when(claims)
        .runIfHeld(any(), any());
    scheduler.snapshot();

    // the cursor did not move past the unprocessed row
    verify(sessions, times(2))
        .findUnstampedCases(
            0L, RegistrationType.ANONYMOUS, CaseSharingModelSnapshotScheduler.BATCH);
    verify(sessions).stampCaseSharingModel(1L, CaseSharingModel.CASE_HANDOVER);
  }

  @Test
  void aRowThatKeepsFailingIsStampedNoneAfterTheLimit() {
    givenUnstamped(case_(1L, 5L, false));
    when(gate.currentModel(5L, 1L)).thenThrow(new IllegalStateException("broken row"));

    for (int run = 1; run < CaseSharingModelSnapshotScheduler.MAX_ROW_FAILURES; run++) {
      scheduler.snapshot();
      scheduler.snapshot();
    }
    verify(sessions, never()).stampCaseSharingModel(any(), any());

    scheduler.snapshot();

    verify(sessions).stampCaseSharingModel(1L, CaseSharingModel.NONE);
  }

  @Test
  void aRowThatCannotEvenBeStampedIsSkippedSoTheJobCanRest() {
    givenUnstamped(case_(1L, 5L, false));
    when(gate.currentModel(5L, 1L)).thenThrow(new IllegalStateException("broken row"));
    when(sessions.stampCaseSharingModel(any(), any())).thenThrow(new IllegalStateException("db"));
    for (int run = 0; run < CaseSharingModelSnapshotScheduler.MAX_ROW_FAILURES; run++) {
      scheduler.snapshot();
      scheduler.snapshot();
    }

    scheduler.snapshot(); // the given-up row is skipped: nothing found in this pass
    scheduler.snapshot(); // end of that pass: the job rests
    org.mockito.Mockito.clearInvocations(sessions);
    scheduler.snapshot();

    verify(sessions, never()).findUnstampedCases(anyLong(), any(), any());
  }

  @Test
  void restsAfterAPassThatFoundNothingAndLooksAgainLater() {
    when(sessions.findUnstampedCases(anyLong(), any(), any())).thenReturn(List.of());

    scheduler.snapshot();
    scheduler.snapshot();
    clock.advance(Duration.ofHours(5));
    scheduler.snapshot();

    verify(sessions, times(1)).findUnstampedCases(anyLong(), any(), any());

    clock.advance(Duration.ofHours(2));
    scheduler.snapshot();

    verify(sessions, times(2)).findUnstampedCases(anyLong(), any(), any());
  }

  @Test
  void doesNothingWithoutTheLease() {
    when(claims.tryClaimLease(any(), any())).thenReturn(Optional.empty());

    scheduler.snapshot();

    verify(sessions, never()).findUnstampedCases(anyLong(), any(), any());
  }

  private void givenUnstamped(UnstampedCase... cases) {
    when(sessions.findUnstampedCases(
            0L, RegistrationType.ANONYMOUS, CaseSharingModelSnapshotScheduler.BATCH))
        .thenReturn(List.of(cases));
    when(sessions.findUnstampedCases(
            cases[cases.length - 1].getId(),
            RegistrationType.ANONYMOUS,
            CaseSharingModelSnapshotScheduler.BATCH))
        .thenReturn(List.of());
  }

  private static UnstampedCase case_(Long id, Long agencyId, boolean team) {
    return new UnstampedCase() {
      @Override
      public Long getId() {
        return id;
      }

      @Override
      public Long getAgencyId() {
        return agencyId;
      }

      @Override
      public Long getTenantId() {
        return 1L;
      }

      @Override
      public boolean isTeamSession() {
        return team;
      }
    };
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-11-25T10:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
