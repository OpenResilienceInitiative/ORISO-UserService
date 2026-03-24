package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantAdminService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class SystemNotificationEmailSettingsServiceTest {

  @Mock private TenantAdminService tenantAdminService;
  @Mock private RestTemplate restTemplate;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void isSupervisorAddedEmailEnabledShouldReturnTrueWhenToggleAndValidSmtpAreEnabled() {
    var service =
        new SystemNotificationEmailSettingsService(tenantAdminService, objectMapper, restTemplate);
    when(tenantAdminService.getTenantById(1L)).thenReturn(validTenantSettings());

    boolean enabled = service.isSupervisorAddedEmailEnabled(1L);

    assertThat(enabled).isTrue();
  }

  @Test
  void isSupervisorAddedEmailEnabledShouldReturnFalseWhenSmtpConfigIsIncomplete() {
    var service =
        new SystemNotificationEmailSettingsService(tenantAdminService, objectMapper, restTemplate);
    when(tenantAdminService.getTenantById(1L)).thenReturn(invalidTenantSettingsMissingHost());

    boolean enabled = service.isSupervisorAddedEmailEnabled(1L);

    assertThat(enabled).isFalse();
  }

  @Test
  void isSupervisorAddedEmailEnabledShouldReturnFalseWhenSystemEmailToggleIsDisabled() {
    var service =
        new SystemNotificationEmailSettingsService(tenantAdminService, objectMapper, restTemplate);
    when(tenantAdminService.getTenantById(1L)).thenReturn(systemEmailDisabledTenantSettings());

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
}

