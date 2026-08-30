package de.caritas.cob.userservice.api.admin.facade;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerReactivationRequestDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.IdentityReactivator;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
public class AskerUserAdminFacadeTest {

  @InjectMocks private AskerUserAdminFacade askerUserAdminFacade;

  @Mock private IdentityDeactivator identityDeactivator;

  @Mock private IdentityReactivator identityReactivator;

  @Mock private UserService userService;

  @Mock private UsernameTranscoder usernameTranscoder;

  @Mock private DeletionLifecycleService deletionLifecycleService;

  @BeforeEach
  void setUpReactivationTransaction() {
    TenantContext.setCurrentTenant(40L);
    TransactionSynchronizationManager.initSynchronization();
    lenient()
        .when(usernameTranscoder.decodeUsername(any()))
        .thenAnswer(invocation -> invocation.<String>getArgument(0).replace("_at_", "@"));
  }

  @AfterEach
  void clearReactivationContext() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TenantContext.clear();
  }

  @Test
  public void markAskerForDeletion_Should_throwNotFoundException_When_askerDoesNotExist() {
    assertThrows(
        NotFoundException.class,
        () -> {
          when(this.userService.getUser(any())).thenReturn(Optional.empty());

          this.askerUserAdminFacade.markAskerForDeletion("user id");
        });
  }

  @Test
  public void
      markAskerForDeletion_Should_throwConflictException_When_askerIsAlreadyMarkedForDeletion() {
    assertThrows(
        ConflictException.class,
        () -> {
          User user = new User();
          user.setDeleteDate(nowInUtc());
          when(this.userService.getUser(any())).thenReturn(Optional.of(user));

          this.askerUserAdminFacade.markAskerForDeletion("user id");
        });
  }

  @Test
  public void
      markAskerForDeletion_Should_markUserForDeletion_When_askerExistsAndIsNotMarkedForDeletion() {
    User user = new User();
    when(this.userService.getUser(any())).thenReturn(Optional.of(user));
    doAnswer(
            invocation -> {
              invocation.<User>getArgument(0).setDeleteDate(nowInUtc());
              return null;
            })
        .when(this.deletionLifecycleService)
        .beginUserDeletion(any(User.class), any());

    this.askerUserAdminFacade.markAskerForDeletion("user id");

    verify(this.identityDeactivator, times(1)).deactivateUser("user id");
    ArgumentCaptor<User> argumentCaptor = ArgumentCaptor.forClass(User.class);
    verify(this.userService, times(1)).saveUser(argumentCaptor.capture());
    assertThat(argumentCaptor.getValue().getDeleteDate(), notNullValue());
  }

  @Test
  void reactivateAsker_shouldReactivateOnlyTheExactDeletedIdentity() {
    var request = reactivationRequest();
    var user =
        deletedUser("user-1", "marge.simpson_at_dreambau.de", "marge.simpson@dreambau.de", 40L);
    when(userService.findUsersByUsernameIncludingDeleted(request.getUsername()))
        .thenReturn(List.of(user));

    askerUserAdminFacade.reactivateAsker(request);

    verify(identityReactivator)
        .reactivateUser(
            "user-1",
            "marge.simpson@dreambau.de",
            "marge.simpson@dreambau.de",
            40L,
            "NewPassw0rd!");
    verify(deletionLifecycleService).cancelUserDeletion(user);
    verify(userService).saveUser(user);
    completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
    verifyNoInteractions(identityDeactivator);
  }

  @Test
  void reactivateAsker_shouldReturnNotFoundWhenNoUsernameExists() {
    var request = reactivationRequest();
    when(userService.findUsersByUsernameIncludingDeleted(request.getUsername()))
        .thenReturn(List.of());

    assertThrows(NotFoundException.class, () -> askerUserAdminFacade.reactivateAsker(request));

    verifyNoInteractions(identityReactivator);
  }

  @Test
  void reactivateAsker_shouldFailClosedWhenIdentityAttributesDoNotMatch() {
    var request = reactivationRequest();
    var wrongTenant =
        deletedUser("user-1", "marge.simpson_at_dreambau.de", "marge.simpson@dreambau.de", 41L);
    when(userService.findUsersByUsernameIncludingDeleted(request.getUsername()))
        .thenReturn(List.of(wrongTenant));

    assertThrows(ConflictException.class, () -> askerUserAdminFacade.reactivateAsker(request));

    verifyNoInteractions(identityReactivator);
  }

  @Test
  void reactivateAsker_shouldFailClosedWhenMatchingIdentityIsActive() {
    var request = reactivationRequest();
    var active =
        deletedUser("user-1", "marge.simpson_at_dreambau.de", "marge.simpson@dreambau.de", 40L);
    active.setDeleteDate(null);
    when(userService.findUsersByUsernameIncludingDeleted(request.getUsername()))
        .thenReturn(List.of(active));

    assertThrows(ConflictException.class, () -> askerUserAdminFacade.reactivateAsker(request));

    verifyNoInteractions(identityReactivator);
  }

  @Test
  void reactivateAsker_shouldReturnNotFoundWhenLifecycleIsHardDeleted() {
    var request = reactivationRequest();
    var hardDeleted =
        deletedUser("user-1", "marge.simpson_at_dreambau.de", "marge.simpson@dreambau.de", 40L);
    hardDeleted.setDeletionLifecycleState(DeletionLifecycleState.HARD_DELETED);
    when(userService.findUsersByUsernameIncludingDeleted(request.getUsername()))
        .thenReturn(List.of(hardDeleted));

    assertThrows(NotFoundException.class, () -> askerUserAdminFacade.reactivateAsker(request));

    verifyNoInteractions(identityReactivator);
  }

  @Test
  void reactivateAsker_shouldRejectIdentityClaimedByHardDeleteWorkflow() {
    var request = reactivationRequest();
    var claimed =
        deletedUser("user-1", "marge.simpson_at_dreambau.de", "marge.simpson@dreambau.de", 40L);
    claimed.setDeletionLifecycleState(DeletionLifecycleState.HARD_DELETE_IN_PROGRESS);
    when(userService.findUsersByUsernameIncludingDeleted(request.getUsername()))
        .thenReturn(List.of(claimed));

    assertThrows(ConflictException.class, () -> askerUserAdminFacade.reactivateAsker(request));

    verifyNoInteractions(identityReactivator);
  }

  @Test
  void reactivateAsker_shouldFailClosedWhenMatchingIdentityIsAmbiguous() {
    var request = reactivationRequest();
    var first =
        deletedUser("user-1", "marge.simpson_at_dreambau.de", "marge.simpson@dreambau.de", 40L);
    var second =
        deletedUser("user-2", "marge.simpson@dreambau.de", "marge.simpson@dreambau.de", 40L);
    when(userService.findUsersByUsernameIncludingDeleted(request.getUsername()))
        .thenReturn(List.of(first, second));

    assertThrows(ConflictException.class, () -> askerUserAdminFacade.reactivateAsker(request));

    verifyNoInteractions(identityReactivator);
  }

  @Test
  void reactivateAsker_shouldRejectCrossTenantUserAdminContext() {
    TenantContext.setCurrentTenant(41L);

    assertThrows(
        AccessDeniedException.class,
        () -> askerUserAdminFacade.reactivateAsker(reactivationRequest()));

    verifyNoInteractions(userService, identityReactivator);
  }

  @Test
  void reactivateAsker_shouldAllowGlobalTenantContext() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    var request = reactivationRequest();
    var user =
        deletedUser("user-1", "marge.simpson_at_dreambau.de", "marge.simpson@dreambau.de", 40L);
    when(userService.findUsersByUsernameIncludingDeleted(request.getUsername()))
        .thenReturn(List.of(user));

    askerUserAdminFacade.reactivateAsker(request);

    verify(identityReactivator)
        .reactivateUser(
            "user-1",
            "marge.simpson@dreambau.de",
            "marge.simpson@dreambau.de",
            40L,
            "NewPassw0rd!");
  }

  @Test
  void reactivateAsker_shouldDisableKeycloakIdentityWhenDatabaseTransactionRollsBack() {
    var request = reactivationRequest();
    var user =
        deletedUser("user-1", "marge.simpson_at_dreambau.de", "marge.simpson@dreambau.de", 40L);
    when(userService.findUsersByUsernameIncludingDeleted(request.getUsername()))
        .thenReturn(List.of(user));

    askerUserAdminFacade.reactivateAsker(request);
    completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

    verify(identityDeactivator).deactivateUser("user-1");
  }

  @Test
  void reactivateAsker_shouldNotDeactivateIdentityWhenKeycloakReturnsNotFound() {
    var request = reactivationRequest();
    var user =
        deletedUser("user-1", "marge.simpson_at_dreambau.de", "marge.simpson@dreambau.de", 40L);
    when(userService.findUsersByUsernameIncludingDeleted(request.getUsername()))
        .thenReturn(List.of(user));
    doThrow(new NotFoundException("Keycloak identity is missing"))
        .when(identityReactivator)
        .reactivateUser(any(), any(), any(), any(), any());

    assertThrows(NotFoundException.class, () -> askerUserAdminFacade.reactivateAsker(request));
    completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

    verifyNoInteractions(identityDeactivator);
  }

  @Test
  void reactivateAsker_shouldNotDeactivateIdentityWhenKeycloakReturnsConflict() {
    var request = reactivationRequest();
    var user =
        deletedUser("user-1", "marge.simpson_at_dreambau.de", "marge.simpson@dreambau.de", 40L);
    when(userService.findUsersByUsernameIncludingDeleted(request.getUsername()))
        .thenReturn(List.of(user));
    doThrow(new ConflictException("Keycloak identity does not match"))
        .when(identityReactivator)
        .reactivateUser(any(), any(), any(), any(), any());

    assertThrows(ConflictException.class, () -> askerUserAdminFacade.reactivateAsker(request));
    completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

    verifyNoInteractions(identityDeactivator);
  }

  @Test
  void reactivateAsker_shouldNotDeactivateIdentityWhenKeycloakReactivationFails() {
    var request = reactivationRequest();
    var user =
        deletedUser("user-1", "marge.simpson_at_dreambau.de", "marge.simpson@dreambau.de", 40L);
    when(userService.findUsersByUsernameIncludingDeleted(request.getUsername()))
        .thenReturn(List.of(user));
    doThrow(new IllegalStateException("Password policy or enable failed"))
        .when(identityReactivator)
        .reactivateUser(any(), any(), any(), any(), any());

    assertThrows(IllegalStateException.class, () -> askerUserAdminFacade.reactivateAsker(request));
    completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

    verifyNoInteractions(identityDeactivator);
  }

  @Test
  void reactivateAsker_shouldDeactivateIdentityExactlyOnceWhenDatabaseSaveFails() {
    var request = reactivationRequest();
    var user =
        deletedUser("user-1", "marge.simpson_at_dreambau.de", "marge.simpson@dreambau.de", 40L);
    when(userService.findUsersByUsernameIncludingDeleted(request.getUsername()))
        .thenReturn(List.of(user));
    when(userService.saveUser(user)).thenThrow(new IllegalStateException("Database save failed"));

    assertThrows(IllegalStateException.class, () -> askerUserAdminFacade.reactivateAsker(request));
    completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

    verify(identityReactivator)
        .reactivateUser(
            "user-1",
            "marge.simpson@dreambau.de",
            "marge.simpson@dreambau.de",
            40L,
            "NewPassw0rd!");
    verify(identityDeactivator, times(1)).deactivateUser("user-1");
  }

  @Test
  void reactivateAsker_shouldLogRollbackCompensationFailureWithoutMaskingDatabaseFailure() {
    var request = reactivationRequest();
    var user =
        deletedUser("user-1", "marge.simpson_at_dreambau.de", "marge.simpson@dreambau.de", 40L);
    when(userService.findUsersByUsernameIncludingDeleted(request.getUsername()))
        .thenReturn(List.of(user));
    when(userService.saveUser(user)).thenThrow(new IllegalStateException("Database save failed"));
    doThrow(new IllegalStateException("Keycloak disable failed"))
        .when(identityDeactivator)
        .deactivateUser("user-1");

    assertThrows(IllegalStateException.class, () -> askerUserAdminFacade.reactivateAsker(request));
    try (var logs = LogbackCaptor.forClass(AskerUserAdminFacade.class)) {
      completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
      assertThat(
          logs.contains(
              Level.ERROR,
              "Could not disable Keycloak identity after asker reactivation rollback for userId=user-1"),
          org.hamcrest.Matchers.is(true));
    }

    verify(identityDeactivator, times(1)).deactivateUser("user-1");
  }

  private static void completeTransaction(int status) {
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(synchronization -> synchronization.afterCompletion(status));
    TransactionSynchronizationManager.clearSynchronization();
  }

  private static AskerReactivationRequestDTO reactivationRequest() {
    var request = new AskerReactivationRequestDTO();
    request.setUsername("marge.simpson@dreambau.de");
    request.setEmail("marge.simpson@dreambau.de");
    request.setTenantId(40L);
    request.setPassword("NewPassw0rd!");
    return request;
  }

  private static User deletedUser(String id, String username, String email, Long tenantId) {
    var user = new User();
    user.setUserId(id);
    user.setUsername(username);
    user.setEmail(email);
    user.setTenantId(tenantId);
    user.setDeleteDate(nowInUtc());
    return user;
  }
}
