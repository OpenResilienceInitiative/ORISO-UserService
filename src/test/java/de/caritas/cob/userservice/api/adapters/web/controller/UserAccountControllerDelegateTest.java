package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AbsenceDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.DeleteUserAccountDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.E2eKeyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.EmailNotificationsDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.MasterKeyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MobileTokenDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchUserDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.config.VideoChatConfig;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.userdata.AskerDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataFacade;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.KeycloakUserDataProvider;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.DecryptionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import jakarta.ws.rs.InternalServerErrorException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  void getUserDataShouldPreserveOtpAvailabilityWhenOtpLookupFails() {
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
    when(userDtoMapper.userDataOf(
            eq(partialUserData), any(OtpInfoDTO.class), anyBoolean(), anyBoolean()))
        .thenReturn(fullUserData);

    var response = delegate.getUserData();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(fullUserData);
    verify(userDtoMapper)
        .userDataOf(eq(partialUserData), any(OtpInfoDTO.class), anyBoolean(), anyBoolean());
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

  @Test
  void updateAbsence_happyPath_delegatesToConsultantDataFacade() {
    // Consultants update absence through the shared consultant data facade.
    var absence = new AbsenceDTO().absent(true).message("away");
    var consultant = new Consultant();
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);

    var response = delegate.updateAbsence(absence);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(consultantDataFacade).updateConsultantAbsent(consultant, absence);
  }

  @Test
  void getUserEmailNotifications_adviceSeekerPath_returnsAdviceSeekerNotifications() {
    // Advice seekers expose notification settings through the asker data provider.
    var user = new User();
    var emailNotifications = new EmailNotificationsDTO().emailNotificationsEnabled(false);
    var userData = new UserDataResponseDTO();
    userData.setEmailNotifications(emailNotifications);
    when(userAccountProvider.findConsultantByEmail("user@example.org"))
        .thenReturn(Optional.empty());
    when(userAccountProvider.findUserByEmail("user@example.org")).thenReturn(Optional.of(user));
    when(askerDataProvider.retrieveData(user)).thenReturn(userData);

    var response = delegate.getUserEmailNotifications("user@example.org");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(emailNotifications);
  }

  @Test
  void getUserEmailNotifications_neitherConsultantNorAdviceSeeker_throwsNotFoundException() {
    // Unknown emails must not leak whether an account exists.
    when(userAccountProvider.findConsultantByEmail("unknown@example.org"))
        .thenReturn(Optional.empty());
    when(userAccountProvider.findUserByEmail("unknown@example.org")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> delegate.getUserEmailNotifications("unknown@example.org"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void getUserData_consultantPathWithDisplayNameEnrichment_returnsEnrichedUserData() {
    // Consultant profiles enrich display name and availability from chat services.
    var roles = Set.of(UserRole.CONSULTANT.getValue());
    var consultant = new Consultant();
    var partialUserData = new UserDataResponseDTO();
    var fullUserData = new UserDataResponseDTO();
    var consultantMap = Map.<String, Object>of("id", USER_ID);
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(consultantDataProvider.retrieveData(consultant)).thenReturn(partialUserData);
    when(accountManager.findConsultant(USER_ID)).thenReturn(Optional.of(consultantMap));
    when(userDtoMapper.displayNameOf(consultantMap)).thenReturn("Display Name");
    when(messenger.getAvailability(USER_ID)).thenReturn(true);
    when(identityClientConfig.isOtpAllowed(roles)).thenReturn(false);
    when(videoChatConfig.getE2eEncryptionEnabled()).thenReturn(true);
    when(identityClientConfig.getDisplayNameAllowedForConsultants()).thenReturn(true);
    when(userDtoMapper.userDataOf(eq(partialUserData), isNull(), eq(true), eq(true)))
        .thenReturn(fullUserData);

    var response = delegate.getUserData();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(partialUserData.getDisplayName()).isEqualTo("Display Name");
    assertThat(partialUserData.getAvailable()).isTrue();
    assertThat(response.getBody()).isSameAs(fullUserData);
  }

  @Test
  void getUserData_agencyAdminPath_returnsKeycloakUserData() {
    // Agency admins load profile data from Keycloak rather than consultant tables.
    var roles = Set.of(UserRole.AGENCY_ADMIN.getValue());
    var partialUserData = new UserDataResponseDTO();
    var fullUserData = new UserDataResponseDTO();
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(authenticatedUser.isAgencySuperAdmin()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(keycloakUserDataProvider.retrieveAuthenticatedUserData()).thenReturn(partialUserData);
    when(identityClientConfig.isOtpAllowed(roles)).thenReturn(false);
    when(videoChatConfig.getE2eEncryptionEnabled()).thenReturn(false);
    when(identityClientConfig.getDisplayNameAllowedForConsultants()).thenReturn(false);
    when(userDtoMapper.userDataOf(eq(partialUserData), isNull(), eq(false), eq(false)))
        .thenReturn(fullUserData);

    var response = delegate.getUserData();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(fullUserData);
    verify(keycloakUserDataProvider).retrieveAuthenticatedUserData();
  }

  @Test
  void getUserData_adviceSeekerPath_returnsAskerUserData() {
    // Advice seekers read profile data from the local user account store.
    var roles = Set.of(UserRole.USER.getValue());
    var user = new User();
    var partialUserData = new UserDataResponseDTO();
    var fullUserData = new UserDataResponseDTO();
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(authenticatedUser.isAgencySuperAdmin()).thenReturn(false);
    when(authenticatedUser.isRestrictedAgencyAdmin()).thenReturn(false);
    when(authenticatedUser.isSingleTenantAdmin()).thenReturn(false);
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(false);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user);
    when(askerDataProvider.retrieveData(user)).thenReturn(partialUserData);
    when(identityClientConfig.isOtpAllowed(roles)).thenReturn(false);
    when(videoChatConfig.getE2eEncryptionEnabled()).thenReturn(false);
    when(identityClientConfig.getDisplayNameAllowedForConsultants()).thenReturn(false);
    when(userDtoMapper.userDataOf(eq(partialUserData), isNull(), eq(false), eq(false)))
        .thenReturn(fullUserData);

    var response = delegate.getUserData();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(fullUserData);
    verify(askerDataProvider).retrieveData(user);
  }

  @Test
  void getUserData_otpAllowedAndSuccess_includesOtpDataInResponse() {
    // OTP state is included when identity allows and returns credentials.
    var roles = Set.of(UserRole.USER.getValue());
    var partialUserData = new UserDataResponseDTO();
    var fullUserData = new UserDataResponseDTO();
    var otpInfo = new OtpInfoDTO().otpSecret("secret");
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(authenticatedUser.isAgencySuperAdmin()).thenReturn(false);
    when(authenticatedUser.isRestrictedAgencyAdmin()).thenReturn(false);
    when(authenticatedUser.isSingleTenantAdmin()).thenReturn(false);
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(false);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(new User());
    when(askerDataProvider.retrieveData(any(User.class))).thenReturn(partialUserData);
    when(identityClientConfig.isOtpAllowed(roles)).thenReturn(true);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(USERNAME);
    when(identityManager.getOtpCredential(USERNAME)).thenReturn(otpInfo);
    when(videoChatConfig.getE2eEncryptionEnabled()).thenReturn(false);
    when(identityClientConfig.getDisplayNameAllowedForConsultants()).thenReturn(false);
    when(userDtoMapper.userDataOf(eq(partialUserData), eq(otpInfo), eq(false), eq(false)))
        .thenReturn(fullUserData);

    var response = delegate.getUserData();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(fullUserData);
  }

  @Test
  void getUserData_otpNotAllowed_returnsNullOtpInResponse() {
    // OTP lookup is skipped entirely when the role is not OTP-eligible.
    var roles = Set.of(UserRole.CONSULTANT.getValue());
    var partialUserData = new UserDataResponseDTO();
    var fullUserData = new UserDataResponseDTO();
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(new Consultant());
    when(consultantDataProvider.retrieveData(any(Consultant.class))).thenReturn(partialUserData);
    when(accountManager.findConsultant(USER_ID)).thenReturn(Optional.empty());
    when(messenger.getAvailability(USER_ID)).thenReturn(false);
    when(identityClientConfig.isOtpAllowed(roles)).thenReturn(false);
    when(videoChatConfig.getE2eEncryptionEnabled()).thenReturn(false);
    when(identityClientConfig.getDisplayNameAllowedForConsultants()).thenReturn(false);
    when(userDtoMapper.userDataOf(eq(partialUserData), isNull(), eq(false), eq(false)))
        .thenReturn(fullUserData);

    var response = delegate.getUserData();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(identityManager, never()).getOtpCredential(anyString());
    verify(userDtoMapper).userDataOf(eq(partialUserData), isNull(), eq(false), eq(false));
  }

  @Test
  void getUserData_displayNameEnrichmentThrows_swallowedAndContinues() {
    // Display name enrichment failures must not block profile retrieval.
    var roles = Set.of(UserRole.CONSULTANT.getValue());
    var partialUserData = new UserDataResponseDTO();
    var fullUserData = new UserDataResponseDTO();
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(new Consultant());
    when(consultantDataProvider.retrieveData(any(Consultant.class))).thenReturn(partialUserData);
    when(accountManager.findConsultant(USER_ID))
        .thenThrow(new RuntimeException("display name down"));
    when(messenger.getAvailability(USER_ID)).thenReturn(true);
    when(identityClientConfig.isOtpAllowed(roles)).thenReturn(false);
    when(videoChatConfig.getE2eEncryptionEnabled()).thenReturn(false);
    when(identityClientConfig.getDisplayNameAllowedForConsultants()).thenReturn(false);
    when(userDtoMapper.userDataOf(eq(partialUserData), isNull(), eq(false), eq(false)))
        .thenReturn(fullUserData);

    var response = delegate.getUserData();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(fullUserData);
  }

  @Test
  void getUserData_availabilityEnrichmentThrows_swallowedAndContinues() {
    // Availability enrichment failures must not block profile retrieval.
    var roles = Set.of(UserRole.CONSULTANT.getValue());
    var partialUserData = new UserDataResponseDTO();
    var fullUserData = new UserDataResponseDTO();
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(new Consultant());
    when(consultantDataProvider.retrieveData(any(Consultant.class))).thenReturn(partialUserData);
    when(accountManager.findConsultant(USER_ID)).thenReturn(Optional.empty());
    when(messenger.getAvailability(USER_ID)).thenThrow(new RuntimeException("availability down"));
    when(identityClientConfig.isOtpAllowed(roles)).thenReturn(false);
    when(videoChatConfig.getE2eEncryptionEnabled()).thenReturn(false);
    when(identityClientConfig.getDisplayNameAllowedForConsultants()).thenReturn(false);
    when(userDtoMapper.userDataOf(eq(partialUserData), isNull(), eq(false), eq(false)))
        .thenReturn(fullUserData);

    var response = delegate.getUserData();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(fullUserData);
  }

  @Test
  void patchUser_emptyPatchMap_throwsBadRequestException() {
    // Patch requests must contain at least one mutable property.
    var patchUserDTO = new PatchUserDTO();
    when(userDtoMapper.mapOf(patchUserDTO, authenticatedUser)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> delegate.patchUser(patchUserDTO))
        .isInstanceOf(BadRequestException.class);

    verify(accountManager, never()).patchUser(anyMap());
  }

  @Test
  void patchUser_emptyPatchResponse_throwsNotFoundException() {
    // Orphaned Keycloak accounts (no user or consultant DB record) must yield 404, not 500.
    var patchUserDTO = new PatchUserDTO();
    var patchMap = Map.<String, Object>of("firstName", "Ada");
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(userDtoMapper.mapOf(patchUserDTO, authenticatedUser)).thenReturn(Optional.of(patchMap));
    when(accountManager.patchUser(patchMap)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> delegate.patchUser(patchUserDTO))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining(USER_ID);
  }

  @Test
  void patchUser_preferredLanguage_triggersIdentityManagerChangeLanguage() {
    // Preferred language changes are propagated to the identity provider.
    var patchUserDTO = new PatchUserDTO();
    patchUserDTO.setPreferredLanguage(LanguageCode.DE);
    var patchMap = Map.<String, Object>of("preferredLanguage", "de");
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(userDtoMapper.mapOf(patchUserDTO, authenticatedUser)).thenReturn(Optional.of(patchMap));
    when(accountManager.patchUser(patchMap)).thenReturn(Optional.of(patchMap));
    when(userDtoMapper.preferredLanguageOf(patchUserDTO)).thenReturn(Optional.of("de"));
    when(userDtoMapper.availableOf(patchUserDTO)).thenReturn(Optional.empty());

    var response = delegate.patchUser(patchUserDTO);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(identityManager).changeLanguage(USER_ID, "de");
  }

  @Test
  void patchUser_consultantAvailabilityUpdate_verifiesMessengerCalled() {
    // Consultant availability toggles are mirrored to the chat backend.
    var patchUserDTO = new PatchUserDTO();
    patchUserDTO.setAvailable(true);
    var patchMap = Map.<String, Object>of("available", true);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(userDtoMapper.mapOf(patchUserDTO, authenticatedUser)).thenReturn(Optional.of(patchMap));
    when(accountManager.patchUser(patchMap)).thenReturn(Optional.of(patchMap));
    when(userDtoMapper.preferredLanguageOf(patchUserDTO)).thenReturn(Optional.empty());
    when(userDtoMapper.availableOf(patchUserDTO)).thenReturn(Optional.of(true));

    var response = delegate.patchUser(patchUserDTO);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(messenger).setAvailability(USER_ID, true);
  }

  @Test
  void patchUser_messengerExceptionSwallowed_doesNotThrow() {
    // Chat unavailability during migration must not fail profile updates.
    var patchUserDTO = new PatchUserDTO();
    patchUserDTO.setAvailable(false);
    var patchMap = Map.<String, Object>of("available", false);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(userDtoMapper.mapOf(patchUserDTO, authenticatedUser)).thenReturn(Optional.of(patchMap));
    when(accountManager.patchUser(patchMap)).thenReturn(Optional.of(patchMap));
    when(userDtoMapper.preferredLanguageOf(patchUserDTO)).thenReturn(Optional.empty());
    when(userDtoMapper.availableOf(patchUserDTO)).thenReturn(Optional.of(false));
    doThrow(new RuntimeException("chat down")).when(messenger).setAvailability(USER_ID, false);

    var response = delegate.patchUser(patchUserDTO);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  void patchUser_magicLinkEnabledForAdviceSeekerWithValidEmail_returnsNoContent() {
    // Advice seekers with a real email may enable magic-link login.
    var patchUserDTO = new PatchUserDTO();
    patchUserDTO.setMagicLinkLoginEnabled(true);
    var patchMap = Map.<String, Object>of("magicLinkLoginEnabled", true);
    var user = new User();
    user.setEmail("user@example.org");
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(authenticatedUser.isAdviceSeeker()).thenReturn(true);
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user);
    when(identityClientConfig.getEmailDummySuffix()).thenReturn("@example.invalid");
    when(userDtoMapper.mapOf(patchUserDTO, authenticatedUser)).thenReturn(Optional.of(patchMap));
    when(accountManager.patchUser(patchMap)).thenReturn(Optional.of(patchMap));
    when(userDtoMapper.preferredLanguageOf(patchUserDTO)).thenReturn(Optional.empty());
    when(userDtoMapper.availableOf(patchUserDTO)).thenReturn(Optional.empty());

    var response = delegate.patchUser(patchUserDTO);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(accountManager).patchUser(patchMap);
  }

  @Test
  void updateConsultantData_happyPath_delegatesCorrectly() {
    // Consultants update their own profile through the admin update pipeline.
    var updateConsultantDTO = new UpdateConsultantDTO();
    var consultant = new Consultant();
    var updateAdminConsultantDTO = new UpdateAdminConsultantDTO();
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(consultantService.getConsultant(USER_ID)).thenReturn(Optional.of(consultant));
    when(consultantDtoMapper.updateAdminConsultantOf(updateConsultantDTO, consultant))
        .thenReturn(updateAdminConsultantDTO);

    var response = delegate.updateConsultantData(updateConsultantDTO);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(consultantUpdateService).updateConsultant(USER_ID, updateAdminConsultantDTO);
  }

  @Test
  void updateConsultantData_consultantNotFound_throwsNotFoundException() {
    // Missing consultant records cannot be updated.
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(consultantService.getConsultant(USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> delegate.updateConsultantData(new UpdateConsultantDTO()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void updatePassword_changePasswordReturnsFalse_throwsInternalServerErrorException() {
    // Identity provider failures during password change surface as server errors.
    var passwordDTO = new PasswordDTO();
    passwordDTO.setOldPassword("old");
    passwordDTO.setNewPassword("new");
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(USERNAME);
    when(identityManager.validatePasswordIgnoring2fa(USERNAME, "old")).thenReturn(true);
    when(identityManager.changePassword(USER_ID, "new")).thenReturn(false);

    assertThatThrownBy(() -> delegate.updatePassword(passwordDTO))
        .isInstanceOf(InternalServerErrorException.class);
  }

  @Test
  void updateKey_keyDiffers_updatesAndReturnsOk() {
    // A changed master key is persisted before returning success.
    when(decryptionService.getMasterKey()).thenReturn("old-key");

    var response = delegate.updateKey(new MasterKeyDTO().masterKey("new-key"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(decryptionService).updateMasterKey("new-key");
  }

  @Test
  void updateKey_sameKey_returnsConflict() {
    // Submitting the current master key is rejected to avoid no-op updates.
    when(decryptionService.getMasterKey()).thenReturn("same-key");

    var response = delegate.updateKey(new MasterKeyDTO().masterKey("same-key"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    verify(decryptionService, never()).updateMasterKey(anyString());
  }

  @Test
  void updateE2eInChats_consultantHappyPath_delegatesToMessenger() {
    // Consultants with a chat user id propagate E2E keys to all chats.
    var consultantMap = Map.<String, Object>of("id", USER_ID);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(accountManager.findConsultant(USER_ID)).thenReturn(Optional.of(consultantMap));
    when(userDtoMapper.chatUserIdOf(consultantMap)).thenReturn("chat-user-id");
    when(messenger.updateE2eKeys("chat-user-id", "public-key")).thenReturn(true);

    var response = delegate.updateE2eInChats(new E2eKeyDTO().publicKey("public-key"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(messenger).updateE2eKeys("chat-user-id", "public-key");
  }

  @Test
  void updateE2eInChats_nullChatUserIdForConsultant_throwsInternalServerErrorException() {
    // Consultants without a chat identity cannot update E2E keys.
    var consultantMap = Map.<String, Object>of("id", USER_ID);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(accountManager.findConsultant(USER_ID)).thenReturn(Optional.of(consultantMap));
    when(userDtoMapper.chatUserIdOf(consultantMap)).thenReturn(null);

    assertThatThrownBy(() -> delegate.updateE2eInChats(new E2eKeyDTO().publicKey("public-key")))
        .isInstanceOf(InternalServerErrorException.class);
  }

  @Test
  void deactivateAndFlagUserAccountForDeletion_badPassword_throwsBadRequestException() {
    // Account deletion requires a valid current password.
    var deleteUserAccountDTO = new DeleteUserAccountDTO();
    deleteUserAccountDTO.setPassword("wrong");
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(USERNAME);
    when(identityManager.validatePasswordIgnoring2fa(USERNAME, "wrong")).thenReturn(false);

    assertThatThrownBy(() -> delegate.deactivateAndFlagUserAccountForDeletion(deleteUserAccountDTO))
        .isInstanceOf(BadRequestException.class);

    verify(userAccountProvider, never()).deactivateAndFlagUserAccountForDeletion();
  }

  @Test
  void deactivateAndFlagUserAccountForDeletion_happyPath_delegatesAndReturnsOk() {
    // Valid credentials allow flagging the account for deletion.
    var deleteUserAccountDTO = new DeleteUserAccountDTO();
    deleteUserAccountDTO.setPassword("correct");
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(USERNAME);
    when(identityManager.validatePasswordIgnoring2fa(USERNAME, "correct")).thenReturn(true);

    var response = delegate.deactivateAndFlagUserAccountForDeletion(deleteUserAccountDTO);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(userAccountProvider).deactivateAndFlagUserAccountForDeletion();
  }
}
