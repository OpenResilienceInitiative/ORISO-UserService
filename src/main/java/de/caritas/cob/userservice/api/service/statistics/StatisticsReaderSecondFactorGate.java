package de.caritas.cob.userservice.api.service.statistics;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ORISO-UserService#1666: 2FA is mandatory for statistics readers, so the server refuses their
 * statistics reads until the OTP credential is active. Fails closed: an unreadable OTP state never
 * lets a reader through. Other admins are unaffected (their own 2FA gate lives in the Admin app).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatisticsReaderSecondFactorGate {

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull IdentitySecondFactor identitySecondFactor;

  public void requireActiveSecondFactor() {
    if (!authenticatedUser.isStatisticsReader()) {
      return;
    }
    boolean otpActive;
    try {
      var otp =
          identitySecondFactor.getOtpCredential(
              new UsernameTranscoder().encodeUsername(authenticatedUser.getUsername()));
      otpActive = otp != null && Boolean.TRUE.equals(otp.setup());
    } catch (RuntimeException e) {
      log.warn(
          "Could not read OTP state for statistics reader {}; failing closed ({})",
          authenticatedUser.getUserId(),
          e.getClass().getSimpleName());
      otpActive = false;
    }
    if (!otpActive) {
      throw new ForbiddenException(
          String.format(
              "Statistics reader %s must set up 2FA before reading statistics",
              authenticatedUser.getUserId()));
    }
  }
}
