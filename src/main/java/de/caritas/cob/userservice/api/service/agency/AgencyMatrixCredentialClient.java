package de.caritas.cob.userservice.api.service.agency;

import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.api.service.identity.TechnicalIdentityTokenProvider;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgencyMatrixCredentialClient {

  private final @NonNull RestTemplate restTemplate;
  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull TenantHeaderSupplier tenantHeaderSupplier;
  private final @NonNull TechnicalIdentityTokenProvider tokenProvider;
  private final @NonNull OutboundHttpMetrics outboundHttpMetrics;

  @Value("${agency.service.api.url}")
  private String agencyServiceBaseUrl;

  public Optional<AgencyMatrixCredentialsDTO> fetchMatrixCredentials(Long agencyId) {
    if (agencyId == null) {
      return Optional.empty();
    }

    String url =
        String.format(
            "%s/internal/agencies/%d/matrix-service-account", agencyServiceBaseUrl, agencyId);

    String accessToken = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        accessToken = tokenProvider.getAccessToken();
        HttpHeaders headers = technicalUserHeaders(accessToken);
        // In single-domain multitenancy, non-auth internal calls can miss tenant context.
        // Passing agencyId lets AgencyService resolve the tenant from the target agency.
        headers.add("agencyId", String.valueOf(agencyId));

        ResponseEntity<AgencyMatrixCredentialsDTO> response =
            restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), AgencyMatrixCredentialsDTO.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
          return Optional.of(response.getBody());
        }

      } catch (HttpClientErrorException.Unauthorized ex) {
        if (attempt == 0) {
          tokenProvider.invalidate(accessToken);
          outboundHttpMetrics.recordRetry("agency-service", "matrix-credentials-auth-refresh");
          continue;
        }
        log.error("AgencyService rejected refreshed technical identity for agency {}", agencyId);
      } catch (HttpClientErrorException.NotFound ex) {
        log.warn("Agency {} has no Matrix credentials configured", agencyId);
      } catch (Exception ex) {
        log.error(
            "Failed to fetch Matrix credentials for agency {}: {}", agencyId, ex.getMessage());
      }
      return Optional.empty();
    }

    return Optional.empty();
  }

  private HttpHeaders technicalUserHeaders(String accessToken) {
    var headers = securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(accessToken);
    tenantHeaderSupplier.addTenantHeader(headers);
    return headers;
  }
}
