package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.actions.ActionCommandMockProvider;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.adapters.keycloak.*;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.*;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;

/** Actual scheduled-service boundary; a missing C capability cannot authorize downstream cleanup. */
class DeleteUserAccountScenarioCBoundaryTest {
  private final IdentityClientConfig config = mock(IdentityClientConfig.class);
  private final KeycloakClient nativeClient = mock(KeycloakClient.class);
  private final UserResource nativeUser = mock(UserResource.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final ActionsRegistry registry = mock(ActionsRegistry.class);
  private final WorkflowErrorMailService mail = mock(WorkflowErrorMailService.class);
  private final DeletionLifecycleService lifecycle = new DeletionLifecycleService();
  private final UserHardDeleteClaimService claim = new UserHardDeleteClaimService(users, lifecycle);
  private final ActionCommandMockProvider actions = new ActionCommandMockProvider();
  private final DeleteUserAccountService service =
      new DeleteUserAccountService(users, consultants, registry, mail, lifecycle, claim);

  private void eligiblePersistedAsker(boolean fiveActors) {
    when(config.isFiveActorMode()).thenReturn(fiveActors);
    var nativeUsers = mock(UsersResource.class);
    when(nativeClient.getUsersResource()).thenReturn(nativeUsers);
    when(nativeUsers.get("11111111-1111-4111-8111-111111111111")).thenReturn(nativeUser);
    var identity =
        new KeycloakService(
            new AuthenticatedUser(),
            mock(UserAccountInputValidator.class),
            config,
            nativeClient,
            mock(KeycloakMapper.class),
            mock(UserHelper.class),
            mock(KeycloakAuthClient.class));
    actions.setCustomClassForAction(
        DeleteKeycloakAskerAction.class, new DeleteKeycloakAskerAction(identity));
    when(registry.buildContainerForType(AskerDeletionWorkflowDTO.class))
        .thenAnswer(ignored -> actions.getActionContainer(AskerDeletionWorkflowDTO.class));
    var user = new User();
    user.setUserId("11111111-1111-4111-8111-111111111111");
    user.setTenantId(7L);
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC).minusDays(4));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
    when(users.findAllByDeleteDateNotNull()).thenReturn(List.of(user));
    when(users.findByUserIdForUpdate(user.getUserId())).thenReturn(Optional.of(user));
    when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void fiveActorsMissingScopedDeletionStopsBeforeOwnedDownstreamCleanup() {
    eligiblePersistedAsker(true);
    service.deleteUserAccounts();
    verifyNoInteractions(nativeClient);
    verify(mail).buildAndSendErrorMail(argThat(errors -> errors.size() == 1));
    verifyNoInteractions(
        actions.getActionMock(DeleteMatrixAskerAction.class),
        actions.getActionMock(DeleteDatabaseAskerAction.class));
  }

  @Test
  void legacyModeRetainsItsExistingDeletionWorkflow() {
    eligiblePersistedAsker(false);
    service.deleteUserAccounts();
    verify(nativeUser).remove();
    verify(actions.getActionMock(DeleteDatabaseAskerAction.class)).execute(any());
    verifyNoInteractions(mail);
  }
}
