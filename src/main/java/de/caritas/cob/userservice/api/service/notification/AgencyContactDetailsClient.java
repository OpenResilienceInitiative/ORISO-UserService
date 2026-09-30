package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/** Reads maintained contact fields through AgencyService's technical, tenant-scoped endpoint. */
@Component
public class AgencyContactDetailsClient {
  public record ContactDetails(
      Long agencyId, Long tenantId, String name, String phone, String email, String openingHours) {}

  private final RestTemplate http;
  private final IdentityAuthentication authentication;
  private final IdentityClientConfig identityConfig;
  private final SecurityHeaderSupplier headers;

  @Value("${agency.service.api.url:}")
  private String agencyServiceUrl;

  public AgencyContactDetailsClient(
      RestTemplate http,
      IdentityAuthentication authentication,
      IdentityClientConfig identityConfig,
      @Qualifier("securityHeaderSupplier") SecurityHeaderSupplier headers) {
    this.http = http;
    this.authentication = authentication;
    this.identityConfig = identityConfig;
    this.headers = headers;
  }

  public ContactDetails read(long agencyId, long tenantId) {
    if (agencyId <= 0 || tenantId <= 0) {
      throw new IllegalArgumentException("agency and tenant IDs must be positive");
    }
    String baseUrl = requireBaseUrl();
    var account = identityConfig.getTechnicalUser();
    if (account == null || account.getUsername() == null || account.getPassword() == null) {
      throw new IllegalStateException("Identity technical account is not configured");
    }
    String token = authentication.login(account.getUsername(), account.getPassword()).accessToken();
    String url =
        baseUrl.replaceAll("/+$", "")
            + "/internal/agencies/"
            + agencyId
            + "/contact-details?tenantId="
            + tenantId;
    ContactDetails details;
    try {
      details =
          http.exchange(
                  url,
                  HttpMethod.GET,
                  new HttpEntity<Void>(headers.getKeycloakAndCsrfHttpHeaders(token)),
                  ContactDetails.class)
              .getBody();
    } catch (HttpClientErrorException.NotFound missing) {
      throw new IllegalStateException("Agency contact details are unavailable", missing);
    }
    if (details == null
        || !Objects.equals(details.agencyId(), agencyId)
        || !Objects.equals(details.tenantId(), tenantId)) {
      throw new IllegalStateException("Agency contact details do not match the session");
    }
    return details;
  }

  @PostConstruct
  void validateAgencyServiceUrl() {
    requireBaseUrl();
  }

  private String requireBaseUrl() {
    String value = agencyServiceUrl == null ? "" : agencyServiceUrl.trim();
    URI base;
    try {
      base = URI.create(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "AGENCY_SERVICE_API_URL is required and must be valid", invalid);
    }
    if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null) {
      throw new IllegalStateException("AGENCY_SERVICE_API_URL is required and must be valid");
    }
    return value;
  }
}
