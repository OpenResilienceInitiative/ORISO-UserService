package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.email.OrisoEmailDispatcher;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantSystemEmailDeliveryTest {
  @Mock TenantSystemEmailClient tenantClient;
  @Mock PlatformSmtpSettingsProvider platformSettings;
  @Mock OrisoEmailDispatcher platformDispatcher;

  private final OrisoEmailRenderer.RenderedEmail email =
      new OrisoEmailRenderer.RenderedEmail("Subject", "<p>Body</p>", "Body");

  @Test
  void platformNeverCallsTenantDelivery() {
    var smtp =
        new PlatformSmtpSettingsProvider.Settings(
            "smtp.platform.example", 587, false, "account", "secret", "sender@platform.example");
    when(platformSettings.requireConfigured()).thenReturn(smtp);

    new TenantSystemEmailDelivery(tenantClient, platformSettings, platformDispatcher)
        .send(
            40L,
            new TenantSystemEmailRouteService.Route(
                TenantSystemEmailRouteService.Mode.PLATFORM, null),
            TenantSystemEmailDelivery.Purpose.SUPERVISOR_ADDED,
            "recipient@example.org",
            email);

    verify(platformDispatcher).send(smtp, "recipient@example.org", email);
    verify(tenantClient, never())
        .deliver(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void ownNeverReadsPlatformCredentials() {
    new TenantSystemEmailDelivery(tenantClient, platformSettings, platformDispatcher)
        .send(
            40L,
            new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.OWN, null),
            TenantSystemEmailDelivery.Purpose.SUPERVISOR_REMOVED,
            "recipient@example.org",
            email);

    verify(tenantClient).deliver(40L, "SUPERVISOR_REMOVED", "recipient@example.org", email);
    verify(platformSettings, never()).requireConfigured();
  }

  @Test
  void platformFailureIsObservableToNotificationSender() {
    var smtp =
        new PlatformSmtpSettingsProvider.Settings(
            "smtp.platform.example", 587, false, "account", "secret", "sender@platform.example");
    when(platformSettings.requireConfigured()).thenReturn(smtp);
    when(platformDispatcher.send(smtp, "recipient@example.org", email)).thenReturn(false);

    boolean accepted =
        new TenantSystemEmailDelivery(tenantClient, platformSettings, platformDispatcher)
            .sendConfirmed(
                40L,
                new TenantSystemEmailRouteService.Route(
                    TenantSystemEmailRouteService.Mode.PLATFORM, null),
                TenantSystemEmailDelivery.Purpose.NEW_ENQUIRY,
                "recipient@example.org",
                email);

    assertThat(accepted).isFalse();
  }

  @Test
  void replyMailKeepsAnAmbiguousPlatformSmtpFailureVisible() {
    var smtp =
        new PlatformSmtpSettingsProvider.Settings(
            "smtp.platform.example", 587, false, "account", "secret", "sender@platform.example");
    when(platformSettings.requireConfigured()).thenReturn(smtp);
    org.mockito.Mockito.doThrow(new IllegalStateException("SMTP acknowledgement lost"))
        .when(platformDispatcher)
        .sendOrThrow(
            smtp,
            "recipient@example.org",
            email,
            java.util.UUID.fromString("ab2e5141-2f26-456a-9e46-0ff642918115"));

    assertThatThrownBy(
            () ->
                new TenantSystemEmailDelivery(tenantClient, platformSettings, platformDispatcher)
                    .sendReply(
                        40L,
                        new TenantSystemEmailRouteService.Route(
                            TenantSystemEmailRouteService.Mode.PLATFORM, null),
                        "recipient@example.org",
                        email,
                        java.util.UUID.fromString("ab2e5141-2f26-456a-9e46-0ff642918115")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SMTP acknowledgement lost");
  }
}
