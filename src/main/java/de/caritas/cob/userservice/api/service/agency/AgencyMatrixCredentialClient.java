package de.caritas.cob.userservice.api.service.agency;

import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
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

  @Value("${agency.service.api.url}")
  private String agencyServiceBaseUrl;

  public Optional<AgencyMatrixCredentialsDTO> fetchMatrixCredentials(Long agencyId) {
    if (agencyId == null) {
      return Optional.empty();
    }

    String url =
        String.format(
            "%s/internal/agencies/%d/matrix-service-account", agencyServiceBaseUrl, agencyId);

    HttpHeaders headers = securityHeaderSupplier.getCsrfHttpHeaders();
    tenantHeaderSupplier.addTenantHeader(headers);

    try {
      ResponseEntity<AgencyMatrixCredentialsDTO> response =
          restTemplate.exchange(
              url, HttpMethod.GET, new HttpEntity<>(headers), AgencyMatrixCredentialsDTO.class);

      if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
        return Optional.of(response.getBody());
      }

    } catch (HttpClientErrorException.NotFound ex) {
      log.warn("Agency {} has no Matrix credentials configured", agencyId);
    } catch (Exception ex) {
      log.error("Failed to fetch Matrix credentials for agency {}: {}", agencyId, ex.getMessage());
    }

    return Optional.empty();
  }
}







