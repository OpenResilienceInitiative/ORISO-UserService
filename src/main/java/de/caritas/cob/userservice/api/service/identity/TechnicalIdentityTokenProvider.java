package de.caritas.cob.userservice.api.service.identity;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Provides the configured technical user's identity token without repeating a password grant for
 * every internal service call.
 *
 * <p>The cache is deliberately local to one UserService replica. It never persists credentials or
 * tokens in Redis, refreshes before the identity provider's expiry, and serializes cache misses so
 * concurrent requests share one grant.
 */
@Service
@RequiredArgsConstructor
public class TechnicalIdentityTokenProvider {

  private final @NonNull IdentityAuthentication identityAuthentication;
  private final @NonNull IdentityClientConfig identityClientConfig;
  private final @NonNull Clock clock;

  private String cachedAccessToken;
  private Instant reusableUntil = Instant.EPOCH;

  public synchronized String getAccessToken() {
    var now = clock.instant();
    if (!isBlank(cachedAccessToken) && now.isBefore(reusableUntil)) {
      return cachedAccessToken;
    }

    var technicalUser = identityClientConfig.getTechnicalUser();
    var login =
        identityAuthentication.login(technicalUser.getUsername(), technicalUser.getPassword());
    if (login == null || isBlank(login.accessToken())) {
      throw new IllegalStateException("Technical identity login returned no access token");
    }

    cachedAccessToken = login.accessToken();
    reusableUntil = reusableUntil(now, login.expiresIn());
    return cachedAccessToken;
  }

  public synchronized void invalidate(String rejectedAccessToken) {
    if (Objects.equals(cachedAccessToken, rejectedAccessToken)) {
      cachedAccessToken = null;
      reusableUntil = Instant.EPOCH;
    }
  }

  private Instant reusableUntil(Instant grantedAt, int expiresInSeconds) {
    if (expiresInSeconds <= 1) {
      return Instant.EPOCH;
    }
    long refreshSkewSeconds = Math.min(30, Math.max(1, expiresInSeconds / 10));
    return grantedAt.plusSeconds(expiresInSeconds - refreshSkewSeconds);
  }
}
