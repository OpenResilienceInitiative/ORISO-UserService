package de.caritas.cob.userservice.api.service.notification;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class DefaultDpaSigningEmailDispatchService implements DpaSigningEmailDispatchService {

  private final RestTemplate restTemplate;
  private final SecurityHeaderSupplier securityHeaderSupplier;
  private final TenantHeaderSupplier tenantHeaderSupplier;
  private final String consultingTypeServiceApiUrl;

  public DefaultDpaSigningEmailDispatchService(
      @NonNull RestTemplate restTemplate,
      @NonNull SecurityHeaderSupplier securityHeaderSupplier,
      @NonNull TenantHeaderSupplier tenantHeaderSupplier,
      @Value("${consulting.type.service.api.url:}") String consultingTypeServiceApiUrl) {
    this.restTemplate = restTemplate;
    this.securityHeaderSupplier = securityHeaderSupplier;
    this.tenantHeaderSupplier = tenantHeaderSupplier;
    this.consultingTypeServiceApiUrl = consultingTypeServiceApiUrl;
  }

  @Override
  public void send(
      String recipientEmail, String tenantName, String signLink, LocalDateTime expiresAt) {
    if (isBlank(consultingTypeServiceApiUrl)) {
      throw new IllegalStateException("DPA email dispatch endpoint is not configured");
    }
    var headers = securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders();
    tenantHeaderSupplier.addTenantHeader(headers);
    Map<String, Object> payload =
        Map.of(
            "recipientEmail", recipientEmail,
            "tenantName", tenantName,
            "signLink", signLink,
            "expiresAt", expiresAt.toString());
    restTemplate.exchange(
        normalizeBaseUrl(consultingTypeServiceApiUrl) + "/settingsadmin/dpa-signing-emails",
        HttpMethod.POST,
        new HttpEntity<>(payload, headers),
        Void.class);
  }

  private static String normalizeBaseUrl(String value) {
    String trimmed = value.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
