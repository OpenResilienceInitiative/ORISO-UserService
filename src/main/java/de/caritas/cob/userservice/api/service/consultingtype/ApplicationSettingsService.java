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
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Service class to communicate with the ConsultingTypeService. */
@Slf4j
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
        // #1006: log the configuration state, never the credential values themselves.
        log.warn(
            "Global SMTP credentials lookup at ConsultingTypeService returned no usable"
                + " credentials (username or password missing/blank)");
        return Optional.empty();
      }
      return Optional.of(credentials);
    } catch (RestClientException ex) {
      // #1006: this used to be swallowed silently, making "invite mail not sent"
      // undiagnosable. The lookup stays best-effort, but status and cause must reach the log.
      // A 403 here typically means the current request's token lacks the platform-admin role
      // required by the guarded credentials endpoint.
      String status =
          ex instanceof RestClientResponseException responseException
              ? String.valueOf(responseException.getStatusCode())
              : "no response";
      log.warn(
          "Global SMTP credentials lookup at ConsultingTypeService failed ({}, status: {}): {}",
          ex.getClass().getSimpleName(),
          status,
          ex.getMessage());
      return Optional.empty();
    }
  }

  private void addDefaultHeaders(ApiClient apiClient) {
    var headers = this.securityHeaderSupplier.getCsrfHttpHeaders();
    tenantHeaderSupplier.addTenantHeader(headers);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
