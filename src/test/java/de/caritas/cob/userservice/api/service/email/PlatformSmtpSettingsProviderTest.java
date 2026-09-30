package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsSmtpCredentialsDTO;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlatformSmtpSettingsProviderTest {
  private final ApplicationSettingsService service = mock(ApplicationSettingsService.class);
  private final PlatformSmtpSettingsProvider provider = new PlatformSmtpSettingsProvider(service);

  @Test
  void readsOnlyTheAdminSettingsSnapshot() {
    when(service.getGlobalSmtpCredentials())
        .thenReturn(Optional.of(PlatformSmtpSettingsFixture.credentials("sender", "secret")));

    var settings = provider.requireConfigured();

    assertThat(settings.host()).isEqualTo("smtp.example.org");
    assertThat(settings.port()).isEqualTo(587);
    assertThat(settings.secure()).isFalse();
    assertThat(settings.username()).isEqualTo("sender");
    assertThat(settings.password()).isEqualTo("secret");
    assertThat(settings.from()).isEqualTo("noreply@example.org");
    assertThat(settings.toString()).doesNotContain("secret", "sender");
  }

  @Test
  void namesMissingAdminSettingsWithoutRevealingCredentials() {
    when(service.getGlobalSmtpCredentials()).thenReturn(Optional.empty());

    assertThatThrownBy(provider::requireConfigured)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Admin Settings")
        .hasMessageNotContaining("secret");
  }

  @Test
  void disabledSystemMailFailsClosed() {
    var settings =
        PlatformSmtpSettingsFixture.credentials("sender", "secret")
            .globalFeatureSystemNotificationEmailsEnabled(false);
    when(service.getGlobalSmtpCredentials()).thenReturn(Optional.of(settings));

    assertThatThrownBy(provider::requireConfigured)
        .hasMessageContaining("system notification emails enabled");
  }

  @Test
  void rejectsInvalidPortSecurityAndSender() {
    ApplicationSettingsSmtpCredentialsDTO settings =
        PlatformSmtpSettingsFixture.credentials("sender", "secret")
            .globalSmtpPort("70000")
            .globalSmtpSecure(null)
            .globalSmtpFrom("not-an-address");
    when(service.getGlobalSmtpCredentials()).thenReturn(Optional.of(settings));

    assertThatThrownBy(provider::requireConfigured)
        .hasMessageContaining("SMTP port")
        .hasMessageContaining("SMTP security mode")
        .hasMessageContaining("SMTP sender")
        .hasMessageNotContaining("secret");
  }

  @Test
  void followsCredentialRotationOnTheNextSend() {
    when(service.getGlobalSmtpCredentials())
        .thenReturn(
            Optional.of(PlatformSmtpSettingsFixture.credentials("sender", "first")),
            Optional.of(PlatformSmtpSettingsFixture.credentials("sender", "second")));

    assertThat(provider.requireConfigured().password()).isEqualTo("first");
    assertThat(provider.requireConfigured().password()).isEqualTo("second");
  }
}
