package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService.SmtpSettingsUnavailableException;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsSmtpCredentialsDTO;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlatformSmtpSettingsProviderTest {
  private final ApplicationSettingsService service = mock(ApplicationSettingsService.class);
  private final PlatformSmtpSettingsProvider provider = new PlatformSmtpSettingsProvider(service);

  @Test
  void readsOnlyTheAdminSettingsSnapshot() {
    when(service.getGlobalSmtpSettingsSnapshot())
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
    when(service.getGlobalSmtpSettingsSnapshot()).thenReturn(Optional.empty());

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
    when(service.getGlobalSmtpSettingsSnapshot()).thenReturn(Optional.of(settings));

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
    when(service.getGlobalSmtpSettingsSnapshot()).thenReturn(Optional.of(settings));

    assertThatThrownBy(provider::requireConfigured)
        .hasMessageContaining("SMTP port")
        .hasMessageContaining("SMTP security mode")
        .hasMessageContaining("SMTP sender")
        .hasMessageNotContaining("secret");
  }

  @Test
  void followsCredentialRotationOnTheNextSend() {
    when(service.getGlobalSmtpSettingsSnapshot())
        .thenReturn(
            Optional.of(PlatformSmtpSettingsFixture.credentials("sender", "first")),
            Optional.of(PlatformSmtpSettingsFixture.credentials("sender", "second")));

    assertThat(provider.requireConfigured().password()).isEqualTo("first");
    assertThat(provider.requireConfigured().password()).isEqualTo("second");
  }

  @Test
  void summaryReadsOneSavedSnapshotAndNeverSerializesCredentials() throws Exception {
    when(service.getGlobalSmtpSettingsSnapshot())
        .thenReturn(
            Optional.of(
                PlatformSmtpSettingsFixture.credentials("private-login", "private-secret")));

    String json =
        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(provider.summary());

    assertThat(json)
        .contains("smtp.example.org", "noreply@example.org", "\"configured\":true")
        .doesNotContain("private-login", "private-secret", "username", "password");
    verify(service, times(1)).getGlobalSmtpSettingsSnapshot();
  }

  @Test
  void partialSavedSettingsKeepSafeFieldsWithoutCredentials() {
    var saved =
        PlatformSmtpSettingsFixture.credentials("", "")
            .globalSmtpHost("partial.smtp.example.org")
            .globalSmtpPort("2525")
            .globalSmtpSecure(true)
            .globalSmtpFrom("partial@example.org");
    when(service.getGlobalSmtpSettingsSnapshot()).thenReturn(Optional.of(saved));

    var summary = provider.summary();

    assertThat(summary.host()).isEqualTo("partial.smtp.example.org");
    assertThat(summary.port()).isEqualTo(2525);
    assertThat(summary.secure()).isTrue();
    assertThat(summary.from()).isEqualTo("partial@example.org");
    assertThat(summary.configured()).isFalse();
    assertThat(summary.credentialsPresent()).isFalse();
    verify(service, times(1)).getGlobalSmtpSettingsSnapshot();
  }

  @Test
  void malformedSavedPortIsOmittedWhileOtherSafeFieldsRemainVisible() {
    var saved =
        PlatformSmtpSettingsFixture.credentials("sender", "secret").globalSmtpPort("not-a-port");
    when(service.getGlobalSmtpSettingsSnapshot()).thenReturn(Optional.of(saved));

    var summary = provider.summary();

    assertThat(summary.host()).isEqualTo("smtp.example.org");
    assertThat(summary.port()).isNull();
    assertThat(summary.configured()).isFalse();
    assertThat(summary.credentialsPresent()).isTrue();
  }

  @Test
  void missingSavedSettingsGiveAnEmptyButAvailableSummary() {
    when(service.getGlobalSmtpSettingsSnapshot()).thenReturn(Optional.empty());

    assertThat(provider.summary())
        .isEqualTo(new PlatformSmtpSettingsProvider.Summary(null, null, null, null, false, false));
  }

  @Test
  void unavailableAdminSettingsDoNotMasqueradeAsMissingConfiguration() {
    when(service.getGlobalSmtpSettingsSnapshot()).thenThrow(new SmtpSettingsUnavailableException());

    assertThatThrownBy(provider::summary).isInstanceOf(SmtpSettingsUnavailableException.class);
  }
}
