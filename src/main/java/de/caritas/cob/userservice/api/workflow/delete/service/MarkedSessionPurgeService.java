package de.caritas.cob.userservice.api.workflow.delete.service;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Purges the sessions a deleted Beratungsstelle left with advice seekers who keep their account
 * (ORISO-Admin#1524). Runs in the nightly account-deletion job. The Matrix room goes first: a room
 * that cannot be purged keeps its session, so the next night tries again; a room Synapse no longer
 * knows counts as gone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarkedSessionPurgeService {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull DeleteSessionService deleteSessionService;

  /**
   * @return the errors of this run; the affected sessions stay marked for the next run
   */
  public List<DeletionWorkflowError> purgeMarkedSessions() {
    var errors = new ArrayList<DeletionWorkflowError>();
    for (Session session : sessionRepository.findAllByDeleteDateNotNull()) {
      try {
        purge(session, errors);
      } catch (RuntimeException e) {
        log.error("Could not purge marked session {}", session.getId(), e);
        errors.add(error(session, DeletionTargetType.DATABASE, "Unable to purge marked session"));
      }
    }
    return errors;
  }

  private void purge(Session session, List<DeletionWorkflowError> errors) {
    var roomId = session.getMatrixRoomId();
    if (isNotBlank(roomId) && !matrixSynapseService.purgeRoom(roomId)) {
      errors.add(
          error(session, DeletionTargetType.MATRIX, "Unable to purge Matrix room of session"));
      return;
    }
    errors.addAll(deleteSessionService.performSessionDeletionKeepingFailed(session));
  }

  private static DeletionWorkflowError error(
      Session session, DeletionTargetType target, String reason) {
    return DeletionWorkflowError.builder()
        .deletionSourceType(DeletionSourceType.ASKER)
        .deletionTargetType(target)
        .identifier(String.valueOf(session.getId()))
        .reason(reason)
        .timestamp(nowInUtc())
        .build();
  }
}
