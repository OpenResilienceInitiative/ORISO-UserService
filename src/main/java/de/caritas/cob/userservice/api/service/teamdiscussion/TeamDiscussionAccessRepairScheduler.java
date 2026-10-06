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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
  private final TeamDiscussionParticipantWriter participantWriter;

  private long archiveCursor;
  private long participantCursor;
  private static final Pageable BATCH = PageRequest.of(0, 20);

  @Scheduled(fixedDelayString = "${team.discussion.access-repair.delay-ms:60000}")
  public void reconcileAccess() {
    var lease =
        claims.tryClaimLease(
            AgencyLateJoinerMembershipService.TEAM_ACCESS_REPAIR_TASK, Duration.ofMinutes(2));
    if (lease.isEmpty()) return;
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      var archives = discussions.findPendingArchiveRepairs(SessionStatus.NEW, archiveCursor, BATCH);
      if (archives.isEmpty()) archiveCursor = 0;
      var pending = participants.findAccessRepairs(participantCursor, BATCH);
      if (pending.isEmpty()) participantCursor = 0;
      // Alternate the two kinds so slow archive retries cannot starve access revocations.
      for (int i = 0; i < Math.max(archives.size(), pending.size()); i++) {
        if (i < archives.size()) {
          var discussion = archives.get(i);
          try {
            if (!claims.runIfHeld(
                lease.get(),
                () ->
                    sessions
                        .findById(discussion.getSessionId())
                        .ifPresent(facade::archiveDiscussionIfPresent))) break;
          } catch (RuntimeException ex) {
            log.warn("Team archive repair remains pending for discussion {}", discussion.getId());
          }
          archiveCursor = discussion.getId();
        }
        if (i < pending.size()) {
          var participant = pending.get(i);
          try {
            if (!claims.runIfHeld(lease.get(), () -> reconcileParticipant(participant.getId())))
              break;
          } catch (RuntimeException ex) {
            log.warn(
                "Team membership repair remains pending for participant {}", participant.getId());
          }
          participantCursor = participant.getId();
        }
      }
    } finally {
      TenantContext.clear();
      claims.release(lease.get());
    }
  }

  private void reconcileParticipant(Long participantId) {
    var participant = participants.findById(participantId).orElse(null);
    if (participant == null) return;
    var discussion = discussions.findById(participant.getTeamDiscussionId()).orElse(null);
    if (discussion == null) return;
    var session = sessions.findById(discussion.getSessionId()).orElse(null);
    if (session == null || session.getAgencyId() == null) return;
    if (agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
        participant.getConsultantId(), session.getAgencyId())) {
      if (participant.isAccessRepairRequired()) {
        facade.restoreExistingDiscussionMembership(
            discussion.getId(), participant.getConsultantId());
        participantWriter.setAccessRepairRequired(participantId, false);
      }
      return;
    }
    var consultant = consultants.findById(participant.getConsultantId()).orElse(null);
    if (consultant == null) return;
    // Independent commit before the external side effect survives a crash or reassignment.
    participantWriter.setAccessRepairRequired(participantId, true);
    membership.removeConsultantFromTeamRoom(
        consultant, session.getAgencyId(), discussion.getMatrixRoomId());
  }
}
