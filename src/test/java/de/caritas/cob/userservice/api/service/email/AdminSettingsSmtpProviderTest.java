package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsSmtpCredentialsDTO;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class AdminSettingsSmtpProviderTest {

  @Mock private RestTemplate restTemplate;
  @Mock private ApplicationSettingsService applicationSettingsService;

  @Test
  void resolvesSmtpFromAdminSettingsAndTechnicalCredentialLookup() {
    when(restTemplate.getForObject(anyString(), org.mockito.ArgumentMatchers.eq(Map.class)))
        .thenReturn(enabledSettings());
    when(applicationSettingsService.getGlobalSmtpCredentials())
        .thenReturn(Optional.of(credentials("smtp-user", "smtp-password")));

    var settings = provider().requireConfigured();

    assertThat(settings)
        .isEqualTo(
            new AdminSettingsSmtpProvider.Settings(
                "smtp.example.org",
                587,
                false,
                "smtp-user",
                "smtp-password",
                "noreply@example.org"));
    verify(applicationSettingsService).getGlobalSmtpCredentials();
  }

  @Test
  void rejectsDisabledAdminSettingsWithoutLookingUpCredentials() {
    when(restTemplate.getForObject(anyString(), org.mockito.ArgumentMatchers.eq(Map.class)))
        .thenReturn(Map.of("globalFeatureSystemNotificationEmailsEnabled", false));

    assertThatThrownBy(() -> provider().requireConfigured())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Admin Settings")
        .hasMessageContaining("disabled");
  }

  @Test
  void rejectsMissingTechnicalCredentialLookup() {
    when(restTemplate.getForObject(anyString(), org.mockito.ArgumentMatchers.eq(Map.class)))
        .thenReturn(enabledSettings());
    when(applicationSettingsService.getGlobalSmtpCredentials()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> provider().requireConfigured())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("credentials are unavailable");
  }

  private AdminSettingsSmtpProvider provider() {
    return new AdminSettingsSmtpProvider(
        restTemplate, applicationSettingsService, "http://consulting-type-service:8080/");
  }

  private Map<String, Object> enabledSettings() {
    return Map.of(
        "globalFeatureSystemNotificationEmailsEnabled",
        true,
        "globalSmtpEnabled",
        true,
        "globalSmtpHost",
        "smtp.example.org",
        "globalSmtpPort",
        "587",
        "globalSmtpSecure",
        false,
        "globalSmtpFrom",
        "noreply@example.org");
  }

  private ApplicationSettingsSmtpCredentialsDTO credentials(String username, String password) {
    return new ApplicationSettingsSmtpCredentialsDTO()
        .globalSmtpUsername(username)
        .globalSmtpPassword(password);
  }
}
