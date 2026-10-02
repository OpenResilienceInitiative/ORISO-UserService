package de.caritas.cob.userservice.api.service.httpheader;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Request-thread override for authenticated service-to-service calls in public workflows.
 *
 * <p>While a token is set, {@link SecurityHeaderSupplier#getKeycloakAndCsrfHttpHeaders()} sends it
 * instead of the logged-in user's token. Keep the scope as small as possible: prefer {@link
 * #callWith} / {@link #runWith} around exactly the remote calls that need the service identity, and
 * prefer passing the token explicitly ({@link SecurityHeaderSupplier#getKeycloakAndCsrfHttpHeaders(
 * String)}) where a client allows it. Human-triggered calls never use this context
 * (ORISO-Helm#367).
 *
 * <p>Remaining ambient scopes: the counsellor invite provisioning saga (agency re-check, consultant
 * creation, agency assignment and their rollback, which reach
 * TenantService/AgencyService/ConsultingType clients through the shared admin services) and the
 * reservation-release retry scheduler. The expiry sweep and the queued-invite release only offer
 * their token ({@link #offerDuring}); the ID-allocation clients use it for their own requests.
 */
public final class TechnicalAccessTokenContext {

  private static final ThreadLocal<String> ACCESS_TOKEN = new ThreadLocal<>();
  private static final ThreadLocal<String> OFFERED_TOKEN = new ThreadLocal<>();

  private TechnicalAccessTokenContext() {}

  public static void set(String accessToken) {
    ACCESS_TOKEN.set(accessToken);
  }

  public static Optional<String> get() {
    return Optional.ofNullable(ACCESS_TOKEN.get());
  }

  public static void clear() {
    ACCESS_TOKEN.remove();
  }

  /** Runs {@code action} with the token set and restores whatever was set before. */
  public static <T> T callWith(String accessToken, Supplier<T> action) {
    if (accessToken == null || accessToken.isBlank()) {
      throw new IllegalArgumentException("A service access token is required");
    }
    String previous = ACCESS_TOKEN.get();
    ACCESS_TOKEN.set(accessToken);
    try {
      return action.get();
    } finally {
      if (previous == null) {
        ACCESS_TOKEN.remove();
      } else {
        ACCESS_TOKEN.set(previous);
      }
    }
  }

  /** {@link #callWith} for blocks without a result. */
  public static void runWith(String accessToken, Runnable action) {
    callWith(
        accessToken,
        () -> {
          action.run();
          return null;
        });
  }

  /**
   * Offers {@code accessToken} to the ID-allocation clients during {@code work} without making it
   * ambient: local writes and SMTP in between never carry the service identity.
   */
  public static <T> T offerDuring(String accessToken, Supplier<T> work) {
    String previous = OFFERED_TOKEN.get();
    OFFERED_TOKEN.set(accessToken);
    try {
      return work.get();
    } finally {
      if (previous == null) {
        OFFERED_TOKEN.remove();
      } else {
        OFFERED_TOKEN.set(previous);
      }
    }
  }

  /** The token a background job offered for its ID-allocation calls, if any. */
  public static Optional<String> offered() {
    return Optional.ofNullable(OFFERED_TOKEN.get());
  }
}
