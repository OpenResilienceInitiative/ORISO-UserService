package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.SessionTopicRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
abstract class DeleteRoomsAndSessionAction {

  protected final @NonNull SessionRepository sessionRepository;
  protected final @NonNull SessionDataRepository sessionDataRepository;
  protected final @NonNull CaseHandoverRequestRepository caseHandoverRequestRepository;
  protected final @NonNull SessionSupervisorRepository sessionSupervisorRepository;
  protected final @NonNull SessionTopicRepository sessionTopicRepository;

  void deleteSessionData(Session session, List<DeletionWorkflowError> workflowErrors) {
    try {
      var sessionData = this.sessionDataRepository.findBySessionId(session.getId());
      this.sessionDataRepository.deleteAll(sessionData);
    } catch (Exception e) {
      log.error("UserService delete workflow error: ", e);
      workflowErrors.add(
          DeletionWorkflowError.builder()
              .deletionSourceType(ASKER)
              .deletionTargetType(DeletionTargetType.DATABASE)
              .identifier(String.valueOf(session.getId()))
              .reason("Unable to delete session data from session")
              .timestamp(nowInUtc())
              .build());
    }
  }

  void deleteCaseHandoverRequests(Session session, List<DeletionWorkflowError> workflowErrors) {
    try {
      this.caseHandoverRequestRepository.deleteAllBySessionId(session.getId());
    } catch (Exception e) {
      log.error("UserService delete workflow error: ", e);
      workflowErrors.add(
          DeletionWorkflowError.builder()
              .deletionSourceType(ASKER)
              .deletionTargetType(DeletionTargetType.DATABASE)
              .identifier(String.valueOf(session.getId()))
              .reason("Unable to delete case handover requests for session")
              .timestamp(nowInUtc())
              .build());
    }
  }

  void deleteSessionSupervisors(Session session, List<DeletionWorkflowError> workflowErrors) {
    try {
      this.sessionSupervisorRepository.deleteAllBySessionId(session.getId());
    } catch (Exception e) {
      log.error("UserService delete workflow error: ", e);
      workflowErrors.add(
          DeletionWorkflowError.builder()
              .deletionSourceType(ASKER)
              .deletionTargetType(DeletionTargetType.DATABASE)
              .identifier(String.valueOf(session.getId()))
              .reason("Unable to delete supervisors for session")
              .timestamp(nowInUtc())
              .build());
    }
  }

  void deleteSessionTopics(Session session, List<DeletionWorkflowError> workflowErrors) {
    try {
      this.sessionTopicRepository.deleteAllBySessionId(session.getId());
    } catch (Exception e) {
      log.error("UserService delete workflow error: ", e);
      workflowErrors.add(
          DeletionWorkflowError.builder()
              .deletionSourceType(ASKER)
              .deletionTargetType(DeletionTargetType.DATABASE)
              .identifier(String.valueOf(session.getId()))
              .reason("Unable to delete topics for session")
              .timestamp(nowInUtc())
              .build());
    }
  }

  protected void deleteSession(Session session, List<DeletionWorkflowError> workflowErrors) {
    try {
      this.sessionRepository.delete(session);
    } catch (Exception e) {
      log.error("UserService delete workflow error: ", e);
      workflowErrors.add(
          DeletionWorkflowError.builder()
              .deletionSourceType(ASKER)
              .deletionTargetType(DeletionTargetType.DATABASE)
              .identifier(String.valueOf(session.getId()))
              .reason("Unable to delete session")
              .timestamp(nowInUtc())
              .build());
    }
  }

  void performSessionDeletion(Session session, List<DeletionWorkflowError> workflowErrors) {

    deleteSessionData(session, workflowErrors);
    deleteSessionSupervisors(session, workflowErrors);
    deleteSessionTopics(session, workflowErrors);
    deleteCaseHandoverRequests(session, workflowErrors);
    deleteSession(session, workflowErrors);
  }
}
