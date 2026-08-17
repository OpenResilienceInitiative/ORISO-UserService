package de.caritas.cob.userservice.api.service.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TechnicalIdentityTokenProviderTest {

  @Mock private IdentityAuthentication identityAuthentication;
  @Mock private IdentityClientConfig identityClientConfig;

  private MutableClock clock;
  private TechnicalIdentityTokenProvider provider;

  @BeforeEach
  void setUp() {
    var technicalUser = new TechnicalUserConfig();
    technicalUser.setUsername("technical");
    technicalUser.setPassword("secret");
    when(identityClientConfig.getTechnicalUser()).thenReturn(technicalUser);
    clock = new MutableClock(Instant.parse("2026-07-29T08:00:00Z"));
    provider =
        new TechnicalIdentityTokenProvider(identityAuthentication, identityClientConfig, clock);
  }

  @Test
  void parallelCallersShareOneUnexpiredTechnicalUserGrant() throws Exception {
    when(identityAuthentication.login("technical", "secret"))
        .thenReturn(new IdentityLogin("shared-token", 300, 1800, "refresh"));

    try (var callers = Executors.newFixedThreadPool(16)) {
      List<Callable<String>> requests =
          java.util.stream.IntStream.range(0, 64)
              .mapToObj(ignored -> (Callable<String>) provider::getAccessToken)
              .toList();

      var tokens =
          callers.invokeAll(requests).stream()
              .map(
                  result -> {
                    try {
                      return result.get();
                    } catch (Exception exception) {
                      throw new AssertionError(exception);
                    }
                  })
              .toList();

      assertThat(tokens).containsOnly("shared-token");
    }

    verify(identityAuthentication).login("technical", "secret");
  }

  @Test
  void refreshesBeforeTheCachedGrantExpires() {
    when(identityAuthentication.login("technical", "secret"))
        .thenReturn(
            new IdentityLogin("first-token", 100, 1800, "refresh"),
            new IdentityLogin("second-token", 100, 1800, "refresh"));

    assertThat(provider.getAccessToken()).isEqualTo("first-token");
    clock.advanceSeconds(89);
    assertThat(provider.getAccessToken()).isEqualTo("first-token");
    clock.advanceSeconds(2);
    assertThat(provider.getAccessToken()).isEqualTo("second-token");

    verify(identityAuthentication, times(2)).login("technical", "secret");
  }

  @Test
  void invalidatingTheCurrentGrantForcesOneFreshLogin() {
    when(identityAuthentication.login("technical", "secret"))
        .thenReturn(
            new IdentityLogin("stale-token", 300, 1800, "refresh"),
            new IdentityLogin("fresh-token", 300, 1800, "refresh"));

    assertThat(provider.getAccessToken()).isEqualTo("stale-token");
    provider.invalidate("another-token");
    assertThat(provider.getAccessToken()).isEqualTo("stale-token");
    provider.invalidate("stale-token");
    assertThat(provider.getAccessToken()).isEqualTo("fresh-token");

    verify(identityAuthentication, times(2)).login("technical", "secret");
  }

  @Test
  void zeroLifetimeGrantIsNeverReused() {
    when(identityAuthentication.login("technical", "secret"))
        .thenReturn(
            new IdentityLogin("first-token", 0, 0, "refresh"),
            new IdentityLogin("second-token", 0, 0, "refresh"));

    assertThat(provider.getAccessToken()).isEqualTo("first-token");
    assertThat(provider.getAccessToken()).isEqualTo("second-token");
  }

  private static final class MutableClock extends Clock {

    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    private void advanceSeconds(long seconds) {
      current = current.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }
  }
}
