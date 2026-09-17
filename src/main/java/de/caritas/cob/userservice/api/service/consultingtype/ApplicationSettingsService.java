package de.caritas.cob.userservice.api.service.consultingtype;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.config.CacheManagerConfig;
import de.caritas.cob.userservice.api.config.apiclient.ApplicationSettingsApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
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
  private final @NonNull IdentityClientConfig identityClientConfig;
  private final @NonNull IdentityAuthentication identityAuthentication;

  @Cacheable(value = CacheManagerConfig.APPLICATION_SETTINGS_CACHE)
  public ApplicationSettingsDTO getApplicationSettings() {
    ApplicationsettingsControllerApi controllerApi =
        applicationSettingsApiControllerFactory.createControllerApi();
    addDefaultHeaders(controllerApi.getApiClient());
    return controllerApi.getApplicationSettings();
  }

  /**
   * Reads the global SMTP credentials from the ConsultingTypeService.
   *
   * <p>#1160: the guarded credentials endpoint is platform-scoped, so the lookup must never depend
   * on <em>who</em> triggered the mail. It is therefore performed with the technical service
   * identity, not with the caller's token — a tenant admin and a platform admin get the same
   * outcome, and the unauthenticated flows (password reset, magic link) can use it at all. There is
   * deliberately no fallback to the caller's token: a role-dependent result is the bug.
   */
  public Optional<ApplicationSettingsSmtpCredentialsDTO> getGlobalSmtpCredentials() {
    Optional<String> technicalAccessToken = loginTechnicalUser();
    if (technicalAccessToken.isEmpty()) {
      return Optional.empty();
    }
    try {
      ApplicationsettingsControllerApi controllerApi =
          applicationSettingsApiControllerFactory.createControllerApi();
      HttpHeaders headers =
          this.securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(technicalAccessToken.get());
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
      // #1160: a 403 here now means the TECHNICAL identity is not accepted by the guarded
      // credentials endpoint (missing tenantId=0 claim / service authority), not that the
      // clicking admin lacked a role.
      String status =
          ex instanceof RestClientResponseException responseException
              ? String.valueOf(responseException.getStatusCode())
              : "no response";
      // Review 3893332413: attach the exception itself so root cause (TLS vs DNS vs
      // connection) and stack trace reach the log — context fields stay secret-free.
      log.warn(
          "Global SMTP credentials lookup at ConsultingTypeService failed ({}, status: {})",
          ex.getClass().getSimpleName(),
          status,
          ex);
      return Optional.empty();
    }
  }

  /**
   * #1160: obtains a fresh technical-user access token for the platform-scoped credentials call.
   * Returns empty (with a WARN naming the configuration state, never a secret) when the technical
   * user is unconfigured or the login fails.
   */
  private Optional<String> loginTechnicalUser() {
    TechnicalUserConfig technicalUser = identityClientConfig.getTechnicalUser();
    if (technicalUser == null
        || isBlank(technicalUser.getUsername())
        || isBlank(technicalUser.getPassword())) {
      log.warn(
          "Global SMTP credentials lookup skipped: no technical user configured"
              + " (identity.technical-user.username / .password)");
      return Optional.empty();
    }
    try {
      var login =
          identityAuthentication.login(technicalUser.getUsername(), technicalUser.getPassword());
      if (login == null || isBlank(login.accessToken())) {
        log.warn("Global SMTP credentials lookup skipped: technical user login returned no token");
        return Optional.empty();
      }
      return Optional.of(login.accessToken());
    } catch (RuntimeException ex) {
      log.warn(
          "Global SMTP credentials lookup skipped: technical user login failed ({})",
          ex.getClass().getSimpleName(),
          ex);
      return Optional.empty();
    }
  }

  private void addDefaultHeaders(ApiClient apiClient) {
    var headers = this.securityHeaderSupplier.getCsrfHttpHeaders();
    tenantHeaderSupplier.addTenantHeader(headers);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
