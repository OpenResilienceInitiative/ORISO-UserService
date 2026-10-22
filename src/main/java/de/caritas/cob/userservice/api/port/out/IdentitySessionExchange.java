package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.identity.IdentitySession;
import de.caritas.cob.userservice.api.service.auth.MagicLinkLoginService.ClaimedMagicLink;
import java.util.Optional;

/** Exchanges a trusted identity subject for an authenticated provider-neutral session. */
public interface IdentitySessionExchange {

  Optional<IdentitySession> exchangeForUser(String identityUserId);

  /** Continuation uses the same private held workflow; a factor never selects a subject. */
  default Optional<IdentitySession> exchangeMagicLink(ClaimedMagicLink claim, String otp) {
    return otp == null ? exchangeMagicLink(claim) : Optional.empty();
  }

  /** Finite native challenge only; provider messages and request values are never exposed. */
  final class MagicLinkChallenge extends RuntimeException {
    private final String otpType;
    private final Integer resendAvailableInSeconds;
    private final int status;

    public MagicLinkChallenge(String otpType, Integer resendAvailableInSeconds, int status) {
      super("Magic-link verification required");
      if (!("APP".equals(otpType) || "EMAIL".equals(otpType))
          || resendAvailableInSeconds != null && resendAvailableInSeconds < 0
          || status != 400 && status != 429) {
        throw new IllegalArgumentException("Invalid magic-link challenge");
      }
      this.otpType = otpType;
      this.resendAvailableInSeconds = resendAvailableInSeconds;
      this.status = status;
    }

    public String otpType() {
      return otpType;
    }

    public Integer resendAvailableInSeconds() {
      return resendAvailableInSeconds;
    }

    public int status() {
      return status;
    }

    @Override
    public String toString() {
      return "MagicLinkChallenge[redacted]";
    }
  }

  default Optional<IdentitySession> exchangeMagicLink(ClaimedMagicLink claim) {
    return Optional.empty();
  }
}
