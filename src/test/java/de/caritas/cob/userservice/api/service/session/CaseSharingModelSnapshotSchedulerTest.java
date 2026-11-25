package de.caritas.cob.userservice.api.service.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.CaseSharingModel;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.CaseSharingGate;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** FE#2494 *4: cases created before the stamp keep the model in force at the snapshot. */
class CaseSharingModelSnapshotSchedulerTest {

  private final SessionRepository sessions = mock(SessionRepository.class);
  private final CaseSharingGate gate = mock(CaseSharingGate.class);
  private final ScheduledTaskClaimService claims = mock(ScheduledTaskClaimService.class);
  private final CaseSharingModelSnapshotScheduler scheduler =
      new CaseSharingModelSnapshotScheduler(sessions, gate, claims);
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
    when(gate.currentModel(5L, 1L)).thenReturn(Optional.of(CaseSharingModel.NONE));

    scheduler.snapshot();

    verify(sessions).stampCaseSharingModel(1L, CaseSharingModel.NONE);
    verify(sessions).stampCaseSharingModel(2L, CaseSharingModel.NONE);
    verify(gate, times(1)).currentModel(5L, 1L);
  }

  @Test
  void aRunningCaseIsNotTurnedIntoATeamCaseByATeamCentre() {
    givenUnstamped(case_(1L, 5L, false));
    when(gate.currentModel(5L, 1L)).thenReturn(Optional.of(CaseSharingModel.TEAM_COUNSELLING));

    scheduler.snapshot();

    verify(sessions).stampCaseSharingModel(1L, CaseSharingModel.CASE_HANDOVER);
  }

  @Test
  void aTeamCaseIsStampedAsTeamberatungWithoutReadingTheCentre() {
    givenUnstamped(case_(1L, 5L, true));

    scheduler.snapshot();

    verify(sessions).stampCaseSharingModel(1L, CaseSharingModel.TEAM_COUNSELLING);
    verify(gate, never()).currentModel(any(), any());
  }

  @Test
  void anUnreadableCentreLeavesTheCaseForTheNextRun() {
    givenUnstamped(case_(1L, 5L, false));
    when(gate.currentModel(5L, 1L)).thenReturn(Optional.empty());

    scheduler.snapshot();

    verify(sessions, never()).stampCaseSharingModel(any(), any());
  }

  @Test
  void doesNothingWithoutTheLease() {
    when(claims.tryClaimLease(any(), any())).thenReturn(Optional.empty());

    scheduler.snapshot();

    verify(sessions, never()).findUnstampedCaseIds(org.mockito.ArgumentMatchers.anyLong(), any());
  }

  private void givenUnstamped(Session... cases) {
    when(sessions.findUnstampedCaseIds(0L, CaseSharingModelSnapshotScheduler.BATCH))
        .thenReturn(java.util.Arrays.stream(cases).map(Session::getId).toList());
    for (Session session : cases) {
      when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
    }
  }

  private static Session case_(Long id, Long agencyId, boolean team) {
    var session = new Session();
    session.setId(id);
    session.setAgencyId(agencyId);
    session.setTenantId(1L);
    session.setTeamSession(team);
    session.setConversationType(ConversationType.AGENCY_COUNSELLING);
    return session;
  }
}
