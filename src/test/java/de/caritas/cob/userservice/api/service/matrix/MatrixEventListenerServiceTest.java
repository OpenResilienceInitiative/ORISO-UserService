package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.liveevents.LiveEventNotificationService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Focused resilience tests for the Matrix sync loop bootstrap/backoff (hardening B1). These verify
 * the backoff schedule and that a transient null admin token does not permanently stop retrying.
 * They never sleep for real: the {@code sleep(long)} seam is stubbed to a no-op.
 */
@ExtendWith(MockitoExtension.class)
class MatrixEventListenerServiceTest {

  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private SessionService sessionService;
  @Mock private LiveEventNotificationService liveEventNotificationService;
  @Mock private EventNotificationService eventNotificationService;
  @Mock private UserRepository userRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private SessionRepository sessionRepository;

  private MatrixEventListenerService newService() {
    return new MatrixEventListenerService(
        matrixSynapseService,
        sessionService,
        liveEventNotificationService,
        eventNotificationService,
        Optional.empty(),
        userRepository,
        consultantRepository,
        sessionRepository);
  }

  @Test
  void nextBackoffMillis_shouldFollowExponentialScheduleCappedAt60s() {
    assertThat(MatrixEventListenerService.nextBackoffMillis(5_000L)).isEqualTo(10_000L);
    assertThat(MatrixEventListenerService.nextBackoffMillis(10_000L)).isEqualTo(20_000L);
    assertThat(MatrixEventListenerService.nextBackoffMillis(20_000L)).isEqualTo(40_000L);
    assertThat(MatrixEventListenerService.nextBackoffMillis(40_000L)).isEqualTo(60_000L);
    // Capped: 40s doubles to 80s but is clamped to the 60s ceiling, and stays there.
    assertThat(MatrixEventListenerService.nextBackoffMillis(60_000L)).isEqualTo(60_000L);
  }

  @Test
  void backoffSchedule_startsAt5sAndCapsAt60s() {
    long current = MatrixEventListenerService.INITIAL_BACKOFF_MS;
    long[] expected = {5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L};

    assertThat(current).isEqualTo(expected[0]);
    for (int i = 1; i < expected.length; i++) {
      current = MatrixEventListenerService.nextBackoffMillis(current);
      assertThat(current).as("backoff step %d", i).isEqualTo(expected[i]);
    }
    assertThat(MatrixEventListenerService.MAX_BACKOFF_MS).isEqualTo(60_000L);
  }

  @Test
  void bootstrapAdminToken_shouldKeepRetryingUntilTokenBecomesAvailable() {
    // No-op sleep so the retry loop does not wait for real backoff.
    MatrixEventListenerService service =
        new MatrixEventListenerService(
            matrixSynapseService,
            sessionService,
            liveEventNotificationService,
            eventNotificationService,
            Optional.empty(),
            userRepository,
            consultantRepository,
            sessionRepository) {
          @Override
          void sleep(long millis) {
            // deterministic: never actually sleep in the test
          }
        };
    ReflectionTestUtils.setField(service, "running", true);

    // Two transient failures (null) then a real token: the loop must not give up on the first null.
    when(matrixSynapseService.getAdminToken()).thenReturn(null, null, "admin-token-123");

    boolean acquired = (boolean) ReflectionTestUtils.invokeMethod(service, "bootstrapAdminToken");

    assertThat(acquired).isTrue();
    assertThat(ReflectionTestUtils.getField(service, "adminAccessToken"))
        .isEqualTo("admin-token-123");
    // Proves it did not stop after the first null: getAdminToken was polled repeatedly.
    verify(matrixSynapseService, atLeast(3)).getAdminToken();
  }

  @Test
  void bootstrapAdminToken_shouldStopWhenNotRunning() {
    MatrixEventListenerService service = newService();
    // running defaults to false -> the bootstrap loop must exit immediately without polling.
    boolean acquired = (boolean) ReflectionTestUtils.invokeMethod(service, "bootstrapAdminToken");

    assertThat(acquired).isFalse();
  }
}
