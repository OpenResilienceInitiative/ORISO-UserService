package de.caritas.cob.userservice.api.service.teamdiscussion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.facade.TeamDiscussionFacade;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.session.AgencyLateJoinerMembershipService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TeamDiscussionAccessRepairSchedulerTest {
  @Test
  void retriesFailedRemovalAndClearsItsRecordOnlyAfterMatrixConfirms() {
    var discussions = mock(TeamDiscussionRepository.class);
    var participants = mock(TeamDiscussionParticipantRepository.class);
    var sessions = mock(SessionRepository.class);
    var consultants = mock(ConsultantRepository.class);
    var agencies = mock(ConsultantAgencyRepository.class);
    var facade = mock(TeamDiscussionFacade.class);
    var membership = mock(AgencyLateJoinerMembershipService.class);
    var claims = mock(ScheduledTaskClaimService.class);
    var lease =
        new ScheduledTaskClaimService.ClaimLease(
            "team-discussion-access-repair", LocalDateTime.now().plusMinutes(2));
    when(claims.tryClaimLease(anyString(), any())).thenReturn(Optional.of(lease));
    var discussion =
        TeamDiscussion.builder()
            .id(1L)
            .sessionId(2L)
            .matrixRoomId("!team:test")
            .status(TeamDiscussion.Status.ARCHIVED)
            .build();
    var participant =
        TeamDiscussionParticipant.builder()
            .id(3L)
            .teamDiscussionId(1L)
            .consultantId("removed")
            .build();
    var session = new Session();
    session.setId(2L);
    session.setAgencyId(4L);
    var consultant = new Consultant();
    consultant.setId("removed");
    consultant.setMatrixUserId("@removed:test");
    when(discussions.findPendingArchiveRepairs(Session.SessionStatus.NEW))
        .thenReturn(List.of(discussion));
    when(participants.findParticipantsWithoutAgencyAccess()).thenReturn(List.of(participant));
    when(discussions.findById(1L)).thenReturn(Optional.of(discussion));
    when(sessions.findById(2L)).thenReturn(Optional.of(session));
    when(consultants.findById("removed")).thenReturn(Optional.of(consultant));
    when(membership.removeConsultantFromTeamRoom(consultant, 4L, "!team:test"))
        .thenReturn(false, true);
    var scheduler =
        new TeamDiscussionAccessRepairScheduler(
            discussions, participants, sessions, consultants, agencies, facade, membership, claims);

    scheduler.reconcileAccess();
    verify(participants, never()).deleteById(any());
    scheduler.reconcileAccess();
    verify(participants).deleteById(3L);
    verify(facade, times(2)).archiveDiscussionIfPresent(session);
    verify(claims, times(2)).release(lease);
    assertThat(TenantContext.getCurrentTenant()).isNull();
  }
}
