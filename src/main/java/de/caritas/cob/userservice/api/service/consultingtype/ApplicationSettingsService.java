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
    try {
      Optional<ApplicationSettingsSmtpCredentialsDTO> source = getGlobalSmtpSettingsSnapshot();
      if (source.isEmpty()
          || isBlank(source.get().getGlobalSmtpUsername())
          || isBlank(source.get().getGlobalSmtpPassword())) {
        log.warn(
            "Global SMTP credentials lookup at ConsultingTypeService returned no usable"
                + " credentials (username or password missing/blank)");
        return Optional.empty();
      }
      return source;
    } catch (SmtpSettingsUnavailableException exception) {
      // Preserve the best-effort contract for existing callers. The strict snapshot below
      // retains availability separately for diagnostics and saved configuration validation.
      return Optional.empty();
    }
  }

  /**
   * Reads one uncached Admin SMTP snapshot using only the technical identity. Partial saved values
   * are retained for validation; an unavailable dependency is never represented as empty settings.
   */
  public Optional<ApplicationSettingsSmtpCredentialsDTO> getGlobalSmtpSettingsSnapshot() {
    String technicalAccessToken = loginTechnicalUser();
    try {
      ApplicationsettingsControllerApi controllerApi =
          applicationSettingsApiControllerFactory.createControllerApi();
      HttpHeaders headers =
          this.securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(technicalAccessToken);
      tenantHeaderSupplier.addTenantHeader(headers);
      headers.forEach(
          (key, value) ->
              controllerApi.getApiClient().addDefaultHeader(key, value.iterator().next()));
      return Optional.ofNullable(controllerApi.getGlobalSmtpCredentials());
    } catch (RestClientException ex) {
      String status =
          ex instanceof RestClientResponseException responseException
              ? String.valueOf(responseException.getStatusCode())
              : "no response";
      // Upstream responses and throwables can contain credentials or full addresses.
      log.warn(
          "Global SMTP credentials lookup at ConsultingTypeService failed ({}, status: {})",
          ex.getClass().getSimpleName(),
          status);
      throw new SmtpSettingsUnavailableException();
    }
  }

  /** A fixed, credential-safe dependency failure; the upstream throwable is deliberately absent. */
  public static class SmtpSettingsUnavailableException extends IllegalStateException {
    public SmtpSettingsUnavailableException() {
      super(
          "Platform SMTP Admin Settings are unavailable. Please retry or contact a platform admin.");
    }
  }

  private String loginTechnicalUser() {
    TechnicalUserConfig technicalUser = identityClientConfig.getTechnicalUser();
    if (technicalUser == null
        || isBlank(technicalUser.getUsername())
        || isBlank(technicalUser.getPassword())) {
      log.warn(
          "Global SMTP credentials lookup skipped: no technical user configured"
              + " (identity.technical-user.username / .password)");
      throw new SmtpSettingsUnavailableException();
    }
    de.caritas.cob.userservice.api.port.out.IdentityLogin login;
    try {
      login =
          identityAuthentication.login(technicalUser.getUsername(), technicalUser.getPassword());
    } catch (RuntimeException ex) {
      log.warn(
          "Global SMTP credentials lookup skipped: technical user login failed ({})",
          ex.getClass().getSimpleName());
      throw new SmtpSettingsUnavailableException();
    }
    if (login == null || isBlank(login.accessToken())) {
      log.warn("Global SMTP credentials lookup skipped: technical user login returned no token");
      throw new SmtpSettingsUnavailableException();
    }
    return login.accessToken();
  }

  private void addDefaultHeaders(ApiClient apiClient) {
    var headers = this.securityHeaderSupplier.getCsrfHttpHeaders();
    tenantHeaderSupplier.addTenantHeader(headers);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
