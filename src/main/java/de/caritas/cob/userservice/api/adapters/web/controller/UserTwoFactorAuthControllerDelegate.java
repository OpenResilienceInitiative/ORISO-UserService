package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.apache.commons.lang3.BooleanUtils.isFalse;

import de.caritas.cob.userservice.api.adapters.web.dto.EmailDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.OneTimePasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import java.util.Locale;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class UserTwoFactorAuthControllerDelegate {

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull IdentityClientConfig identityClientConfig;
  private final @NonNull IdentityManaging identityManager;
  private final @NonNull AccountManaging accountManager;
  private final @NonNull UserDtoMapper userDtoMapper;
  private final @NonNull UsernameTranscoder usernameTranscoder;

  ResponseEntity<Void> startTwoFactorAuthByEmailSetup(EmailDTO emailDTO) {
    var username = usernameTranscoder.encodeUsername(authenticatedUser.getUsername());
    var email = emailDTO.getEmail().toLowerCase(Locale.ROOT);

    if (!identityManager.isEmailAvailableOrOwn(username, email)) {
      return new ResponseEntity<>(HttpStatus.PRECONDITION_FAILED);
    }

    identityManager
        .setUpOneTimePassword(username, email)
        .ifPresent(
            message -> {
              throw new InternalServerErrorException(message);
            });

    return ResponseEntity.noContent().build();
  }

  ResponseEntity<Void> finishTwoFactorAuthByEmailSetup(String tan) {
    var username = usernameTranscoder.encodeUsername(authenticatedUser.getUsername());
    var validationResult = identityManager.validateOneTimePassword(username, tan);

    if (Boolean.parseBoolean(validationResult.get("created"))) {
      var patchMap = userDtoMapper.mapOf(validationResult.get("email"), authenticatedUser);
      accountManager.patchUser(patchMap);
      return ResponseEntity.noContent().build();
    }
    if (Boolean.parseBoolean(validationResult.get("attemptsLeft"))) {
      return ResponseEntity.badRequest().build();
    }
    if (Boolean.parseBoolean(validationResult.get("createdBefore"))) {
      return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build();
    }

    return new ResponseEntity<>(HttpStatus.TOO_MANY_REQUESTS);
  }

  ResponseEntity<Void> activateTwoFactorAuthByApp(OneTimePasswordDTO oneTimePasswordDTO) {
    if (authenticatedUser.isAdviceSeeker()
        && isFalse(identityClientConfig.getOtpAllowedForUsers())) {
      throw new ConflictException("2FA is disabled for user role");
    }
    if (authenticatedUser.isConsultant()
        && isFalse(identityClientConfig.getOtpAllowedForConsultants())) {
      throw new ConflictException("2FA is disabled for consultant role");
    }
    if (authenticatedUser.isSingleTenantAdmin()
        && isFalse(identityClientConfig.getOtpAllowedForSingleTenantAdmins())) {
      throw new ConflictException("2FA is disabled for single tenant admin role");
    }
    if (authenticatedUser.isTenantSuperAdmin()
        && isFalse(identityClientConfig.getOtpAllowedForTenantSuperAdmins())) {
      throw new ConflictException("2FA is disabled for tenant admin role");
    }

    var isValid =
        identityManager.setUpOneTimePassword(
            usernameTranscoder.encodeUsername(authenticatedUser.getUsername()),
            oneTimePasswordDTO.getOtp(),
            oneTimePasswordDTO.getSecret());

    return isValid ? ResponseEntity.ok().build() : ResponseEntity.badRequest().build();
  }

  ResponseEntity<Void> deactivateTwoFactorAuthByApp() {
    identityManager.deleteOneTimePassword(
        usernameTranscoder.encodeUsername(authenticatedUser.getUsername()));

    return new ResponseEntity<>(HttpStatus.OK);
  }
}
