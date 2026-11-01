package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantSystemEmailRouteServiceTest {
  @Mock TenantSystemEmailClient client;

  @Test
  void platformModeNeedsNoTenantSmtpCredentials() {
    when(client.readMailContext(40L)).thenReturn(context(true, "PLATFORM", false));

    var route = new TenantSystemEmailRouteService(client).resolve(40L);

    assertThat(route)
        .hasValueSatisfying(
            value ->
                assertThat(value.mode()).isEqualTo(TenantSystemEmailRouteService.Mode.PLATFORM));
    verify(client).readMailContext(40L);
  }

  @Test
  void ownModeRequiresCompleteRedactedSettingsWithoutReadingPassword() {
    when(client.readMailContext(40L)).thenReturn(context(true, "OWN", true));

    var route = new TenantSystemEmailRouteService(client).resolve(40L);

    assertThat(route)
        .hasValueSatisfying(
            value -> assertThat(value.mode()).isEqualTo(TenantSystemEmailRouteService.Mode.OWN));
  }

  @Test
  void incompleteOwnModeIsAnErrorInsteadOfPlatformFallback() {
    when(client.readMailContext(40L)).thenReturn(context(true, "OWN", false));

    assertThatThrownBy(() -> new TenantSystemEmailRouteService(client).resolve(40L))
        .isInstanceOf(TenantSystemEmailRouteService.ConfigurationException.class)
        .hasMessageContaining("OWN");
  }

  @Test
  void missingNotificationPolicyIsASetupErrorRatherThanIntentionalSuppression() {
    when(client.readMailContext(40L)).thenReturn(context(null, "PLATFORM", false));

    assertThatThrownBy(() -> new TenantSystemEmailRouteService(client).resolve(40L))
        .isInstanceOf(TenantSystemEmailRouteService.ConfigurationException.class)
        .hasMessageContaining("notification policy");
  }

  @Test
  void disabledSystemMailSendsNothing() {
    when(client.readMailContext(40L)).thenReturn(context(false, "PLATFORM", false));

    assertThat(new TenantSystemEmailRouteService(client).resolve(40L)).isEmpty();
  }

  @Test
  void transportForAlwaysSentMailsIgnoresTheNotificationSwitch() {
    when(client.readMailContext(40L)).thenReturn(context(false, "OWN", true));
    var routes = new TenantSystemEmailRouteService(client);

    assertThat(routes.resolveTransport(40L).mode())
        .isEqualTo(TenantSystemEmailRouteService.Mode.OWN);
    assertThat(routes.resolve(40L)).isEmpty();
  }

  @Test
  void transportForUnclassifiedLegacyTenantStaysOnThePlatform() {
    when(client.readMailContext(40L))
        .thenReturn(new TenantSystemEmailClient.MailContext(null, null, null, false, false));

    assertThat(new TenantSystemEmailRouteService(client).resolveTransport(40L).mode())
        .isEqualTo(TenantSystemEmailRouteService.Mode.PLATFORM);
  }

  @Test
  void transportRejectsAnUnknownExplicitMode() {
    when(client.readMailContext(40L)).thenReturn(context(true, "RELAY", false));

    assertThatThrownBy(() -> new TenantSystemEmailRouteService(client).resolveTransport(40L))
        .isInstanceOf(TenantSystemEmailRouteService.ConfigurationException.class);
  }

  @Test
  void transportWithoutTenantIsThePlatformWithoutAnyRead() {
    var routes = new TenantSystemEmailRouteService(client);

    assertThat(routes.resolveTransport(null).mode())
        .isEqualTo(TenantSystemEmailRouteService.Mode.PLATFORM);
    org.mockito.Mockito.verifyNoInteractions(client);
  }

  private static TenantSystemEmailClient.MailContext context(
      Boolean notificationsEnabled, String smtpMode, boolean smtpConfigured) {
    return new TenantSystemEmailClient.MailContext(
        notificationsEnabled, smtpMode, "#005500", smtpConfigured, smtpConfigured);
  }
}
