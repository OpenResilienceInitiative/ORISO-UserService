package de.caritas.cob.userservice.api.workflow.deactivate.service;

import static de.caritas.cob.userservice.api.model.Session.RegistrationType.ANONYMOUS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;

import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.actions.session.DeactivateSessionActionCommand;
import de.caritas.cob.userservice.api.actions.session.PostMatrixUserLeftMessageActionCommand;
import de.caritas.cob.userservice.api.actions.session.SendFinishedAnonymousConversationEventActionCommand;
import de.caritas.cob.userservice.api.actions.user.DeactivateKeycloakUserActionCommand;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.livechat.LiveChatAccountClaimService;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Service to trigger deletion of anonymous users. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeactivateAnonymousUserService {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ActionsRegistry actionsRegistry;
  private final @NonNull LiveChatAccountClaimService claims;

  @Value("${user.anonymous.deactivateworkflow.periodMinutes}")
  private long deactivatePeriodMinutes;

  /** Deletes all anonymous users with special constraints. */
  @Transactional
  public void deactivateStaleAnonymousUsers() {
    LocalDateTime deactivationTime = LocalDateTime.now().minusMinutes(deactivatePeriodMinutes);
    List<Session> anonymousSessions =
        this.sessionRepository.findLiveChatSessionsByStatusIn(Set.of(NEW, IN_PROGRESS), ANONYMOUS);

    Set<Session> staleAnonymousSessions =
        anonymousSessions.stream()
            .filter(isSessionOutsideOfDeactivationTime(deactivationTime))
            .collect(Collectors.toSet());

    var claimedUsers =
        staleAnonymousSessions.stream()
            .map(Session::getUser)
            .map(User::getUserId)
            .distinct()
            .filter(this::tryClaimCleanup)
            .collect(Collectors.toSet());
    staleAnonymousSessions.removeIf(
        session -> !claimedUsers.contains(session.getUser().getUserId()));
    deactivateAnonymousUsersAndSessions(staleAnonymousSessions);
  }

  private boolean tryClaimCleanup(String userId) {
    try {
      return claims.claimCleanup(userId);
    } catch (RuntimeException exception) {
      log.warn(
          "Cleanup claim failed; user skipped. Failure type: {}",
          exception.getClass().getSimpleName());
      return false;
    }
  }

  private Predicate<Session> isSessionOutsideOfDeactivationTime(LocalDateTime deactivationTime) {
    return session -> session.getUpdateDate().isBefore(deactivationTime);
  }

  private void deactivateAnonymousUsersAndSessions(Set<Session> staleSessions) {
    Set<User> usersToDeactivate =
        staleSessions.stream().map(Session::getUser).collect(Collectors.toSet());

    this.performUserDeactivationActions(usersToDeactivate);
    this.performSessionDeactivationActions(staleSessions);
  }

  private void performUserDeactivationActions(Set<User> usersToDeactivate) {
    var userDeactivationActions = this.actionsRegistry.buildContainerForType(User.class);
    usersToDeactivate.forEach(
        userToDeactivate ->
            userDeactivationActions
                .addActionToExecute(DeactivateKeycloakUserActionCommand.class)
                .executeActions(userToDeactivate));
  }

  private void performSessionDeactivationActions(Set<Session> staleAnonymousSessions) {
    var sessionDeactivationActions = this.actionsRegistry.buildContainerForType(Session.class);
    staleAnonymousSessions.forEach(
        staleSession ->
            sessionDeactivationActions
                .addActionToExecute(DeactivateSessionActionCommand.class)
                .addActionToExecute(PostMatrixUserLeftMessageActionCommand.class)
                .addActionToExecute(SendFinishedAnonymousConversationEventActionCommand.class)
                .executeActions(staleSession));
  }
}
