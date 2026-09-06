package de.caritas.cob.userservice.api.workflow.teamdiscussionretention.service;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.RoomPurgeOutcome;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Purges team discussions that have outlived their retention period (KDG epic #1010, #1116).
 *
 * <p>Archiving a discussion (ADR-016 hard close) only flips a status and makes the Matrix room
 * read-only; nothing was ever deleted, and the room kept everything the team wrote about the advice
 * seeker in plain text. ADR-016 §3 assumed an archive auto-deletion that never existed; this job is
 * the replacement decided in the addendum of 2026-09-05.
 *
 * <p>The period starts at {@code archive_date}. A discussion that was never archived and is still
 * {@code OPEN} is measured from {@code create_date} under the same period, so an abandoned
 * discussion cannot outlive an archived one. Cutoffs use the same local clock that stamps those
 * columns in {@code TeamDiscussionFacade}.
 *
 * <p>The Matrix room is purged before the row goes. A failed purge keeps the row, so the pointer to
 * a room that may still exist is never lost and the next run retries; a room Synapse no longer
 * knows counts as already purged and does not block the row deletion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamDiscussionRetentionService {

  private final @NonNull TeamDiscussionRepository teamDiscussionRepository;
  private final @NonNull TeamDiscussionPurgeWriter purgeWriter;
  private final @NonNull MatrixSynapseService matrixSynapseService;

  @Value("${team-discussion.archive.retention.days:90}")
  private int retentionDays;

  /** Whether the job is switched on. A period of zero or less disables it entirely. */
  public boolean isEnabled() {
    return retentionDays > 0;
  }

  /**
   * Purges every discussion past its retention period and writes one summary line per run.
   *
   * <p>Not transactional on purpose: each discussion is removed in its own transaction by {@link
   * TeamDiscussionPurgeWriter} once its room is gone, so one failure neither rolls back the others
   * nor holds a transaction open across the Synapse call.
   */
  public void purgeExpiredDiscussions() {
    if (!isEnabled()) {
      return;
    }
    LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

    List<TeamDiscussion> expired = new ArrayList<>();
    expired.addAll(
        teamDiscussionRepository.findByStatusAndArchiveDateBefore(
            TeamDiscussion.Status.ARCHIVED, cutoff));
    expired.addAll(
        teamDiscussionRepository.findByStatusAndCreateDateBefore(
            TeamDiscussion.Status.OPEN, cutoff));

    Map<String, int[]> countsByTenant = new TreeMap<>();
    int purged = 0;
    int failures = 0;
    for (TeamDiscussion discussion : expired) {
      int[] counts = countsByTenant.computeIfAbsent(tenantKey(discussion), key -> new int[2]);
      if (purge(discussion)) {
        purged++;
        counts[0]++;
      } else {
        failures++;
        counts[1]++;
      }
    }

    log.info(
        "Team discussion retention run: retentionDays={}, cutoff={}, tenants={}, purged={},"
            + " purgeFailures={}",
        retentionDays,
        cutoff,
        summarise(countsByTenant),
        purged,
        failures);
  }

  private boolean purge(TeamDiscussion discussion) {
    RoomPurgeOutcome outcome =
        matrixSynapseService.purgeRoomOrConfirmGone(discussion.getMatrixRoomId());
    if (outcome == RoomPurgeOutcome.FAILED) {
      log.warn(
          "Team discussion {} (room {}, tenant {}) kept: Matrix purge failed, will retry next run",
          discussion.getId(),
          discussion.getMatrixRoomId(),
          discussion.getTenantId());
      return false;
    }
    try {
      purgeWriter.deleteDiscussionAndParticipants(discussion);
      return true;
    } catch (RuntimeException ex) {
      log.error(
          "Team discussion {} (room {}, tenant {}) row could not be deleted after the room purge: {}",
          discussion.getId(),
          discussion.getMatrixRoomId(),
          discussion.getTenantId(),
          ex.getMessage());
      return false;
    }
  }

  private static String tenantKey(TeamDiscussion discussion) {
    return discussion.getTenantId() == null ? "none" : String.valueOf(discussion.getTenantId());
  }

  private static String summarise(Map<String, int[]> countsByTenant) {
    if (countsByTenant.isEmpty()) {
      return "{}";
    }
    StringBuilder out = new StringBuilder("{");
    countsByTenant.forEach(
        (tenant, counts) ->
            out.append(tenant)
                .append(": purged=")
                .append(counts[0])
                .append(" failed=")
                .append(counts[1])
                .append(", "));
    out.setLength(out.length() - 2);
    return out.append("}").toString();
  }
}
