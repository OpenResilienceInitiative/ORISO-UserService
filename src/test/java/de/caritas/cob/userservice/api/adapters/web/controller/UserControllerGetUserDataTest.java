package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.config.VideoChatConfig;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.facade.userdata.AskerDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.KeycloakUserDataProvider;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.archive.SessionDeleteService;
import de.caritas.cob.userservice.api.service.auth.MagicLinkLoginService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class UserControllerGetUserDataTest {

  private static final String USER_ID = "user-id";
  private static final String USERNAME = "username";

  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private IdentityManaging identityManager;
  @Mock private AccountManaging accountManager;
  @Mock private Messaging messenger;
  @Mock private ConsultantDtoMapper consultantDtoMapper;
  @Mock private UserDtoMapper userDtoMapper;
  @Mock private ConsultantService consultantService;
  @Mock private ConsultantUpdateService consultantUpdateService;
  @Mock private ConsultantDataProvider consultantDataProvider;
  @Mock private AskerDataProvider askerDataProvider;
  @Mock private VideoChatConfig videoChatConfig;
  @Mock private KeycloakUserDataProvider keycloakUserDataProvider;
  @Mock private MagicLinkLoginService magicLinkLoginService;
  @Mock private SessionDeleteService sessionDeleteService;
  @Mock private EventNotificationService eventNotificationService;

  @InjectMocks private UserController userController;

  @Test
  void getUserData_Should_ReturnUserDataWithoutOtpState_WhenOtpLookupFails() {
    var roles = Set.of(UserRole.TENANT_ADMIN.getValue());
    var partialUserData = new UserDataResponseDTO();
    var fullUserData = new UserDataResponseDTO();
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(keycloakUserDataProvider.retrieveAuthenticatedUserData()).thenReturn(partialUserData);
    when(identityClientConfig.isOtpAllowed(roles)).thenReturn(true);
    when(identityManager.getOtpCredential(USERNAME)).thenThrow(new RuntimeException("OTP down"));
    when(userDtoMapper.userDataOf(eq(partialUserData), isNull(), anyBoolean(), anyBoolean()))
        .thenReturn(fullUserData);

    var response = userController.getUserData();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(fullUserData);
    verify(userDtoMapper).userDataOf(eq(partialUserData), isNull(), anyBoolean(), anyBoolean());
  }
}
