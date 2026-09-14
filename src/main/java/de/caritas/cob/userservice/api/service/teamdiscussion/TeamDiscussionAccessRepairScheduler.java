package de.caritas.cob.userservice.api.service.teamdiscussion;

import de.caritas.cob.userservice.api.facade.TeamDiscussionFacade;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionParticipantRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.service.session.AgencyLateJoinerMembershipService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reconciles persisted access decisions even when every browser stays open or disconnects. */
@Component
@Profile("!testing")
@RequiredArgsConstructor
@Slf4j
public class TeamDiscussionAccessRepairScheduler {
  private final TeamDiscussionRepository discussions;
  private final TeamDiscussionParticipantRepository participants;
  private final SessionRepository sessions;
  private final ConsultantRepository consultants;
  private final ConsultantAgencyRepository agencies;
  private final TeamDiscussionFacade facade;
  private final AgencyLateJoinerMembershipService membership;
  private final ScheduledTaskClaimService claims;

  @Scheduled(fixedDelayString = "${team.discussion.access-repair.delay-ms:60000}")
  public void reconcileAccess() {
    var lease = claims.tryClaimLease("team-discussion-access-repair", Duration.ofMinutes(2));
    if (lease.isEmpty()) return;
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      for (var discussion : discussions.findPendingArchiveRepairs(SessionStatus.NEW)) {
        try {
          sessions
              .findById(discussion.getSessionId())
              .ifPresent(facade::archiveDiscussionIfPresent);
        } catch (RuntimeException ex) {
          log.warn("Team archive repair remains pending for discussion {}", discussion.getId());
        }
      }
      for (var participant : participants.findParticipantsWithoutAgencyAccess()) {
        try {
          var discussion = discussions.findById(participant.getTeamDiscussionId()).orElse(null);
          if (discussion == null) continue;
          var session = sessions.findById(discussion.getSessionId()).orElse(null);
          if (session == null || session.getAgencyId() == null) continue;
          // Re-read eligibility: a colleague may have been assigned again since the query.
          if (agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
              participant.getConsultantId(), session.getAgencyId())) continue;
          var consultant = consultants.findById(participant.getConsultantId()).orElse(null);
          if (consultant == null) continue;
          if (membership.removeConsultantFromTeamRoom(
              consultant, session.getAgencyId(), discussion.getMatrixRoomId()))
            participants.deleteById(participant.getId());
        } catch (RuntimeException ex) {
          log.warn(
              "Team membership repair remains pending for participant {}", participant.getId());
        }
      }
    } finally {
      TenantContext.clear();
      claims.release(lease.get());
    }
  }
}
