package de.caritas.cob.userservice.api.config;

import com.google.common.net.InternetDomainName;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validates that all required configuration values are provided via ConfigMaps/Secrets. Throws an
 * exception on startup if any required configuration is missing.
 */
@Component
public class ConfigurationValidator {

  @Value("${spring.datasource.url:}")
  private String datasourceUrl;

  @Value("${spring.datasource.username:}")
  private String datasourceUsername;

  @Value("${spring.datasource.password:}")
  private String datasourcePassword;

  @Value("${keycloak.auth-server-url:}")
  private String keycloakAuthServerUrl;

  @Value("${keycloak.realm:}")
  private String keycloakRealm;

  @Value("${identity.openid-connect-url:}")
  private String identityOpenIdConnectUrl;

  @Value("${identity.technical-user.client-id:}")
  private String identityTechnicalClientId;

  @Value("${identity.technical-user.client-secret:}")
  private String identityTechnicalClientSecret;

  @Value("${consulting.type.service.api.url:}")
  private String consultingTypeServiceApiUrl;

  @Value("${consulting.type.service.scoped.api.url:}")
  private String scopedTopicServiceApiUrl;

  @Value("${tenant.service.api.url:}")
  private String tenantServiceApiUrl;

  /**
   * Both agency URLs used to fall back to this service's own base URL when the environment variable
   * was absent. The service then called itself, and the misconfiguration only surfaced as a runtime
   * 500 on every consultant-agency read (epic #351, workstream 3). They are required now, so a
   * deployment that lacks them fails at startup instead — during a rolling update that leaves the
   * previous, working replica set in place.
   */
  @Value("${agency.service.api.url:}")
  private String agencyServiceApiUrl;

  @Value("${agency.admin.service.api.url:}")
  private String agencyAdminServiceApiUrl;

  @Value("${matrix.apiUrl:}")
  private String matrixApiUrl;

  @Value("${matrix.registrationSharedSecret:}")
  private String matrixRegistrationSharedSecret;

  @PostConstruct
  public void validateConfiguration() {
    List<String> missingConfigs = new ArrayList<>();

    if (isEmpty(datasourceUrl)) {
      missingConfigs.add("spring.datasource.url (SPRING_DATASOURCE_URL)");
    }
    if (isEmpty(datasourceUsername)) {
      missingConfigs.add("spring.datasource.username (SPRING_DATASOURCE_USERNAME)");
    }
    if (isEmpty(datasourcePassword)) {
      missingConfigs.add("spring.datasource.password (SPRING_DATASOURCE_PASSWORD)");
    }
    if (isEmpty(keycloakAuthServerUrl)) {
      missingConfigs.add("keycloak.auth-server-url (KEYCLOAK_AUTH_SERVER_URL)");
    }
    if (isEmpty(keycloakRealm)) {
      missingConfigs.add("keycloak.realm (KEYCLOAK_REALM)");
    }
    if (isEmpty(identityOpenIdConnectUrl)) {
      missingConfigs.add("identity.openid-connect-url (IDENTITY_OPENID_CONNECT_URL)");
    }
    if (isEmpty(identityTechnicalClientId)) {
      missingConfigs.add("identity.technical-user.client-id (IDENTITY_TECHNICAL_CLIENT_ID)");
    }
    if (isEmpty(identityTechnicalClientSecret)) {
      missingConfigs.add(
          "identity.technical-user.client-secret (KEYCLOAK_BACKEND_TECHNICAL_CLIENT_SECRET)");
    }
    if (isEmpty(consultingTypeServiceApiUrl)) {
      missingConfigs.add("consulting.type.service.api.url (CONSULTING_TYPE_SERVICE_API_URL)");
    }
    if (isEmpty(tenantServiceApiUrl)) {
      missingConfigs.add("tenant.service.api.url (TENANT_SERVICE_API_URL)");
    }
    if (isEmpty(agencyServiceApiUrl)) {
      missingConfigs.add("agency.service.api.url (AGENCY_SERVICE_API_URL)");
    }
    if (isEmpty(agencyAdminServiceApiUrl)) {
      missingConfigs.add("agency.admin.service.api.url (AGENCY_ADMIN_SERVICE_API_URL)");
    }
    if (isEmpty(matrixApiUrl)) {
      missingConfigs.add("matrix.apiUrl (MATRIX_API_URL)");
    }
    if (isEmpty(matrixRegistrationSharedSecret)) {
      missingConfigs.add("matrix.registrationSharedSecret (MATRIX_REGISTRATION_SHARED_SECRET)");
    }

    if (!missingConfigs.isEmpty()) {
      String errorMessage =
          String.format(
              "CRITICAL: Missing required configuration values. Please provide the following via ConfigMap/Secrets:\n%s\n\n"
                  + "IMPORTANT: Use Kubernetes DNS names (e.g., mariadb.caritas.svc.cluster.local:3306) NOT hardcoded IPs.\n"
                  + "DNS names ensure services can find resources even when pods are rescheduled or scaled.",
              String.join(
                  "\n",
                  missingConfigs.stream()
                      .map(config -> "  - config '" + config + "' is missing")
                      .toArray(String[]::new)));
      throw new IllegalStateException(errorMessage);
    }

    if (scopedTopicServiceApiUrl != null
        && !scopedTopicServiceApiUrl.isBlank()
        && !isValidScopedTopicServiceUrl(scopedTopicServiceApiUrl)) {
      throw new IllegalStateException(
          "consulting.type.service.scoped.api.url (CONSULTING_TYPE_SERVICE_SCOPED_API_URL)"
              + " must be an absolute HTTP(S) URL with a short hostname, a valid port if supplied,"
              + " and no user info, query or fragment");
    }
  }

  private static boolean isValidScopedTopicServiceUrl(String value) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException malformed) {
      return false;
    }

    String host = uri.getHost();
    int port = uri.getPort();
    // ConsultingTypeService treats a dotted host as the authoritative tenant subdomain.
    return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        && host != null
        && !host.contains(".")
        && InternetDomainName.isValid(host)
        && !uri.getRawAuthority().endsWith(":")
        && (port == -1 || (port > 0 && port <= 65535))
        && uri.getRawUserInfo() == null
        && uri.getRawQuery() == null
        && uri.getRawFragment() == null;
  }

  private boolean isEmpty(String value) {
    return value == null || value.trim().isEmpty();
  }
}
