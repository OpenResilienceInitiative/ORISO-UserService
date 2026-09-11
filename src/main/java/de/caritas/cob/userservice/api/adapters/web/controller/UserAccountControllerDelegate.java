package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.AbsenceDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.DeleteUserAccountDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.EmailNotificationsDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MasterKeyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MobileTokenDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchUserDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.config.VideoChatConfig;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.userdata.AskerDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataFacade;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.KeycloakUserDataProvider;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.in.IdentityPolicy;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.DecryptionService;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import jakarta.ws.rs.InternalServerErrorException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class UserAccountControllerDelegate {

  private final @NonNull UserAccountService userAccountProvider;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull DecryptionService decryptionService;
  private final @NonNull ConsultantDataFacade consultantDataFacade;
  private final @NonNull IdentityPolicy identityPolicy;
  private final @NonNull IdentityManaging identityManager;
  private final @NonNull AccountManaging accountManager;
  private final @NonNull Messaging messenger;
  private final @NonNull ConsultantDtoMapper consultantDtoMapper;
  private final @NonNull UserDtoMapper userDtoMapper;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull ConsultantUpdateService consultantUpdateService;
  private final @NonNull ConsultantDataProvider consultantDataProvider;
  private final @NonNull AskerDataProvider askerDataProvider;
  private final @NonNull VideoChatConfig videoChatConfig;
  private final @NonNull KeycloakUserDataProvider keycloakUserDataProvider;
  private final @NonNull UsernameTranscoder usernameTranscoder;

  ResponseEntity<Void> updateAbsence(AbsenceDTO absence) {
    var consultant = userAccountProvider.retrieveValidatedConsultant();
    this.consultantDataFacade.updateConsultantAbsent(consultant, absence);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<EmailNotificationsDTO> getUserEmailNotifications(String email) {
    Optional<Consultant> consultantByEmail = userAccountProvider.findConsultantByEmail(email);
    if (consultantByEmail.isPresent()) {
      return new ResponseEntity<>(getEmailNotifications(consultantByEmail.get()), HttpStatus.OK);
    } else {
      Optional<User> userByEmail = userAccountProvider.findUserByEmail(email);
      if (userByEmail.isPresent()) {
        return new ResponseEntity<>(getEmailNotifications(userByEmail.get()), HttpStatus.OK);
      } else {
        throw new NotFoundException("No adviceseeker nor consultant with given email found.");
      }
    }
  }

  private EmailNotificationsDTO getEmailNotifications(Consultant consultant) {
    var consultantDTO = consultantDataProvider.retrieveData(consultant);
    return consultantDTO.getEmailNotifications();
  }

  private EmailNotificationsDTO getEmailNotifications(User user) {
    var userDTO = askerDataProvider.retrieveData(user);
    return userDTO.getEmailNotifications();
  }

  ResponseEntity<UserDataResponseDTO> getUserData() {
    UserDataResponseDTO partialUserData;
    if (authenticatedUser.isConsultant()) {
      var consultant = userAccountProvider.retrieveValidatedConsultant();
      partialUserData = consultantDataProvider.retrieveData(consultant);
      enrichConsultantDisplayName(partialUserData);
      enrichConsultantAvailability(partialUserData);
    } else if (isTenantAdmin() || isAgencyAdmin()) {
      partialUserData = keycloakUserDataProvider.retrieveAuthenticatedUserData();
      // Only ask a platform admin to set 2FA up when the OTP role policy would actually
      // let them finish. Encouraging it unconditionally deadlocks the admin UI: the
      // client gates on isToEncourage && !isActive, but isActive can never become true
      // while the policy denies OTP (the credential is never read), and every setup
      // endpoint rejects the attempt with 409. See assertTwoFactorAuthAllowed in
      // UserTwoFactorAuthControllerDelegate.
      if (authenticatedUser.isPlatformAdmin() && isOtpAllowed()) {
        partialUserData.setEncourage2fa(true);
      }
    } else {
      var user = userAccountProvider.retrieveValidatedUser();
      partialUserData = askerDataProvider.retrieveData(user);
    }
    var otpInfoDTO = retrieveOtpCredentialIfAllowed();

    var fullUserData =
        userDtoMapper.userDataOf(
            partialUserData,
            otpInfoDTO,
            videoChatConfig.getE2eEncryptionEnabled(),
            identityPolicy.isConsultantDisplayNameAllowed());

    return new ResponseEntity<>(fullUserData, HttpStatus.OK);
  }

  private void enrichConsultantDisplayName(UserDataResponseDTO userData) {
    try {
      accountManager
          .findConsultant(authenticatedUser.getUserId())
          .ifPresent(
              consultantMap -> userData.setDisplayName(userDtoMapper.displayNameOf(consultantMap)));
    } catch (Exception ex) {
      log.warn(
          "Could not enrich display name for consultant {}; returning user data without display name",
          authenticatedUser.getUserId(),
          ex);
    }
  }

  private void enrichConsultantAvailability(UserDataResponseDTO userData) {
    try {
      userData.setAvailable(messenger.getAvailability(authenticatedUser.getUserId()));
    } catch (Exception ex) {
      log.warn(
          "Could not enrich availability for consultant {}; returning user data without availability",
          authenticatedUser.getUserId(),
          ex);
    }
  }

  private boolean isOtpAllowed() {
    return identityPolicy.isTwoFactorAuthenticationAllowed(authenticatedUser.getRoles());
  }

  private IdentityOtpCredential retrieveOtpCredentialIfAllowed() {
    if (!isOtpAllowed()) {
      return null;
    }
    try {
      return identityManager.getOtpCredential(
          usernameTranscoder.encodeUsername(authenticatedUser.getUsername()));
    } catch (Exception ex) {
      log.warn(
          "Could not retrieve OTP credential for authenticated user {}; preserving OTP availability without credential state",
          authenticatedUser.getUserId(),
          ex);
      // A failed Keycloak lookup must not look like role-policy denial. An empty
      // DTO keeps 2FA enabled in the response while safely reporting no active
      // credential, so users can still open setup/reset controls.
      return IdentityOtpCredential.empty();
    }
  }

  private boolean isAgencyAdmin() {
    return authenticatedUser.isAgencySuperAdmin() || authenticatedUser.isRestrictedAgencyAdmin();
  }

  private boolean isTenantAdmin() {
    return authenticatedUser.isSingleTenantAdmin() || authenticatedUser.isTenantSuperAdmin();
  }

  ResponseEntity<Void> patchUser(PatchUserDTO patchUserDTO) {
    validateMagicLinkTogglePrerequisites(patchUserDTO);

    var userId = authenticatedUser.getUserId();
    var patchMap =
        userDtoMapper
            .mapOf(patchUserDTO, authenticatedUser)
            .orElseThrow(
                () -> new BadRequestException("Invalid payload: at least one property expected"));

    Optional<Map<String, Object>> patchResponse = accountManager.patchUser(patchMap);
    if (patchResponse.isEmpty()) {
      throw new NotFoundException(
          "User with id %s not found in user or consultant repository", userId);
    }

    userDtoMapper
        .preferredLanguageOf(patchUserDTO)
        .ifPresent(lang -> identityManager.changeLanguage(userId, lang));

    userDtoMapper
        .availableOf(patchUserDTO)
        .filter(available -> authenticatedUser.isConsultant())
        .ifPresent(available -> messenger.setAvailability(userId, available));

    return ResponseEntity.noContent().build();
  }

  private void validateMagicLinkTogglePrerequisites(PatchUserDTO patchUserDTO) {
    if (!Boolean.TRUE.equals(patchUserDTO.getMagicLinkLoginEnabled())) {
      return;
    }

    String email = null;
    if (authenticatedUser.isConsultant()) {
      email = userAccountProvider.retrieveValidatedConsultant().getEmail();
    } else if (authenticatedUser.isAdviceSeeker()) {
      email = userAccountProvider.retrieveValidatedUser().getEmail();
    }

    boolean hasRealEmail = identityPolicy.isProfileEmailUsableForMagicLink(email);
    if (!hasRealEmail) {
      throw new BadRequestException(
          "Magic link login can only be enabled when a profile email is set.");
    }
  }

  ResponseEntity<Void> updateConsultantData(UpdateConsultantDTO updateConsultantDTO) {
    var consultantId = authenticatedUser.getUserId();
    var consultant =
        consultantService
            .getConsultant(consultantId)
            .orElseThrow(
                () -> new NotFoundException("Consultant with id %s not found", consultantId));

    var updateAdminConsultantDTO =
        consultantDtoMapper.updateAdminConsultantOf(updateConsultantDTO, consultant);
    consultantUpdateService.updateConsultant(consultantId, updateAdminConsultantDTO);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> updatePassword(PasswordDTO passwordDTO) {
    var username = authenticatedUser.getUsername();
    var encodedUsername = usernameTranscoder.encodeUsername(username);
    if (!identityManager.validatePasswordIgnoring2fa(
        encodedUsername, passwordDTO.getOldPassword())) {
      var message = String.format("Could not log in user %s into Keycloak", username);
      throw new BadRequestException(message);
    }

    var userId = authenticatedUser.getUserId();
    if (!identityManager.changePassword(userId, passwordDTO.getNewPassword())) {
      var message = String.format("Could not update password of user %s", userId);
      throw new InternalServerErrorException(message);
    }

    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> updateKey(MasterKeyDTO masterKey) {
    if (!decryptionService.getMasterKey().equals(masterKey.getMasterKey())) {
      decryptionService.updateMasterKey(masterKey.getMasterKey());
      LogService.logInfo("MasterKey updated");
      return new ResponseEntity<>(HttpStatus.OK);
    }

    return new ResponseEntity<>(HttpStatus.CONFLICT);
  }

  ResponseEntity<Void> updateEmailAddress(String emailAddress) {
    var lowerCaseEmail = Optional.of(emailAddress.toLowerCase(Locale.ROOT));
    userAccountProvider.changeUserAccountEmailAddress(lowerCaseEmail);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> deleteEmailAddress() {
    userAccountProvider.changeUserAccountEmailAddress(Optional.empty());

    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> deactivateAndFlagUserAccountForDeletion(
      DeleteUserAccountDTO deleteUserAccountDTO) {
    var username = authenticatedUser.getUsername();
    var password = deleteUserAccountDTO.getPassword();
    var passwordValid = identityManager.validatePasswordIgnoring2fa(username, password);
    if (!passwordValid) {
      var encodedUsername = usernameTranscoder.encodeUsername(username);
      passwordValid =
          !encodedUsername.equals(username)
              && identityManager.validatePasswordIgnoring2fa(encodedUsername, password);
    }
    if (!passwordValid) {
      var message = String.format("Could not log in user %s into Keycloak", username);
      throw new BadRequestException(message);
    }

    userAccountProvider.deactivateAndFlagUserAccountForDeletion();

    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> updateMobileToken(MobileTokenDTO mobileTokenDTO) {
    this.userAccountProvider.updateUserMobileToken(mobileTokenDTO.getToken());
    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> addMobileAppToken(MobileTokenDTO mobileTokenDTO) {
    this.userAccountProvider.addMobileAppToken(mobileTokenDTO.getToken());
    return new ResponseEntity<>(HttpStatus.OK);
  }
}
