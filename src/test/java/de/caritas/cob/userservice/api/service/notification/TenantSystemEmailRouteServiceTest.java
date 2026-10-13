package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantSystemEmailRouteServiceTest {
  @Mock TenantSystemEmailClient client;

  @Test
  void platformModeNeedsNoTenantSmtpCredentials() {
    when(client.readTenant(40L))
        .thenReturn(
            Map.of(
                "settings",
                Map.of("featureSystemNotificationEmailsEnabled", true, "smtpMode", "PLATFORM")));

    var route = new TenantSystemEmailRouteService(client).resolve(40L);

    assertThat(route)
        .hasValueSatisfying(
            value ->
                assertThat(value.mode()).isEqualTo(TenantSystemEmailRouteService.Mode.PLATFORM));
    verify(client).readTenant(40L);
  }

  @Test
  void ownModeRequiresCompleteRedactedSettingsWithoutReadingPassword() {
    when(client.readTenant(40L))
        .thenReturn(
            Map.of(
                "settings",
                Map.of(
                    "featureSystemNotificationEmailsEnabled",
                    true,
                    "smtpMode",
                    "OWN",
                    "smtp",
                    Map.of(
                        "enabled",
                        true,
                        "host",
                        "smtp.tenant.example",
                        "port",
                        587,
                        "secure",
                        false,
                        "username",
                        "sender",
                        "from",
                        "sender@tenant.example",
                        "passwordSet",
                        true))));

    var route = new TenantSystemEmailRouteService(client).resolve(40L);

    assertThat(route)
        .hasValueSatisfying(
            value -> assertThat(value.mode()).isEqualTo(TenantSystemEmailRouteService.Mode.OWN));
  }

  @Test
  void incompleteOwnAndLegacyAreErrorsInsteadOfPlatformFallback() {
    when(client.readTenant(40L))
        .thenReturn(
            Map.of(
                "settings",
                Map.of(
                    "featureSystemNotificationEmailsEnabled",
                    true,
                    "smtpMode",
                    "OWN",
                    "smtp",
                    Map.of("enabled", true, "passwordSet", false))));
    var service = new TenantSystemEmailRouteService(client);
    assertThatThrownBy(() -> service.resolve(40L))
        .isInstanceOf(TenantSystemEmailRouteService.ConfigurationException.class)
        .hasMessageContaining("OWN");

    when(client.readTenant(40L))
        .thenReturn(Map.of("settings", Map.of("featureSystemNotificationEmailsEnabled", true)));
    assertThatThrownBy(() -> service.resolve(40L))
        .isInstanceOf(TenantSystemEmailRouteService.ConfigurationException.class)
        .hasMessageContaining("smtpMode");

    when(client.readTenant(40L))
        .thenReturn(
            Map.of(
                "settings",
                Map.of(
                    "featureSystemNotificationEmailsEnabled",
                    true,
                    "smtpMode",
                    "OWN",
                    "smtp",
                    Map.of("enabled", false))));
    assertThatThrownBy(() -> service.resolve(40L))
        .isInstanceOf(TenantSystemEmailRouteService.ConfigurationException.class)
        .hasMessageContaining("OWN");
  }

  @Test
  void disabledSystemMailSendsNothing() {
    when(client.readTenant(40L))
        .thenReturn(Map.of("settings", Map.of("featureSystemNotificationEmailsEnabled", false)));
    assertThat(new TenantSystemEmailRouteService(client).resolve(40L)).isEmpty();
  }

  @Test
  void transportForAlwaysSentMailsIgnoresTheNotificationSwitch() {
    when(client.readTenant(40L))
        .thenReturn(
            Map.of(
                "settings",
                Map.of(
                    "featureSystemNotificationEmailsEnabled",
                    false,
                    "smtpMode",
                    "OWN",
                    "smtp",
                    Map.of(
                        "enabled",
                        true,
                        "host",
                        "smtp.tenant.example",
                        "port",
                        587,
                        "secure",
                        false,
                        "username",
                        "sender",
                        "from",
                        "sender@tenant.example",
                        "passwordSet",
                        true))));
    var routes = new TenantSystemEmailRouteService(client);

    assertThat(routes.resolveTransport(40L).mode())
        .isEqualTo(TenantSystemEmailRouteService.Mode.OWN);
    assertThat(routes.resolve(40L)).isEmpty();
  }

  @Test
  void transportForUnclassifiedTenantStaysOnThePlatform() {
    var routes = new TenantSystemEmailRouteService(client);
    var withoutMode = new java.util.HashMap<String, Object>();
    withoutMode.put("smtpMode", null);
    withoutMode.put("smtp", Map.of("enabled", true, "host", "smtp.tenant.example"));

    when(client.readTenant(40L)).thenReturn(Map.of("settings", withoutMode));
    assertThat(routes.resolveTransport(40L).mode())
        .isEqualTo(TenantSystemEmailRouteService.Mode.PLATFORM);

    when(client.readTenant(41L)).thenReturn(Map.of("id", 41L));
    assertThat(routes.resolveTransport(41L).mode())
        .isEqualTo(TenantSystemEmailRouteService.Mode.PLATFORM);
  }

  @Test
  void transportRejectsAnUnknownExplicitMode() {
    when(client.readTenant(40L)).thenReturn(Map.of("settings", Map.of("smtpMode", "RELAY")));

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
}
