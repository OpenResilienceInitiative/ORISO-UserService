package de.caritas.cob.userservice.api.workflow.delete.action;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.List;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Action to delete a user account and rooms in Matrix Synapse. */
@Slf4j
@Component
public abstract class DeleteMatrixUserAction {

  protected static final String ERROR_REASON = "Unable to delete Matrix user account";

  private final @NonNull MatrixSynapseService matrixSynapseService;

  protected DeleteMatrixUserAction(@NonNull MatrixSynapseService matrixSynapseService) {
    this.matrixSynapseService = matrixSynapseService;
  }

  protected void deactivateMatrixUser(String matrixUserId) {
    if (isNotBlank(matrixUserId) && !matrixSynapseService.deactivateUser(matrixUserId)) {
      throw new IllegalStateException(ERROR_REASON);
    }
  }

  protected void purgeMatrixRoom(String matrixRoomId) {
    if (isNotBlank(matrixRoomId) && !matrixSynapseService.purgeRoom(matrixRoomId)) {
      throw new IllegalStateException(ERROR_REASON);
    }
  }

  protected void appendErrorsForSourceType(
      List<DeletionWorkflowError> workflowErrors,
      DeletionSourceType deletionSourceType,
      String matrixUserId,
      Exception e) {
    log.error("UserService delete workflow error: ", e);
    workflowErrors.add(
        DeletionWorkflowError.builder()
            .deletionSourceType(deletionSourceType)
            .deletionTargetType(DeletionTargetType.MATRIX)
            .identifier(matrixUserId)
            .reason(ERROR_REASON)
            .timestamp(nowInUtc())
            .build());
  }
}
