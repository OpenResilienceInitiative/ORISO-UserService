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
import de.caritas.cob.userservice.api.port.out.IdentityAccountCreated;
import de.caritas.cob.userservice.api.port.out.IdentityAccountCreator;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnonymousUserCreatorServiceTest {

  @InjectMocks private AnonymousUserCreatorService anonymousUserCreatorService;
  @Mock private CreateUserFacade createUserFacade;
  @Mock private IdentityAccountCreator identityAccountCreator;
  @Mock private IdentityAuthentication identityAuthentication;
  @Mock private RollbackFacade rollbackFacade;

  @Test
  void createAnonymousUserCreatesIdentityAccountAndMatrixUser() {
    var createdIdentity = new IdentityAccountCreated("user-id");
    var identityLogin = new IdentityLogin("access-token", 300, 600, "refresh-token");
    var user = new User();

    when(identityAccountCreator.createAccount(any())).thenReturn(createdIdentity);
    when(createUserFacade.updateIdentityAndCreateAccount(anyString(), any(), any()))
        .thenReturn(user);
    when(identityAuthentication.login(USER_DTO_SUCHT.getUsername(), USER_DTO_SUCHT.getPassword()))
        .thenReturn(identityLogin);

    var credentials = anonymousUserCreatorService.createAnonymousUser(USER_DTO_SUCHT);

    assertThat(credentials.getUserId()).isEqualTo("user-id");
    assertThat(credentials.getAccessToken()).isEqualTo("access-token");
    assertThat(credentials.getExpiresIn()).isEqualTo(300);
    assertThat(credentials.getRefreshToken()).isEqualTo("refresh-token");
    assertThat(credentials.getRefreshExpiresIn()).isEqualTo(600);
    verify(createUserFacade).provisionMatrixUser(user, USER_DTO_SUCHT.getUsername());
    verifyNoInteractions(rollbackFacade);
  }

  @Test
  void createAnonymousUserRollsBackWhenMatrixProvisioningFails() {
    var createdIdentity = new IdentityAccountCreated("user-id");
    var user = new User();

    when(identityAccountCreator.createAccount(any())).thenReturn(createdIdentity);
    when(createUserFacade.updateIdentityAndCreateAccount(anyString(), any(), any()))
        .thenReturn(user);
    doThrow(new InternalServerErrorException("Matrix provisioning failed"))
        .when(createUserFacade)
        .provisionMatrixUser(user, USER_DTO_SUCHT.getUsername());

    assertThatThrownBy(() -> anonymousUserCreatorService.createAnonymousUser(USER_DTO_SUCHT))
        .isInstanceOf(InternalServerErrorException.class);

    verify(rollbackFacade).rollBackUserAccount(any());
    verify(identityAuthentication, never()).login(anyString(), anyString());
  }

  @Test
  void createAnonymousUserRollsBackWhenIdentityLoginFails() {
    var createdIdentity = new IdentityAccountCreated("user-id");
    var user = new User();

    when(identityAccountCreator.createAccount(any())).thenReturn(createdIdentity);
    when(createUserFacade.updateIdentityAndCreateAccount(anyString(), any(), any()))
        .thenReturn(user);
    when(identityAuthentication.login(USER_DTO_SUCHT.getUsername(), USER_DTO_SUCHT.getPassword()))
        .thenThrow(new BadRequestException("login failed"));

    assertThatThrownBy(() -> anonymousUserCreatorService.createAnonymousUser(USER_DTO_SUCHT))
        .isInstanceOf(InternalServerErrorException.class);

    verify(createUserFacade).provisionMatrixUser(user, USER_DTO_SUCHT.getUsername());
    verify(rollbackFacade).rollBackUserAccount(any());
  }
}
