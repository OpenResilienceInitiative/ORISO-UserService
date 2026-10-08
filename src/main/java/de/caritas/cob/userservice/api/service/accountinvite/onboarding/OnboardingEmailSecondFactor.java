package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Uses the existing OTP SPI; the invitation, never browser input, determines the recipient. */
final class OnboardingEmailSecondFactor {
  private OnboardingEmailSecondFactor() {}

  static void start(
      AccountInvite invite, IdentityProfileLookup profiles, IdentitySecondFactor otp) {
    IdentityProfile profile = pendingProfile(invite, profiles, otp);
    var started = otp.initiateEmailVerification(profile.username(), invite.getRecipientEmail());
    if (started == null || !started.started()) {
      // Do not forward the provider's message: it may contain credentials or delivery details.
      throw new ServiceUnavailableException("Email verification is unavailable");
    }
  }

  static void verify(
      AccountInvite invite, String code, IdentityProfileLookup profiles, IdentitySecondFactor otp) {
    IdentityProfile profile = pendingProfile(invite, profiles, otp);
    var verified = otp.finishEmailVerification(profile.username(), code.trim());
    if (verified != null && verified.created()) {
      if (verified.email() == null
          || !invite.getRecipientEmail().equalsIgnoreCase(verified.email())) {
        throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
      }
      return;
    }
    if (verified != null && verified.createdBefore()) {
      throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
    }
    if (verified != null && !verified.attemptsLeft()) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    }
    throw new BadRequestException("Invalid or expired one-time password");
  }

  static void requireInactive(IdentityProfile profile, IdentitySecondFactor otp) {
    var credential = otp.getOtpCredential(profile.username());
    if (credential != null && Boolean.TRUE.equals(credential.setup())) {
      throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
    }
  }

  private static IdentityProfile pendingProfile(
      AccountInvite invite, IdentityProfileLookup profiles, IdentitySecondFactor otp) {
    var profile =
        profiles
            .findById(invite.getAcceptedByUserId())
            .orElseThrow(
                () -> new BadRequestException("No identity profile exists for this invite"));
    requireInactive(profile, otp);
    if (invite.getRecipientEmail() == null || invite.getRecipientEmail().isBlank()) {
      throw new BadRequestException("Invite recipient email is required");
    }
    return profile;
  }
}
