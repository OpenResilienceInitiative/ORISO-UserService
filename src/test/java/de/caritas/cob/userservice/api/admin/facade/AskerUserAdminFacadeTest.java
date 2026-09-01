package de.caritas.cob.userservice.api.admin.facade;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AskerReactivationRequestDTO;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationOperation;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationRepairService;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationSagaStore;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationUnmutatedException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.identity.IdentityReactivationCompensationException;
import de.caritas.cob.userservice.api.exception.identity.IdentityReactivationUpstreamException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
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

@ExtendWith(MockitoExtension.class)
class AskerUserAdminFacadeTest {

  @InjectMocks private AskerUserAdminFacade askerUserAdminFacade;
  @Mock private IdentityDeactivator identityDeactivator;
  @Mock private IdentityReactivationSagaStore identityReactivationSagaStore;
  @Mock private IdentityReactivationRepairService identityReactivationRepairService;
  @Mock private UserService userService;
  @Mock private UsernameTranscoder usernameTranscoder;
  @Mock private DeletionLifecycleService deletionLifecycleService;

  @BeforeEach
  void setUp() {
    TenantContext.setCurrentTenant(40L);
    lenient()
        .when(usernameTranscoder.decodeUsername(any()))
        .thenAnswer(invocation -> invocation.<String>getArgument(0).replace("_at_", "@"));
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void markAskerForDeletionRejectsMissingAsker() {
    when(userService.getUser(any())).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class, () -> askerUserAdminFacade.markAskerForDeletion("user id"));
  }

  @Test
  void markAskerForDeletionRejectsAlreadyDeletedAsker() {
    User user = new User();
    user.setDeleteDate(nowInUtc());
    when(userService.getUser(any())).thenReturn(Optional.of(user));

    assertThrows(
        ConflictException.class, () -> askerUserAdminFacade.markAskerForDeletion("user id"));
  }

  @Test
  void markAskerForDeletionDisablesIdentityAndPersistsLifecycle() {
    User user = new User();
    when(userService.getUser(any())).thenReturn(Optional.of(user));
    doAnswer(
            invocation -> {
              invocation.<User>getArgument(0).setDeleteDate(nowInUtc());
              return null;
            })
        .when(deletionLifecycleService)
        .beginUserDeletion(any(User.class), any());

    askerUserAdminFacade.markAskerForDeletion("user id");

    verify(identityDeactivator).deactivateUser("user id");
    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(userService).saveUser(saved.capture());
    assertThat(saved.getValue().getDeleteDate(), notNullValue());
  }

  @Test
  void reactivateAskerBeginsDurableClaimBeforeExecutingIdentityMutation() {
    var request = reactivationRequest();
    var operation = operation();
    when(identityReactivationSagaStore.begin(
            request.getUsername(), request.getEmail(), request.getTenantId()))
        .thenReturn(operation);

    askerUserAdminFacade.reactivateAsker(request);

    var inOrder = org.mockito.Mockito.inOrder(identityReactivationSagaStore);
    inOrder
        .verify(identityReactivationSagaStore)
        .begin(request.getUsername(), request.getEmail(), request.getTenantId());
    inOrder
        .verify(identityReactivationSagaStore)
        .reactivateAndCommit(operation, request.getPassword());
    verifyNoInteractions(identityReactivationRepairService);
  }

  @Test
  void reactivateAskerRejectsCrossTenantBeforeCreatingClaim() {
    TenantContext.setCurrentTenant(41L);

    assertThrows(
        AccessDeniedException.class,
        () -> askerUserAdminFacade.reactivateAsker(reactivationRequest()));

    verifyNoInteractions(identityReactivationSagaStore, identityReactivationRepairService);
  }

  @Test
  void reactivateAskerAllowsTechnicalTenant() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    when(identityReactivationSagaStore.begin(any(), any(), any())).thenReturn(operation());

    askerUserAdminFacade.reactivateAsker(reactivationRequest());

    verify(identityReactivationSagaStore).reactivateAndCommit(any(), any());
  }

  @Test
  void reactivateAskerReleasesClaimWithoutDeactivationForIdentityMismatch() {
    var operation = operation();
    when(identityReactivationSagaStore.begin(any(), any(), any())).thenReturn(operation);
    doThrow(new IdentityReactivationUnmutatedException(new ConflictException("identity mismatch")))
        .when(identityReactivationSagaStore)
        .reactivateAndCommit(any(), any());

    assertThrows(
        ConflictException.class, () -> askerUserAdminFacade.reactivateAsker(reactivationRequest()));

    verifyNoInteractions(identityReactivationRepairService);
  }

  @Test
  void reactivateAskerCompensatesRuntimeFailureAgainstSameOperationGeneration() {
    var operation = operation();
    var failure = new IdentityReactivationUpstreamException("Keycloak failed", new Exception());
    when(identityReactivationSagaStore.begin(any(), any(), any())).thenReturn(operation);
    doThrow(failure).when(identityReactivationSagaStore).reactivateAndCommit(any(), any());

    assertThrows(
        IdentityReactivationUpstreamException.class,
        () -> askerUserAdminFacade.reactivateAsker(reactivationRequest()));

    verify(identityReactivationRepairService).compensate(operation, failure);
    verify(identityReactivationSagaStore, never()).abortUnmutated(any());
  }

  @Test
  void reactivateAskerSurfacesFailedCompensationWithDurableClaimRetained() {
    var operation = operation();
    var failure = new IllegalStateException("database commit failed");
    var compensationFailure =
        new IdentityReactivationCompensationException(
            "disable failed", failure, new IllegalStateException("Keycloak unavailable"));
    when(identityReactivationSagaStore.begin(any(), any(), any())).thenReturn(operation);
    doThrow(failure).when(identityReactivationSagaStore).reactivateAndCommit(any(), any());
    doThrow(compensationFailure)
        .when(identityReactivationRepairService)
        .compensate(operation, failure);

    assertThrows(
        IdentityReactivationCompensationException.class,
        () -> askerUserAdminFacade.reactivateAsker(reactivationRequest()));

    verify(identityReactivationRepairService).compensate(operation, failure);
  }

  @Test
  void reactivateAskerDoesNotCompensateWhenClaimCannotBeCreated() {
    when(identityReactivationSagaStore.begin(any(), any(), any()))
        .thenThrow(new ConflictException("pending claim"));

    assertThrows(
        ConflictException.class, () -> askerUserAdminFacade.reactivateAsker(reactivationRequest()));

    verifyNoInteractions(identityReactivationRepairService);
  }

  private static AskerReactivationRequestDTO reactivationRequest() {
    var request = new AskerReactivationRequestDTO();
    request.setUsername("marge.simpson@dreambau.de");
    request.setEmail("marge.simpson@dreambau.de");
    request.setTenantId(40L);
    request.setPassword("NewPassw0rd!");
    return request;
  }

  private static IdentityReactivationOperation operation() {
    return new IdentityReactivationOperation(
        "user-1", "operation-1", "marge.simpson@dreambau.de", "marge.simpson@dreambau.de", 40L);
  }
}
