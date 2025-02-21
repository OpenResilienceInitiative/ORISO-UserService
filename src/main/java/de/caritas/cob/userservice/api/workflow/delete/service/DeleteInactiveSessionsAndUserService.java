package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.service.provider.InactivePrivateGroupsProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.persistence.NonUniqueResultException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Service to trigger deletion of inactive sessions and asker accounts. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeleteInactiveSessionsAndUserService {

  private final @NonNull UserRepository userRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull DeleteUserAccountService deleteUserAccountService;
  private final @NonNull WorkflowErrorMailService workflowErrorMailService;
  private final @NonNull WorkflowErrorLogService workflowErrorLogService;
  private final @NonNull DeleteSessionService deleteSessionService;
  private final @NonNull InactivePrivateGroupsProvider inactivePrivateGroupsProvider;

  private static final String USER_NOT_FOUND_REASON = "User could not be found.";
  private static final String RC_SESSION_GROUP_NOT_FOUND_REASON =
      "Session with rc group id could not be found.";

  private static final int CHUNK_SIZE = 1000;

  /**
   * Deletes all inactive sessions and even the asker accounts, if there are no more active
   * sessions.
   */
  public void deleteInactiveSessionsAndUsers() {

    Map<String, List<String>> userWithInactiveGroupsMap =
        inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupsMap();

    log.info("Total users with inactive groups: " + userWithInactiveGroupsMap.size());
    List<Entry<String, List<String>>> entries =
        new ArrayList<>(userWithInactiveGroupsMap.entrySet());
    int totalChunks = (int) Math.ceil((double) entries.size() / CHUNK_SIZE);

    List<DeletionWorkflowError> workflowErrors = new ArrayList<>();

    log.info("Total chunks to process: " + totalChunks);
    for (int i = 0; i < totalChunks; i++) {
      int start = i * CHUNK_SIZE;
      int end = Math.min(start + CHUNK_SIZE, entries.size());
      List<Entry<String, List<String>>> chunk = entries.subList(start, end);

      log.info("Processing chunk number: " + (i + 1));

      List<DeletionWorkflowError> chunkErrors =
          chunk.stream()
              .map(this::performDeletionWorkflow)
              .flatMap(Collection::stream)
              .collect(Collectors.toList());

      workflowErrors.addAll(chunkErrors);
    }

    findWorkflowErrorByReason(workflowErrors);
  }

  private void findWorkflowErrorByReason(List<DeletionWorkflowError> workflowErrors) {
    if (isNotEmpty(workflowErrors)) {
      List<DeletionWorkflowError> rcSessionGroupNotFoundWorkflowErrors =
          getSameReasonWorkflowErrors(workflowErrors, RC_SESSION_GROUP_NOT_FOUND_REASON);
      List<DeletionWorkflowError> workflowErrorsExceptSessionGroupNotFound =
          new ArrayList<>(workflowErrors);
      workflowErrorsExceptSessionGroupNotFound.removeAll(rcSessionGroupNotFoundWorkflowErrors);
      this.workflowErrorLogService.logWorkflowErrors(rcSessionGroupNotFoundWorkflowErrors);
      this.workflowErrorMailService.buildAndSendErrorMail(workflowErrorsExceptSessionGroupNotFound);
    }
  }

  private static List<DeletionWorkflowError> getSameReasonWorkflowErrors(
      List<DeletionWorkflowError> workflowErrors, String reason) {
    return workflowErrors.stream()
        .filter(error -> reason.equals(error.getReason()))
        .collect(Collectors.toList());
  }

  List<DeletionWorkflowError> performDeletionWorkflow(
      Entry<String, List<String>> userInactiveGroupEntry) {

    List<DeletionWorkflowError> workflowErrors = new ArrayList<>();

    try {
      List<User> users =
          userRepository.findAllByRcUserIdAndDeleteDateIsNull(userInactiveGroupEntry.getKey());
      if (users.isEmpty()) {
        log.info(
            "User with rcUserId: {} not found in users table. Will try to delete it's sessions from db and rocketchat.",
            userInactiveGroupEntry.getKey());
        workflowErrors.addAll(
            performUserSessionDeletionForNonExistingUser(userInactiveGroupEntry.getValue()));
      } else {
        log.info(
            "User with rcUserId: {} found in users table. Will try to delete it's sessions from db and rocketchat.",
            userInactiveGroupEntry.getKey());
        users.stream()
            .forEach(
                u -> workflowErrors.addAll(deleteInactiveGroupsOrUser(userInactiveGroupEntry, u)));
      }
    } catch (NonUniqueResultException ex) {
      log.error(
          "Non unique result for findByRcUserIdAndDeleteDateIsNull found. RcUserId:",
          userInactiveGroupEntry.getKey());
      return workflowErrors;
    } catch (Exception ex) {
      log.info(
          "Skip deleting user-session for user with rcUserId: {}, unexpected error occurred while deleting user with rcUserId: {}",
          userInactiveGroupEntry.getKey(),
          ex);
    }
    return workflowErrors;
  }

  private List<DeletionWorkflowError> deleteInactiveGroupsOrUser(
      Entry<String, List<String>> userInactiveGroupEntry, User user) {

    List<Session> userSessionList = sessionRepository.findByUser(user);
    if (allSessionsOfUserAreInactive(userInactiveGroupEntry, userSessionList)) {
      log.info(
          "All sessions of user with rcUserId: {} are inactive. Will try to delete user account.",
          userInactiveGroupEntry.getKey());
      return deleteUserAccountService.performUserDeletion(user);
    }
    return perfomUserSessionDeletion(userInactiveGroupEntry, userSessionList);
  }

  private List<DeletionWorkflowError> perfomUserSessionDeletion(
      Entry<String, List<String>> userInactiveGroupEntry, List<Session> userSessionList) {
    return userInactiveGroupEntry.getValue().stream()
        .map(rcGroupId -> performSessionDeletion(rcGroupId, userSessionList))
        .flatMap(Collection::stream)
        .collect(Collectors.toList());
  }

  private boolean allSessionsOfUserAreInactive(
      Entry<String, List<String>> userInactiveGroupEntry, List<Session> userSessionList) {
    return userInactiveGroupEntry.getValue().size() == userSessionList.size();
  }

  private List<DeletionWorkflowError> performSessionDeletion(
      String rcGroupId, List<Session> userSessionList) {
    List<DeletionWorkflowError> workflowErrors = new ArrayList<>();
    try {
      Optional<Session> session = findSessionInUserSessionList(rcGroupId, userSessionList);
      session.ifPresentOrElse(
          s -> workflowErrors.addAll(deleteSessionService.performSessionDeletion(s)),
          () ->
              workflowErrors.addAll(
                  deleteSessionService.performRocketchatSessionDeletion(rcGroupId)));
    } catch (Exception ex) {
      log.info(
          "Skip deleting user-session for user with rcGroupId: {}, unexpected error occurred while deleting user with rcGroupId: {}",
          rcGroupId,
          ex);
    }

    return workflowErrors;
  }

  private List<DeletionWorkflowError> performUserSessionDeletionForNonExistingUser(
      List<String> rcGroupIds) {
    List<DeletionWorkflowError> workflowErrors = new ArrayList<>();
    rcGroupIds.forEach(
        rcGroupId ->
            workflowErrors.addAll(performUserSessionDeletionForNonExistingUser(rcGroupId)));
    return workflowErrors;
  }

  private Collection<? extends DeletionWorkflowError> performUserSessionDeletionForNonExistingUser(
      String rcGroupId) {
    List<DeletionWorkflowError> workflowErrors = new ArrayList<>();
    try {
      Optional<Session> session = sessionRepository.findByGroupId(rcGroupId);
      if (session.isPresent()) {
        workflowErrors.addAll(deleteSessionService.performSessionDeletion(session.get()));
      } else {
        workflowErrors.addAll(deleteSessionService.performRocketchatSessionDeletion(rcGroupId));
      }
      return workflowErrors;
    } catch (Exception ex) {
      log.info(
          "Skip deleting user-session for user with rcGroupId: {}, unexpected error occurred while deleting user with rcGroupId: {}",
          rcGroupId,
          ex);
      return workflowErrors;
    }
  }

  private Optional<Session> findSessionInUserSessionList(
      String rcGroupId, List<Session> userSessionList) {

    return userSessionList.stream()
        .filter(s -> s.getGroupId() != null && s.getGroupId().equals(rcGroupId))
        .findFirst();
  }
}
