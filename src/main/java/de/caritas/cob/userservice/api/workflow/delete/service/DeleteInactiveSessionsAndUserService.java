package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowInfo;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowResult;
import de.caritas.cob.userservice.api.workflow.delete.model.InactiveGroupInfo;
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
   * Deletes all inactive sessions and even the asker accounts, if there are no more active
   * sessions.
   */
  public void deleteInactiveSessionsAndUsers() {

    Map<String, List<InactiveGroupInfo>> userWithInactiveGroupsMap =
        inactivePrivateGroupsProvider.retrieveUserWithInactiveGroupInfoMap();

    log.info("Total users with inactive groups: " + userWithInactiveGroupsMap.size());
    List<Entry<String, List<InactiveGroupInfo>>> entries =
        new ArrayList<>(userWithInactiveGroupsMap.entrySet());
    int totalChunks = (int) Math.ceil((double) entries.size() / CHUNK_SIZE);

    DeletionWorkflowResult deletionWorkflowResult = new DeletionWorkflowResult();

    log.info("Total chunks to process: " + totalChunks);
    for (int i = 0; i < totalChunks; i++) {
      int start = i * CHUNK_SIZE;
      int end = Math.min(start + CHUNK_SIZE, entries.size());
      List<Entry<String, List<InactiveGroupInfo>>> chunk = entries.subList(start, end);

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
      Entry<String, List<InactiveGroupInfo>> userInactiveGroupEntry) {

    DeletionWorkflowResult result = new DeletionWorkflowResult();

    try {
      List<User> users =
          userRepository.findAllByRcUserIdAndDeleteDateIsNull(userInactiveGroupEntry.getKey());
      if (users.isEmpty()) {
        log.info(
            "User with rcUserId: {} not found in users table. Will try to delete it's sessions from db and rocketchat.",
            userInactiveGroupEntry.getKey());
        DeletionWorkflowResult userResult =
            performUserSessionDeletionForNonExistingUser(
                userInactiveGroupEntry.getValue(), userInactiveGroupEntry.getKey());
        addDeletionInfoForUser(
            userResult,
            userInactiveGroupEntry.getKey(),
            "N/A (User not found)",
            userInactiveGroupEntry.getValue());
        result.merge(userResult);
      } else {
        log.info(
            "User with rcUserId: {} found in users table. Will try to delete it's sessions from db and rocketchat.",
            userInactiveGroupEntry.getKey());
        users.forEach(
            user -> result.merge(deleteInactiveGroupsOrUser(userInactiveGroupEntry, user)));
      }
    } catch (NonUniqueResultException ex) {
      log.error(
          "Non unique result for findByRcUserIdAndDeleteDateIsNull found. RcUserId: {}",
          userInactiveGroupEntry.getKey());
      return result;
    } catch (Exception ex) {
      log.info(
          "Skip deleting user-session for user with rcUserId: {}, unexpected error occurred while deleting user with rcUserId: {}",
          userInactiveGroupEntry.getKey(),
          userInactiveGroupEntry.getKey(),
          ex);
    }
    return result;
  }

  private DeletionWorkflowResult deleteInactiveGroupsOrUser(
      Entry<String, List<InactiveGroupInfo>> userInactiveGroupEntry, User user) {

    List<Session> userSessionList = sessionRepository.findByUser(user);
    DeletionWorkflowResult result = new DeletionWorkflowResult();
    if (allSessionsOfUserAreInactive(userInactiveGroupEntry, userSessionList)) {
      log.info(
          "All sessions of user with rcUserId: {} are inactive. Will try to delete user account.",
          userInactiveGroupEntry.getKey());
      List<DeletionWorkflowError> errors = deleteUserAccountService.performUserDeletion(user);
      result.addAll(errors);
    } else {
      result.merge(performUserSessionDeletion(userInactiveGroupEntry, userSessionList));
    }

    addDeletionInfoForUser(
        result, user.getUserId(), user.getUsername(), userInactiveGroupEntry.getValue());
    return result;
  }

  private DeletionWorkflowResult performUserSessionDeletion(
      Entry<String, List<InactiveGroupInfo>> userInactiveGroupEntry,
      List<Session> userSessionList) {
    DeletionWorkflowResult result = new DeletionWorkflowResult();
    userInactiveGroupEntry
        .getValue()
        .forEach(groupInfo -> result.merge(performSessionDeletion(groupInfo, userSessionList)));
    return result;
  }

  private boolean allSessionsOfUserAreInactive(
      Entry<String, List<InactiveGroupInfo>> userInactiveGroupEntry,
      List<Session> userSessionList) {
    return userInactiveGroupEntry.getValue().size() == userSessionList.size();
  }

  private DeletionWorkflowResult performSessionDeletion(
      InactiveGroupInfo groupInfo, List<Session> userSessionList) {
    DeletionWorkflowResult result = new DeletionWorkflowResult();
    try {
      Optional<Session> session =
          findSessionInUserSessionList(groupInfo.getGroupId(), userSessionList);
      List<DeletionWorkflowError> errors;
      if (session.isPresent()) {
        errors = deleteSessionService.performSessionDeletion(session.get());
      } else {
        errors =
            new ArrayList<>(
                deleteSessionService.performRocketchatSessionDeletion(groupInfo.getGroupId()));
      }
      result.addAll(errors);
    } catch (Exception ex) {
      log.info(
          "Skip deleting user-session for user with rcGroupId: {}, unexpected error occurred while deleting user with rcGroupId: {}",
          groupInfo.getGroupId(),
          groupInfo.getGroupId(),
          ex);
    }

    return result;
  }

  private DeletionWorkflowResult performUserSessionDeletionForNonExistingUser(
      List<InactiveGroupInfo> groupInfoList, String rcUserId) {
    DeletionWorkflowResult result = new DeletionWorkflowResult();
    groupInfoList.forEach(
        groupInfo ->
            result.merge(performUserSessionDeletionForNonExistingUser(groupInfo, rcUserId)));
    return result;
  }

  private DeletionWorkflowResult performUserSessionDeletionForNonExistingUser(
      InactiveGroupInfo groupInfo, String rcUserId) {
    DeletionWorkflowResult result = new DeletionWorkflowResult();
    try {
      Optional<Session> session = sessionRepository.findByGroupId(groupInfo.getGroupId());

      List<DeletionWorkflowError> errors;
      if (session.isPresent()) {
        errors = deleteSessionService.performSessionDeletion(session.get());
      } else {
        errors =
            new ArrayList<>(
                deleteSessionService.performRocketchatSessionDeletion(groupInfo.getGroupId()));
      }
      result.addAll(errors);
      return result;
    } catch (Exception ex) {
      log.info(
          "Skip deleting user-session for user with rcGroupId: {}, unexpected error occurred while deleting user with rcGroupId: {}",
          groupInfo.getGroupId(),
          groupInfo.getGroupId(),
          ex);
      return result;
    }
  }

  private void addDeletionInfoForUser(
      DeletionWorkflowResult result,
      String userId,
      String userName,
      List<InactiveGroupInfo> groupInfos) {
    result.add(
        DeletionWorkflowInfo.builder()
            .userId(userId)
            .userName(userName)
            .lastMessageDate(getLastMessageDate(groupInfos))
            .build());
  }

  private static Date getLastMessageDate(List<InactiveGroupInfo> groupInfos) {
    return groupInfos.stream()
        .map(InactiveGroupInfo::getLastMessageDate)
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
