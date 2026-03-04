package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowInfo;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowResult;
import de.caritas.cob.userservice.api.workflow.delete.model.InactiveGroup;
import de.caritas.cob.userservice.api.workflow.delete.service.provider.InactivePrivateGroupsProvider;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
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
  private final @NonNull WorkflowResultsMailService workflowResultsMailService;
  private final @NonNull WorkflowErrorLogService workflowErrorLogService;
  private final @NonNull DeleteSessionService deleteSessionService;
  private final @NonNull InactivePrivateGroupsProvider inactivePrivateGroupsProvider;

  private static final String RC_SESSION_GROUP_NOT_FOUND_REASON =
      "Session with rc group id could not be found.";

  private static final int CHUNK_SIZE = 1000;

  /**
   * Deletes all inactive sessions and even the asker accounts if there are no more active sessions.
   */
  public void deleteInactiveSessionsAndUsers() {

    Map<String, List<InactiveGroup>> userWithInactiveGroupsMap =
        inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupInfoMap();

    log.info("Total users with inactive groups: " + userWithInactiveGroupsMap.size());
    List<Entry<String, List<InactiveGroup>>> entries =
        new ArrayList<>(userWithInactiveGroupsMap.entrySet());
    int totalChunks = (int) Math.ceil((double) entries.size() / CHUNK_SIZE);

    DeletionWorkflowResult deletionWorkflowResult = new DeletionWorkflowResult();

    log.info("Total chunks to process: " + totalChunks);
    for (int i = 0; i < totalChunks; i++) {
      int start = i * CHUNK_SIZE;
      int end = Math.min(start + CHUNK_SIZE, entries.size());
      List<Entry<String, List<InactiveGroup>>> chunk = entries.subList(start, end);

      log.info("Processing chunk number: " + (i + 1));

      chunk.forEach(entry -> deletionWorkflowResult.merge(performDeletionWorkflow(entry)));
    }

    processWorkflowResult(deletionWorkflowResult);
  }

  private void processWorkflowResult(DeletionWorkflowResult deletionResult) {
    List<DeletionWorkflowError> deletionErrors = deletionResult.getErrors();
    List<DeletionWorkflowInfo> deletionInfos = deletionResult.getDeletionInfo();

    List<DeletionWorkflowError> rcSessionGroupNotFoundWorkflowErrors =
        getWorkflowErrorsByRcSessionGroupNotFound(deletionErrors);
    List<DeletionWorkflowError> workflowErrorsExceptSessionGroupNotFound =
        new ArrayList<>(deletionErrors);
    workflowErrorsExceptSessionGroupNotFound.removeAll(rcSessionGroupNotFoundWorkflowErrors);

    this.workflowErrorLogService.logWorkflowErrors(rcSessionGroupNotFoundWorkflowErrors);
    this.workflowResultsMailService.buildAndSendMail(
        workflowErrorsExceptSessionGroupNotFound, deletionInfos);
  }

  private List<DeletionWorkflowError> getWorkflowErrorsByRcSessionGroupNotFound(
      List<DeletionWorkflowError> workflowErrors) {
    return workflowErrors.stream()
        .filter(error -> RC_SESSION_GROUP_NOT_FOUND_REASON.equals(error.getReason()))
        .collect(Collectors.toList());
  }

  DeletionWorkflowResult performDeletionWorkflow(
      Entry<String, List<InactiveGroup>> userInactiveGroupsEntry) {

    final DeletionWorkflowResult result = new DeletionWorkflowResult();
    final String rcUserId = userInactiveGroupsEntry.getKey();
    try {
      List<User> users = userRepository.findAllByRcUserIdAndDeleteDateIsNull(rcUserId);
      if (users.isEmpty()) {
        log.info(
            "User with rcUserId: {} not found in users table. Will try to delete it's sessions from db and rocketchat.",
            rcUserId);
        List<DeletionWorkflowError> userErrors =
            performUserSessionDeletionForNonExistingUser(userInactiveGroupsEntry.getValue());
        result.addErrors(userErrors);
      } else {
        log.info(
            "User with rcUserId: {} found in users table. Will try to delete it's sessions from db and rocketchat.",
            rcUserId);
        users.forEach(
            user -> result.merge(deleteInactiveGroupsOrUser(userInactiveGroupsEntry, user)));
      }
    } catch (NonUniqueResultException ex) {
      log.error(
          "Non unique result for findByRcUserIdAndDeleteDateIsNull found. RcUserId: {}", rcUserId);
      return result;
    } catch (Exception ex) {
      log.info(
          "Skip deleting user-session for user with rcUserId: {}, unexpected error occurred while deleting user with rcUserId: {}",
          rcUserId,
          rcUserId,
          ex);
    }
    return result;
  }

  private DeletionWorkflowResult deleteInactiveGroupsOrUser(
      Entry<String, List<InactiveGroup>> userInactiveGroupEntry, User user) {

    List<Session> userSessionList = sessionRepository.findByUser(user);
    DeletionWorkflowResult result = new DeletionWorkflowResult();
    final List<InactiveGroup> inactiveGroups = userInactiveGroupEntry.getValue();
    if (allSessionsOfUserAreInactive(inactiveGroups, userSessionList)) {
      log.info(
          "All sessions of user with rcUserId: {} are inactive. Will try to delete user account.",
          userInactiveGroupEntry.getKey());
      result.addErrors(deleteUserAccountService.performUserDeletion(user));
      result.addInfo(buildUserInfo(inactiveGroups, user));
    } else {
      result.addErrors(performUserSessionDeletion(inactiveGroups, userSessionList));
    }

    return result;
  }

  private DeletionWorkflowInfo buildUserInfo(
      final List<InactiveGroup> inactiveGroups, final User user) {
    return DeletionWorkflowInfo.builder()
        .userId(user.getUserId())
        .userName(user.getUsername())
        .lastMessageDate(getLastMessageDate(inactiveGroups))
        .build();
  }

  private List<DeletionWorkflowError> performUserSessionDeletion(
      List<InactiveGroup> inactiveGroups, List<Session> userSessionList) {
    List<DeletionWorkflowError> errors = new ArrayList<>();
    inactiveGroups.forEach(
        inactiveGroup -> errors.addAll(performSessionDeletion(inactiveGroup, userSessionList)));
    return errors;
  }

  private boolean allSessionsOfUserAreInactive(
      List<InactiveGroup> inactiveGroups, List<Session> userSessionList) {
    return inactiveGroups.size() == userSessionList.size();
  }

  private List<DeletionWorkflowError> performSessionDeletion(
      InactiveGroup groupInfo, List<Session> userSessionList) {
    List<DeletionWorkflowError> errors = new ArrayList<>();
    try {
      Optional<Session> session =
          findSessionInUserSessionList(groupInfo.getGroupId(), userSessionList);
      if (session.isPresent()) {
        errors.addAll(deleteSessionService.performSessionDeletion(session.get()));
      } else {
        errors.addAll(
            deleteSessionService.performRocketchatSessionDeletion(groupInfo.getGroupId()));
      }
    } catch (Exception ex) {
      log.info(
          "Skip deleting user-session for user with rcGroupId: {}, unexpected error occurred while deleting user with rcGroupId: {}",
          groupInfo.getGroupId(),
          groupInfo.getGroupId(),
          ex);
    }

    return errors;
  }

  private List<DeletionWorkflowError> performUserSessionDeletionForNonExistingUser(
      List<InactiveGroup> inactiveGroups) {
    List<DeletionWorkflowError> errors = new ArrayList<>();
    inactiveGroups.forEach(
        inactiveGroup ->
            errors.addAll(performUserSessionDeletionForNonExistingUser(inactiveGroup)));
    return errors;
  }

  private List<DeletionWorkflowError> performUserSessionDeletionForNonExistingUser(
      InactiveGroup inactiveGroup) {
    List<DeletionWorkflowError> errors = new ArrayList<>();
    try {
      Optional<Session> session = sessionRepository.findByGroupId(inactiveGroup.getGroupId());

      if (session.isPresent()) {
        errors.addAll(deleteSessionService.performSessionDeletion(session.get()));
      } else {
        errors.addAll(
            deleteSessionService.performRocketchatSessionDeletion(inactiveGroup.getGroupId()));
      }
      return errors;
    } catch (Exception ex) {
      log.info(
          "Skip deleting user-session for user with rcGroupId: {}, unexpected error occurred while deleting user with rcGroupId: {}",
          inactiveGroup.getGroupId(),
          inactiveGroup.getGroupId(),
          ex);
      return errors;
    }
  }

  private Date getLastMessageDate(List<InactiveGroup> groupInfos) {
    return groupInfos.stream()
        .map(InactiveGroup::getLastMessageDate)
        .filter(Objects::nonNull)
        .max(Date::compareTo)
        .orElse(null);
  }

  private Optional<Session> findSessionInUserSessionList(
      String rcGroupId, List<Session> userSessionList) {

    return userSessionList.stream()
        .filter(s -> s.getGroupId() != null && s.getGroupId().equals(rcGroupId))
        .findFirst();
  }
}
