package de.caritas.cob.userservice.api.workflow.delete.service;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Removes a team discussion completely: the Matrix room, the participant rows and the {@code
 * team_discussion} row itself (#1118).
 *
 * <p>The Matrix room goes first. If Synapse refuses to purge it, the database row is deliberately
 * kept: it is the only handle that still names the room, so a later run (session deletion again, or
 * the orphan clean-up) can retry. A room Synapse no longer knows counts as purged, see {@link
 * MatrixSynapseService#purgeRoom(String)}.
 *
 * <p>The participant rows and the discussion row go together in one transaction of their own
 * ({@link TeamDiscussionDeletionWriter}), opened only after the Synapse call has returned.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamDiscussionPurgeService {

  static final String MATRIX_ROOM_ERROR_REASON = "Unable to purge team discussion Matrix room";
  static final String DATABASE_ERROR_REASON = "Unable to delete team discussion";

  private final @NonNull TeamDiscussionDeletionWriter deletionWriter;
  private final @NonNull MatrixSynapseService matrixSynapseService;

  /**
   * Purges the Matrix room of the discussion, then deletes its participants and the row.
   *
   * @param discussion the discussion to remove
   * @param workflowErrors collects what could not be removed; nothing is thrown
   * @return true if the discussion is gone from Matrix and the database
   */
  public boolean purge(TeamDiscussion discussion, List<DeletionWorkflowError> workflowErrors) {
    if (!purgeMatrixRoom(discussion, workflowErrors)) {
      return false;
    }
    return deleteRows(discussion, workflowErrors);
  }

  private boolean purgeMatrixRoom(
      TeamDiscussion discussion, List<DeletionWorkflowError> workflowErrors) {
    var matrixRoomId = discussion.getMatrixRoomId();
    if (isBlank(matrixRoomId) || matrixSynapseService.purgeRoom(matrixRoomId)) {
      return true;
    }
    log.error(
        "UserService delete workflow error: Unable to purge Matrix room {} of team discussion {}",
        matrixRoomId,
        discussion.getId());
    workflowErrors.add(
        DeletionWorkflowError.builder()
            .deletionSourceType(ASKER)
            .deletionTargetType(DeletionTargetType.MATRIX)
            .identifier(matrixRoomId)
            .reason(MATRIX_ROOM_ERROR_REASON)
            .timestamp(nowInUtc())
            .build());
    return false;
  }

  private boolean deleteRows(
      TeamDiscussion discussion, List<DeletionWorkflowError> workflowErrors) {
    try {
      deletionWriter.deleteDiscussionAndParticipants(discussion);
      return true;
    } catch (Exception e) {
      log.error("UserService delete workflow error: ", e);
      workflowErrors.add(
          DeletionWorkflowError.builder()
              .deletionSourceType(ASKER)
              .deletionTargetType(DeletionTargetType.DATABASE)
              .identifier(String.valueOf(discussion.getId()))
              .reason(DATABASE_ERROR_REASON)
              .timestamp(nowInUtc())
              .build());
      return false;
    }
  }
}
