package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantAdminService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemNotificationEmailSettingsServiceTest {

  @Mock private TenantAdminService tenantAdminService;
  @Mock private RestTemplate restTemplate;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void isSupervisorAddedEmailEnabledShouldReturnTrueWhenToggleAndValidSmtpAreEnabled() {
    var service =
        new SystemNotificationEmailSettingsService(tenantAdminService, objectMapper, restTemplate);
    when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(validTenantSettings());

    boolean enabled = service.isSupervisorAddedEmailEnabled(1L);

    assertThat(enabled).isTrue();
  }

  @Test
  void isSupervisorAddedEmailEnabledShouldReturnFalseWhenSmtpConfigIsIncomplete() {
    var service =
        new SystemNotificationEmailSettingsService(tenantAdminService, objectMapper, restTemplate);
    when(restTemplate.getForObject(anyString(), eq(Map.class)))
        .thenReturn(invalidTenantSettingsMissingHost());

    boolean enabled = service.isSupervisorAddedEmailEnabled(1L);

    assertThat(enabled).isFalse();
  }

  @Test
  void isSupervisorAddedEmailEnabledShouldReturnFalseWhenSystemEmailToggleIsDisabled() {
    var service =
        new SystemNotificationEmailSettingsService(tenantAdminService, objectMapper, restTemplate);
    when(restTemplate.getForObject(anyString(), eq(Map.class)))
        .thenReturn(systemEmailDisabledTenantSettings());

    boolean enabled = service.isSupervisorAddedEmailEnabled(1L);

    assertThat(enabled).isFalse();
  }

  private Map<String, Object> validTenantSettings() {
    return Map.of(
        "settings",
        Map.of(
            "featureSystemNotificationEmailsEnabled",
            true,
            "smtp",
            Map.of(
                "enabled", true,
                "host", "smtp.example.org",
                "port", 587,
                "username", "user",
                "password", "password",
                "from", "noreply@example.org",
                "secure", true)));
  }

  private Map<String, Object> invalidTenantSettingsMissingHost() {
    return Map.of(
        "settings",
        Map.of(
            "featureSystemNotificationEmailsEnabled",
            true,
            "smtp",
            Map.of(
                "enabled", true,
                "host", "",
                "port", 587,
                "username", "user",
                "password", "password",
                "from", "noreply@example.org",
                "secure", true)));
  }

  private Map<String, Object> systemEmailDisabledTenantSettings() {
    return Map.of(
        "settings",
        Map.of(
            "featureSystemNotificationEmailsEnabled",
            false,
            "smtp",
            Map.of(
                "enabled", true,
                "host", "smtp.example.org",
                "port", 587,
                "username", "user",
                "password", "password",
                "from", "noreply@example.org",
                "secure", true)));
  }

  @Test
  void resolveSupervisorAddedEmailSettings_Should_ReturnEmpty_When_TenantIdIsNull() {
    var service =
        new SystemNotificationEmailSettingsService(tenantAdminService, objectMapper, restTemplate);

    assertThat(service.resolveSupervisorAddedEmailSettings(null, null)).isEmpty();
  }

  @Test
  void resolveSupervisorAddedEmailSettings_Should_ReturnEmpty_When_BothEndpointsReturnNull() {
    var service =
        new SystemNotificationEmailSettingsService(tenantAdminService, objectMapper, restTemplate);
    when(tenantAdminService.getTenantById(1L)).thenReturn(null);
    when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(null);

    assertThat(service.resolveSupervisorAddedEmailSettings(1L, null)).isEmpty();
  }

  @Test
  void resolveSupervisorAddedEmailSettings_Should_FallbackToPublicEndpoint_When_AdminReturnsNull() {
    var service =
        new SystemNotificationEmailSettingsService(tenantAdminService, objectMapper, restTemplate);
    when(tenantAdminService.getTenantById(1L)).thenReturn(null);
    when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(validTenantSettings());

    assertThat(service.resolveSupervisorAddedEmailSettings(1L, null)).isPresent();
  }

  @Test
  void
      resolveSupervisorAddedEmailSettings_Should_ReturnSettings_When_PublicEndpointHasValidConfig() {
    var service =
        new SystemNotificationEmailSettingsService(tenantAdminService, objectMapper, restTemplate);
    when(tenantAdminService.getTenantById(2L)).thenReturn(null);
    when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(validTenantSettings());

    var result = service.resolveSupervisorAddedEmailSettings(2L, "token");

    assertThat(result).isPresent();
    assertThat(result.get().getHost()).isEqualTo("smtp.example.org");
    assertThat(result.get().getPort()).isEqualTo(587);
    assertThat(result.get().isSecure()).isTrue();
  }

  @Test
  void resolveSupervisorAddedEmailSettings_Should_ReturnEmpty_When_SmtpDisabled() {
    var service =
        new SystemNotificationEmailSettingsService(tenantAdminService, objectMapper, restTemplate);
    Map<String, Object> settings =
        Map.of(
            "settings",
            Map.of(
                "featureSystemNotificationEmailsEnabled",
                true,
                "smtp",
                Map.of("enabled", false, "host", "smtp.x.org")));
    when(tenantAdminService.getTenantById(3L)).thenReturn(null);
    when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(settings);

    assertThat(service.resolveSupervisorAddedEmailSettings(3L, null)).isEmpty();
  }

  @Test
  void resolveSupervisorAddedEmailSettings_Should_ReturnEmpty_When_TenantAdminThrows() {
    var service =
        new SystemNotificationEmailSettingsService(tenantAdminService, objectMapper, restTemplate);
    when(tenantAdminService.getTenantById(4L)).thenThrow(new RuntimeException("unavailable"));
    when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(null);

    assertThat(service.resolveSupervisorAddedEmailSettings(4L, null)).isEmpty();
  }

  @Test
  void isSupervisorAddedEmailEnabledWithToken_Should_ReturnTrue_When_ValidConfig() {
    var service =
        new SystemNotificationEmailSettingsService(tenantAdminService, objectMapper, restTemplate);
    when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(validTenantSettings());

    assertThat(service.isSupervisorAddedEmailEnabled(1L, "token")).isTrue();
  }
}
