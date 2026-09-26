package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PlatformSmtpSettingsProviderTest {
  @Test
  void resolvesOnlyTheDeploymentValues() {
    var provider =
        new PlatformSmtpSettingsProvider(
            "smtp.example.org", "587", "false", "sender", "secret", "mail@example.org", true);

    provider.validateAtStartup();
    var settings = provider.requireConfigured();

    assertThat(settings.host()).isEqualTo("smtp.example.org");
    assertThat(settings.port()).isEqualTo(587);
    assertThat(settings.secure()).isFalse();
    assertThat(settings.username()).isEqualTo("sender");
    assertThat(settings.password()).isEqualTo("secret");
    assertThat(settings.from()).isEqualTo("mail@example.org");
    assertThat(settings.toString()).doesNotContain("secret", "sender");
  }

  @Test
  void namesEveryMissingDeploymentSettingWithoutRevealingCredentials() {
    var provider = new PlatformSmtpSettingsProvider("", "", "", "", "", "", true);

    assertThatThrownBy(provider::validateAtStartup)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SMTP_HOST")
        .hasMessageContaining("SMTP_PORT")
        .hasMessageContaining("SMTP_SECURE")
        .hasMessageContaining("SMTP_USER")
        .hasMessageContaining("SMTP_PASSWORD")
        .hasMessageContaining("SMTP_FROM");
  }

  @Test
  void rejectsInvalidPortAndSecurityMode() {
    var provider =
        new PlatformSmtpSettingsProvider(
            "smtp.example.org", "70000", "maybe", "sender", "secret", "mail@example.org", false);

    assertThatThrownBy(provider::requireConfigured)
        .hasMessageContaining("SMTP_PORT")
        .hasMessageContaining("SMTP_SECURE")
        .hasMessageNotContaining("secret");
  }

  @Test
  void rejectsInvalidSenderAddress() {
    var provider =
        new PlatformSmtpSettingsProvider(
            "smtp.example.org", "587", "false", "sender", "secret", "not-an-address", true);

    assertThatThrownBy(provider::validateAtStartup)
        .hasMessageContaining("SMTP_FROM")
        .hasMessageNotContaining("secret");
  }

  @Test
  void optionalLocalStartupStillRejectsAnAttemptedSend() {
    var provider = new PlatformSmtpSettingsProvider("", "", "", "", "", "", false);

    provider.validateAtStartup();
    assertThatThrownBy(provider::requireConfigured).hasMessageContaining("SMTP_HOST");
  }

  @Test
  void publicSummaryDescribesEffectiveDeploymentWithoutCredentials() throws Exception {
    var provider =
        new PlatformSmtpSettingsProvider(
            "smtp.example.org", "587", "false", "sender", "secret", "mail@example.org", true);

    String json =
        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(provider.summary());

    assertThat(json)
        .contains("smtp.example.org", "mail@example.org", "\"configured\":true")
        .doesNotContain("sender", "secret", "username", "password");
  }

  @Test
  void incompleteOptionalDeploymentHasNoConfiguredSummary() {
    var provider = new PlatformSmtpSettingsProvider("", "", "", "", "", "", false);

    var summary = provider.summary();

    assertThat(summary.configured()).isFalse();
    assertThat(summary.credentialsPresent()).isFalse();
  }
}
