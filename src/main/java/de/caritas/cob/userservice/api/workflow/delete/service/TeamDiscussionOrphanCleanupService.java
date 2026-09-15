package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Removes team discussions whose session is already gone (#1118).
 *
 * <p>Two sources feed this: sessions deleted before the deletion path purged discussions at all,
 * and discussions whose Matrix purge failed during a later session deletion and were kept on
 * purpose so the room stays findable. Both are cleaned up here; a purge that keeps failing is
 * reported through the deletion-workflow error mail like any other deletion failure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamDiscussionOrphanCleanupService {

  private final @NonNull TeamDiscussionRepository teamDiscussionRepository;
  private final @NonNull TeamDiscussionPurgeService teamDiscussionPurgeService;
  private final @NonNull WorkflowErrorMailService workflowErrorMailService;

  /**
   * Purges every discussion that references a session which no longer exists.
   *
   * @return the errors of this run, also sent by mail when there are any
   */
  public List<DeletionWorkflowError> purgeOrphanedDiscussions() {
    var orphans = teamDiscussionRepository.findAllOrphaned();
    if (orphans.isEmpty()) {
      return List.of();
    }

    List<DeletionWorkflowError> workflowErrors = new ArrayList<>();
    var purged = 0;
    for (var orphan : orphans) {
      if (teamDiscussionPurgeService.purge(orphan, workflowErrors)) {
        purged++;
      }
    }
    log.info(
        "Team discussion orphan clean-up purged {} of {} discussions without a session",
        purged,
        orphans.size());

    if (isNotEmpty(workflowErrors)) {
      try {
        workflowErrorMailService.buildAndSendErrorMail(workflowErrors);
      } catch (RuntimeException exception) {
        log.error(
            "Deletion workflow error notification failed; completed deletion results are retained. "
                + "Failure type: {}",
            exception.getClass().getSimpleName());
      }
    }
    return workflowErrors;
  }
}
