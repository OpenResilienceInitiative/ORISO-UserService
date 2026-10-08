package de.caritas.cob.userservice.api.workflow.deactivate.service;

import static de.caritas.cob.userservice.api.model.Session.RegistrationType.ANONYMOUS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;

import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.actions.session.DeactivateSessionActionCommand;
import de.caritas.cob.userservice.api.actions.session.PostMatrixUserLeftMessageActionCommand;
import de.caritas.cob.userservice.api.actions.session.SendFinishedAnonymousConversationEventActionCommand;
import de.caritas.cob.userservice.api.actions.user.DeactivateAuthorizedIdentityActionCommand;
import de.caritas.cob.userservice.api.actions.user.IdentityDeactivationTarget;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Service to trigger deletion of anonymous users. */
@Service
@RequiredArgsConstructor
public class DeactivateAnonymousUserService {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ActionsRegistry actionsRegistry;

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
            // The legacy query also returns REGISTERED rows by postcode/username shape.
            // Those heuristics are not lifecycle authority over a registered account.
            .filter(session -> session.getRegistrationType() == ANONYMOUS)
            .filter(isSessionOutsideOfDeactivationTime(deactivationTime))
            .collect(Collectors.toSet());

    deactivateAnonymousUsersAndSessions(staleAnonymousSessions, deactivationTime);
  }

  private Predicate<Session> isSessionOutsideOfDeactivationTime(LocalDateTime deactivationTime) {
    return session -> session.getUpdateDate().isBefore(deactivationTime);
  }

  private void deactivateAnonymousUsersAndSessions(
      Set<Session> staleSessions, LocalDateTime cutoff) {
    var userActions = actionsRegistry.buildContainerForType(IdentityDeactivationTarget.class);
    staleSessions.stream()
        .filter(
            session ->
                sessionRepository.findByUserUserId(session.getUser().getUserId()).stream()
                    .allMatch(owned -> owned.getRegistrationType() == ANONYMOUS))
        .collect(
            Collectors.toMap(
                session -> session.getUser().getUserId(),
                session -> session,
                (first, repeated) -> first))
        .values()
        .forEach(
            session ->
                userActions
                    .addActionToExecute(DeactivateAuthorizedIdentityActionCommand.class)
                    .executeActions(
                        new IdentityDeactivationTarget(
                            session.getUser(),
                            IdentityCommandAuthorization.staleAnonymousSession(session, cutoff))));
    performSessionDeactivationActions(staleSessions);
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
