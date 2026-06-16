package de.caritas.cob.userservice.api.service.consultingtype;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.config.CacheManagerConfig;
import de.caritas.cob.userservice.api.config.apiclient.ApplicationSettingsApiControllerFactory;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.applicationsettingsservice.generated.ApiClient;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.ApplicationsettingsControllerApi;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsDTO;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsSmtpCredentialsDTO;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/** Service class to communicate with the ConsultingTypeService. */
@Component
@RequiredArgsConstructor
public class ApplicationSettingsService {

  private final @NonNull ApplicationSettingsApiControllerFactory
      applicationSettingsApiControllerFactory;
  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull TenantHeaderSupplier tenantHeaderSupplier;

  @Cacheable(value = CacheManagerConfig.APPLICATION_SETTINGS_CACHE)
  public ApplicationSettingsDTO getApplicationSettings() {
    ApplicationsettingsControllerApi controllerApi =
        applicationSettingsApiControllerFactory.createControllerApi();
    addDefaultHeaders(controllerApi.getApiClient());
    return controllerApi.getApplicationSettings();
  }

  public Optional<ApplicationSettingsSmtpCredentialsDTO> getGlobalSmtpCredentials() {
    try {
      ApplicationsettingsControllerApi controllerApi =
          applicationSettingsApiControllerFactory.createControllerApi();
      HttpHeaders headers = this.securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders();
      tenantHeaderSupplier.addTenantHeader(headers);
      headers.forEach(
          (key, value) ->
              controllerApi.getApiClient().addDefaultHeader(key, value.iterator().next()));
      ApplicationSettingsSmtpCredentialsDTO credentials = controllerApi.getGlobalSmtpCredentials();
      if (credentials == null
          || isBlank(credentials.getGlobalSmtpUsername())
          || isBlank(credentials.getGlobalSmtpPassword())) {
        return Optional.empty();
      }
      return Optional.of(credentials);
    } catch (RestClientException ex) {
      return Optional.empty();
    }
  }

  private void addDefaultHeaders(ApiClient apiClient) {
    var headers = this.securityHeaderSupplier.getCsrfHttpHeaders();
    tenantHeaderSupplier.addTenantHeader(headers);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
