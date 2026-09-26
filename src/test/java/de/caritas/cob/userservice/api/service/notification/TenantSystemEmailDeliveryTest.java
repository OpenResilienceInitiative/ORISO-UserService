package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.email.OrisoEmailDispatcher;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

  @ParameterizedTest
  @EnumSource(
      value = TenantSystemEmailDelivery.Purpose.class,
      names = {
        "SELF_HELP_APPOINTMENT_CONFIRMED",
        "SELF_HELP_APPOINTMENT_RESCHEDULED",
        "SELF_HELP_APPOINTMENT_CANCELLED",
        "SELF_HELP_APPOINTMENT_REMINDER"
      })
  void groupAppointmentUsesOnlyOwnRelay(TenantSystemEmailDelivery.Purpose purpose) {
    new TenantSystemEmailDelivery(tenantClient, platformSettings, platformDispatcher)
        .sendConfirmed(
            40L,
            new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.OWN, null),
            purpose,
            "recipient@example.org",
            email);

    verify(tenantClient).deliver(40L, purpose.name(), "recipient@example.org", email);
    verifyNoInteractions(platformSettings, platformDispatcher);
  }

  @Test
  void durableAppointmentCorrelationReachesOwnRelayWithoutPlatformCredentials() {
    String correlation = "f7e8bbfe-7ca9-4e8e-8c55-54575ceca5a9";
    new TenantSystemEmailDelivery(tenantClient, platformSettings, platformDispatcher)
        .sendConfirmed(
            40L,
            new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.OWN, null),
            TenantSystemEmailDelivery.Purpose.SELF_HELP_APPOINTMENT_REMINDER,
            "recipient@example.org",
            email,
            correlation);

    verify(tenantClient)
        .deliver(
            40L, "SELF_HELP_APPOINTMENT_REMINDER", "recipient@example.org", email, correlation);
    verifyNoInteractions(platformSettings, platformDispatcher);
  }

  @Test
  void durableAppointmentCorrelationReachesPlatformMimeDispatcher() {
    String correlation = "f7e8bbfe-7ca9-4e8e-8c55-54575ceca5a9";
    var smtp =
        new PlatformSmtpSettingsProvider.Settings(
            "smtp.platform.example", 587, false, "account", "secret", "sender@platform.example");
    when(platformSettings.requireConfigured()).thenReturn(smtp);

    new TenantSystemEmailDelivery(tenantClient, platformSettings, platformDispatcher)
        .sendConfirmed(
            40L,
            new TenantSystemEmailRouteService.Route(
                TenantSystemEmailRouteService.Mode.PLATFORM, null),
            TenantSystemEmailDelivery.Purpose.SELF_HELP_APPOINTMENT_REMINDER,
            "recipient@example.org",
            email,
            correlation);

    verify(platformDispatcher).send(smtp, "recipient@example.org", email, correlation);
    verifyNoInteractions(tenantClient);
  }
}
