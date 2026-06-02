package de.caritas.cob.userservice.api.service.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantAdminService;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemNotificationEmailSettingsService {

  private static final String KEY_SETTINGS = "settings";
  private static final String KEY_SMTP = "smtp";
  private static final String KEY_SYSTEM_EMAIL_TOGGLE = "featureSystemNotificationEmailsEnabled";
  private static final String KEY_SMTP_ENABLED = "enabled";
  private static final String KEY_SMTP_HOST = "host";
  private static final String KEY_SMTP_PORT = "port";
  private static final String KEY_SMTP_USERNAME = "username";
  private static final String KEY_SMTP_PASSWORD = "password";
  private static final String KEY_SMTP_FROM = "from";
  private static final String KEY_SMTP_EMAIL_THEME_COLOR = "emailThemeColor";
  private static final String TENANT_PUBLIC_BY_ID_PATH = "/tenant/public/id/%d";

  private final @NonNull TenantAdminService tenantAdminService;
  private final @NonNull ObjectMapper objectMapper;
  private final @NonNull RestTemplate restTemplate;

  @Value("${tenant.service.api.url}")
  private String tenantServiceApiUrl;

  public boolean isSupervisorAddedEmailEnabled(Long tenantId) {
    return resolveSupervisorAddedEmailSettings(tenantId, null).isPresent();
  }

  public boolean isSupervisorAddedEmailEnabled(Long tenantId, String accessToken) {
    return resolveSupervisorAddedEmailSettings(tenantId, accessToken).isPresent();
  }

  public Optional<SupervisorAddedEmailSettings> resolveSupervisorAddedEmailSettings(
      Long tenantId, String accessToken) {
    if (tenantId == null) {
      return Optional.empty();
    }
    try {
      // Prefer authenticated tenant-admin payload first; public payload may omit/mask SMTP secrets.
      Optional<Map<String, Object>> tenantAsMapFromAdmin =
          getTenantAsMapFromTenantAdminEndpoint(tenantId, accessToken);
      if (tenantAsMapFromAdmin.isPresent()) {
        Optional<SupervisorAddedEmailSettings> settingsFromAdmin =
            toSupervisorAddedEmailSettings(tenantAsMapFromAdmin.get(), tenantId);
        if (settingsFromAdmin.isPresent()) {
          return settingsFromAdmin;
        }
      }

      Optional<Map<String, Object>> tenantAsMapFromPublic =
          getTenantAsMapFromPublicTenantEndpoint(tenantId);
      if (tenantAsMapFromPublic.isPresent()) {
        Optional<SupervisorAddedEmailSettings> settingsFromPublic =
            toSupervisorAddedEmailSettings(tenantAsMapFromPublic.get(), tenantId);
        if (settingsFromPublic.isPresent()) {
          return settingsFromPublic;
        }
      }

      log.info(
          "Supervisor-added email disabled for tenant {} because SMTP settings are incomplete.",
          tenantId);
      return Optional.empty();
    } catch (Exception ex) {
      log.warn(
          "Could not resolve tenant SMTP/system-email settings for tenant {}. Falling back to disabled.",
          tenantId,
          ex);
      return Optional.empty();
    }
  }

  private Optional<SupervisorAddedEmailSettings> toSupervisorAddedEmailSettings(
      Map<String, Object> tenantAsMap, Long tenantId) {
      Object settingsObj = tenantAsMap.get(KEY_SETTINGS);
      if (!(settingsObj instanceof Map<?, ?>)) {
        return Optional.empty();
      }
      Map<?, ?> settings = (Map<?, ?>) settingsObj;

      boolean systemEmailEnabled = asBoolean(settings.get(KEY_SYSTEM_EMAIL_TOGGLE));
      Object smtpObj = settings.get(KEY_SMTP);
      if (!(smtpObj instanceof Map<?, ?>)) {
        return Optional.empty();
      }

      Map<?, ?> smtpMap = (Map<?, ?>) smtpObj;
      boolean smtpEnabled = asBoolean(smtpMap.get(KEY_SMTP_ENABLED));
      boolean smtpConfigValid = hasValidSmtpConfiguration(smtpMap);
      if (!(systemEmailEnabled && smtpEnabled && smtpConfigValid)) {
        if (systemEmailEnabled && smtpEnabled && !smtpConfigValid) {
          log.warn(
              "SMTP is enabled for tenant {} but configuration is incomplete. Required keys: host, port, username, password, from.",
              tenantId);
        }
        return Optional.empty();
      }

      String host = asTrimmedString(smtpMap.get(KEY_SMTP_HOST));
      Integer port = asInteger(smtpMap.get(KEY_SMTP_PORT));
      boolean secure = asBoolean(smtpMap.get("secure"));
      String username = asTrimmedString(smtpMap.get(KEY_SMTP_USERNAME));
      String password = asTrimmedString(smtpMap.get(KEY_SMTP_PASSWORD));
      String from = asTrimmedString(smtpMap.get(KEY_SMTP_FROM));
      String emailThemeColor = asTrimmedString(smtpMap.get(KEY_SMTP_EMAIL_THEME_COLOR));

      return Optional.of(
        new SupervisorAddedEmailSettings(
            host, port, secure, username, password, from, emailThemeColor));
  }

  private Optional<Map<String, Object>> getTenantAsMapFromTenantAdminEndpoint(
      Long tenantId, String accessToken) {
    try {
      Object tenantDto = tenantAdminService.getTenantById(tenantId);
      Map<String, Object> tenantAsMap =
          objectMapper.convertValue(tenantDto, new TypeReference<Map<String, Object>>() {});
      return tenantAsMap == null || tenantAsMap.isEmpty() ? Optional.empty() : Optional.of(tenantAsMap);
    } catch (Exception ex) {
      return Optional.empty();
    }
  }

  private Optional<Map<String, Object>> getTenantAsMapFromPublicTenantEndpoint(Long tenantId) {
    try {
      String url = tenantServiceApiUrl + String.format(TENANT_PUBLIC_BY_ID_PATH, tenantId);
      @SuppressWarnings("unchecked")
      Map<String, Object> response = restTemplate.getForObject(url, Map.class);
      return response == null || response.isEmpty() ? Optional.empty() : Optional.of(response);
    } catch (Exception ex) {
      return Optional.empty();
    }
  }

  private boolean asBoolean(Object value) {
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value instanceof String) {
      return "true".equalsIgnoreCase((String) value);
    }
    return false;
  }

  private boolean hasValidSmtpConfiguration(Map<?, ?> smtpMap) {
    String host = asTrimmedString(smtpMap.get(KEY_SMTP_HOST));
    String from = asTrimmedString(smtpMap.get(KEY_SMTP_FROM));
    String username = asTrimmedString(smtpMap.get(KEY_SMTP_USERNAME));
    String password = asTrimmedString(smtpMap.get(KEY_SMTP_PASSWORD));
    Integer port = asInteger(smtpMap.get(KEY_SMTP_PORT));

    return StringUtils.isNotBlank(host)
        && StringUtils.isNotBlank(from)
        && StringUtils.isNotBlank(username)
        && StringUtils.isNotBlank(password)
        && port != null
        && port > 0;
  }

  private String asTrimmedString(Object value) {
    if (value == null) {
      return null;
    }
    return String.valueOf(value).trim();
  }

  private Integer asInteger(Object value) {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    if (value instanceof String) {
      try {
        return Integer.parseInt((String) value);
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }

  @lombok.Value
  public static class SupervisorAddedEmailSettings {
    String host;
    Integer port;
    boolean secure;
    String username;
    String password;
    String from;
    String emailThemeColor;
  }
}
