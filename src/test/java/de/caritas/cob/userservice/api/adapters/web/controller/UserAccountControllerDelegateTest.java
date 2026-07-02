package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.E2eKeyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.EmailNotificationsDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MobileTokenDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchUserDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.config.VideoChatConfig;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.facade.userdata.AskerDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataFacade;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.KeycloakUserDataProvider;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.DecryptionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.ws.rs.InternalServerErrorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class UserAccountControllerDelegateTest {

  private static final String USER_ID = "user-id";
  private static final String USERNAME = "username";

  @Mock private UserAccountService userAccountProvider;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private DecryptionService decryptionService;
  @Mock private ConsultantDataFacade consultantDataFacade;
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
  @Mock private UsernameTranscoder usernameTranscoder;

  @InjectMocks private UserAccountControllerDelegate delegate;

  @Test
  void getUserDataShouldReturnUserDataWithoutOtpStateWhenOtpLookupFails() {
    var roles = Set.of(UserRole.TENANT_ADMIN.getValue());
    var partialUserData = new UserDataResponseDTO();
    var fullUserData = new UserDataResponseDTO();
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(keycloakUserDataProvider.retrieveAuthenticatedUserData()).thenReturn(partialUserData);
    when(identityClientConfig.isOtpAllowed(roles)).thenReturn(true);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(USERNAME);
    when(identityManager.getOtpCredential(USERNAME)).thenThrow(new RuntimeException("OTP down"));
    when(userDtoMapper.userDataOf(eq(partialUserData), isNull(), anyBoolean(), anyBoolean()))
        .thenReturn(fullUserData);

    var response = delegate.getUserData();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(fullUserData);
    verify(userDtoMapper).userDataOf(eq(partialUserData), isNull(), anyBoolean(), anyBoolean());
  }

  @Test
  void patchUserShouldRejectMagicLinkLoginWhenConsultantHasDummyEmail() {
    var patchUserDTO = new PatchUserDTO();
    patchUserDTO.setMagicLinkLoginEnabled(true);
    var consultant = new Consultant();
    consultant.setEmail("dummy@example.invalid");
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(identityClientConfig.getEmailDummySuffix()).thenReturn("@example.invalid");

    assertThatThrownBy(() -> delegate.patchUser(patchUserDTO))
        .isInstanceOf(BadRequestException.class);

    verify(accountManager, never()).patchUser(org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void patchUserShouldPatchMappedPayloadAndReturnNoContent() {
    var patchUserDTO = new PatchUserDTO();
    var patchMap = Map.<String, Object>of("firstName", "Ada");
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(userDtoMapper.mapOf(patchUserDTO, authenticatedUser)).thenReturn(Optional.of(patchMap));
    when(accountManager.patchUser(patchMap)).thenReturn(Optional.of(patchMap));
    when(userDtoMapper.preferredLanguageOf(patchUserDTO)).thenReturn(Optional.empty());
    when(userDtoMapper.availableOf(patchUserDTO)).thenReturn(Optional.empty());

    var response = delegate.patchUser(patchUserDTO);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(accountManager).patchUser(patchMap);
  }

  @Test
  void updatePasswordShouldThrowBadRequestWhenOldPasswordIsInvalid() {
    var passwordDTO = new PasswordDTO();
    passwordDTO.setOldPassword("old");
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(USERNAME);
    when(identityManager.validatePasswordIgnoring2fa(USERNAME, "old")).thenReturn(false);

    assertThatThrownBy(() -> delegate.updatePassword(passwordDTO))
        .isInstanceOf(BadRequestException.class);

    verify(identityManager, never())
        .changePassword(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void updatePasswordShouldChangePasswordAndReturnOk() {
    var passwordDTO = new PasswordDTO();
    passwordDTO.setOldPassword("old");
    passwordDTO.setNewPassword("new");
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(USERNAME);
    when(identityManager.validatePasswordIgnoring2fa(USERNAME, "old")).thenReturn(true);
    when(identityManager.changePassword(USER_ID, "new")).thenReturn(true);

    var response = delegate.updatePassword(passwordDTO);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(identityManager).changePassword(USER_ID, "new");
  }

  @Test
  void updateEmailAddressShouldNormalizeEmailToLowerCase() {
    var response = delegate.updateEmailAddress("Name@Example.org");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(userAccountProvider).changeUserAccountEmailAddress(Optional.of("name@example.org"));
  }

  @Test
  void deleteEmailAddressShouldResetEmailAddress() {
    var response = delegate.deleteEmailAddress();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(userAccountProvider).changeUserAccountEmailAddress(Optional.empty());
  }

  @Test
  void updateMobileTokenShouldDelegateTokenUpdate() {
    var response = delegate.updateMobileToken(new MobileTokenDTO().token("mobile-token"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(userAccountProvider).updateUserMobileToken("mobile-token");
  }

  @Test
  void addMobileAppTokenShouldDelegateTokenCreation() {
    var response = delegate.addMobileAppToken(new MobileTokenDTO().token("mobile-token"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(userAccountProvider).addMobileAppToken("mobile-token");
  }

  @Test
  void getUserEmailNotificationsShouldReturnConsultantNotificationsWhenConsultantExists() {
    var consultant = new Consultant();
    var emailNotifications = new EmailNotificationsDTO().emailNotificationsEnabled(true);
    var consultantData = new UserDataResponseDTO();
    consultantData.setEmailNotifications(emailNotifications);
    when(userAccountProvider.findConsultantByEmail("name@example.org"))
        .thenReturn(Optional.of(consultant));
    when(consultantDataProvider.retrieveData(consultant)).thenReturn(consultantData);

    var response = delegate.getUserEmailNotifications("name@example.org");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(emailNotifications);
  }

  @Test
  void updateE2eInChatsShouldReturnAcceptedForUninitializedAdviceSeekerWithoutChatUserId() {
    var userMap = Map.<String, Object>of("id", USER_ID);
    var adviceSeeker = new User();
    var timestamp = LocalDateTime.now();
    adviceSeeker.setCreateDate(timestamp);
    adviceSeeker.setUpdateDate(timestamp);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(authenticatedUser.isAdviceSeeker()).thenReturn(true);
    when(accountManager.findAdviceSeeker(USER_ID)).thenReturn(Optional.of(userMap));
    when(userDtoMapper.chatUserIdOf(userMap)).thenReturn(null);
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(adviceSeeker);

    var response = delegate.updateE2eInChats(new E2eKeyDTO().publicKey("public-key"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    verify(messenger, never())
        .updateE2eKeys(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void updateE2eInChatsShouldThrowWhenMessengerRejectsKeyUpdate() {
    var consultantMap = Map.<String, Object>of("id", USER_ID);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(accountManager.findConsultant(USER_ID)).thenReturn(Optional.of(consultantMap));
    when(userDtoMapper.chatUserIdOf(consultantMap)).thenReturn("chat-user-id");
    when(messenger.updateE2eKeys("chat-user-id", "public-key")).thenReturn(false);

    assertThatThrownBy(() -> delegate.updateE2eInChats(new E2eKeyDTO().publicKey("public-key")))
        .isInstanceOf(InternalServerErrorException.class);
  }
}
