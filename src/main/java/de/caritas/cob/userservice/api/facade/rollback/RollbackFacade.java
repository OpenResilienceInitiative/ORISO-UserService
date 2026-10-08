package de.caritas.cob.userservice.api.facade.rollback;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.service.UserAgencyService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteUserAccountService;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * Facade for capsuling the steps to roll back an user account.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RollbackFacade {

  private final @NonNull de.caritas.cob.userservice.api.adapters.keycloak.commands
          .IdentityAccountProvisioning
      identityProvisioning;
  private final @NonNull UserAgencyService userAgencyService;
  private final @NonNull SessionService sessionService;
  private final @NonNull UserService userService;

  private final @NonNull DeleteUserAccountService deleteUserAccountService;

  public void rollbackConsultantAccount(Consultant consultant) {
    log.info(
        "Initiating rollback of consultant account. Consultant id: {}",
        consultant.getId(),
        consultant.getUsername());
    identityProvisioning.compensateForLocalRollback(consultant.getId());
    List<DeletionWorkflowError> deletionWorkflowErrors =
        deleteUserAccountService.performConsultantCreationRollback(consultant);
    if (nonNull(deletionWorkflowErrors) && !deletionWorkflowErrors.isEmpty()) {
      deletionWorkflowErrors.stream()
          .forEach(e -> log.error("Consultant delete error during rollback: ", e));
    }
  }

  /**
   * Deletes the provided user in Keycloak, MariaDB and its related session or user-chat/agency
   * relations depending on the provided {@link RollbackUserAccountInformation}.
   *
   * @param rollbackUser {@link RollbackUserAccountInformation}
   */
  public void rollBackUserAccount(RollbackUserAccountInformation rollbackUser) {
    if (rollbackUser.isRollBackUserAccount()) {
      if (rollbackUser.getUserId() == null)
        throw new org.springframework.security.access.AccessDeniedException(
            "Creation rollback requires its owned identity attempt");
      identityProvisioning.compensateForLocalRollback(rollbackUser.getUserId());
    }
    rollbackUserAgency(rollbackUser);
    rollbackSession(rollbackUser);
    rollbackKeycloakAndMariaDbAccount(rollbackUser);
  }

  private void rollbackUserAgency(RollbackUserAccountInformation rollbackUser) {
    if (nonNull(rollbackUser.getUserAgency())) {
      userAgencyService.deleteUserAgency(rollbackUser.getUserAgency());
    }
  }

  private void rollbackSession(RollbackUserAccountInformation rollbackUser) {
    if (nonNull(rollbackUser.getSession())) {
      sessionService.deleteSession(rollbackUser.getSession());
    }
  }

  private void rollbackKeycloakAndMariaDbAccount(RollbackUserAccountInformation rollbackUser) {
    if (rollbackUser.isRollBackUserAccount()) {
      if (nonNull(rollbackUser.getUser())) {
        userService.deleteUser(rollbackUser.getUser());
      }
    }
  }
}
