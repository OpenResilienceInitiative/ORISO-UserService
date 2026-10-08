package de.caritas.cob.userservice.api.conversation.service1.user.anonymous;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_DTO_SUCHT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.conversation.service.user.anonymous.AnonymousUserCreatorService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.facade.CreateUserFacade;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnonymousUserCreatorServiceTest {

  @InjectMocks private AnonymousUserCreatorService anonymousUserCreatorService;
  @Mock private CreateUserFacade createUserFacade;

  @Mock
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityAccountProvisioning
      identityProvisioning;

  @Mock private IdentityAuthentication identityAuthentication;
  @Mock private RollbackFacade rollbackFacade;

  @Test
  void createAnonymousUserCreatesIdentityAccountAndMatrixUser() {
    var createdIdentity = new CreatedIdentity();
    createdIdentity.setUserId("user-id");
    var identityLogin = new IdentityLogin("access-token", 300, 600, "refresh-token");
    var user = new User();

    var receipt =
        new de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands
            .CreationResult(java.util.UUID.randomUUID(), "user-id", "own-proof", "OPEN");
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt);
    when(createUserFacade.updateIdentityAndCreateAccount(anyString(), any(), any()))
        .thenReturn(user);
    when(identityAuthentication.login(USER_DTO_SUCHT.getUsername(), USER_DTO_SUCHT.getPassword()))
        .thenReturn(identityLogin);

    var credentials = anonymousUserCreatorService.createAnonymousUser(USER_DTO_SUCHT, origin());

    verify(identityAuthentication, never()).login(anyString(), anyString());
    credentials = anonymousUserCreatorService.authenticateCreatedUser(USER_DTO_SUCHT, credentials);
    assertThat(credentials.getUserId()).isEqualTo("user-id");
    assertThat(credentials.getAccessToken()).isEqualTo("access-token");
    assertThat(credentials.getExpiresIn()).isEqualTo(300);
    assertThat(credentials.getRefreshToken()).isEqualTo("refresh-token");
    assertThat(credentials.getRefreshExpiresIn()).isEqualTo(600);
    verify(createUserFacade).provisionOwnedMatrixUser(user, USER_DTO_SUCHT.getUsername(), receipt);
    verifyNoInteractions(rollbackFacade);
  }

  @Test
  void createAnonymousUserRollsBackWhenMatrixProvisioningFails() {
    var createdIdentity = new CreatedIdentity();
    createdIdentity.setUserId("user-id");
    var user = new User();

    var receipt =
        new de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands
            .CreationResult(java.util.UUID.randomUUID(), "user-id", "own-proof", "OPEN");
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt);
    when(createUserFacade.updateIdentityAndCreateAccount(anyString(), any(), any()))
        .thenReturn(user);
    doThrow(new InternalServerErrorException("Matrix provisioning failed"))
        .when(createUserFacade)
        .provisionOwnedMatrixUser(user, USER_DTO_SUCHT.getUsername(), receipt);

    assertThatThrownBy(
            () -> anonymousUserCreatorService.createAnonymousUser(USER_DTO_SUCHT, origin()))
        .isInstanceOf(InternalServerErrorException.class);

    verify(rollbackFacade)
        .rollBackUserAccount(
            org.mockito.ArgumentMatchers.argThat(
                rollback -> rollback.getUser() == user && "user-id".equals(rollback.getUserId())));
    verify(identityAuthentication, never()).login(anyString(), anyString());
  }

  @Test
  void loginAfterCommitFailsWithoutMisusingCreatorCompensation() {
    var createdIdentity = new CreatedIdentity();
    createdIdentity.setUserId("user-id");
    var user = new User();

    var receipt =
        new de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands
            .CreationResult(java.util.UUID.randomUUID(), "user-id", "own-proof", "OPEN");
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt);
    when(createUserFacade.updateIdentityAndCreateAccount(anyString(), any(), any()))
        .thenReturn(user);
    when(identityAuthentication.login(USER_DTO_SUCHT.getUsername(), USER_DTO_SUCHT.getPassword()))
        .thenThrow(new BadRequestException("login failed"));

    assertThatThrownBy(
            () ->
                anonymousUserCreatorService.authenticateCreatedUser(
                    USER_DTO_SUCHT,
                    anonymousUserCreatorService.createAnonymousUser(USER_DTO_SUCHT, origin())))
        .isInstanceOf(BadRequestException.class);
    verify(createUserFacade).provisionOwnedMatrixUser(user, USER_DTO_SUCHT.getUsername(), receipt);
    verifyNoInteractions(rollbackFacade);
  }

  private static de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCreationOrigin
      origin() {
    var request =
        de.caritas.cob.userservice.api.adapters.web.dto.UserDTO.builder()
            .username("Anonymous-test")
            .password("fixture-password")
            .termsAccepted("true")
            .postcode("00000")
            .consultingType("1")
            .build();
    return de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCreationOrigin
        .checkedAnonymous(request, null);
  }
}
