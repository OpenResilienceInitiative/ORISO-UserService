package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.ApiClient;
import de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.DpaSignatureDTO;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * Authoritative read-back of a tenant's DPA signature audit list from TenantService
 * (ORISO-UserService#1005). The signed-notice trigger arriving from TenantService is an
 * UNAUTHENTICATED hint; every fact that ends up in a notice mail is read through THIS authenticated
 * technical-user call instead — a spoofed hint can therefore never fabricate notice content. Same
 * technical-user pattern as {@code TenantCreationClient}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantDpaSignatureReadClient {

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull IdentityAuthentication identityAuthentication;
  private final @NonNull IdentityClientConfig identityClientConfig;

  private final @NonNull TenantAdminServiceApiControllerFactory
      tenantAdminServiceApiControllerFactory;

  /** The tenant's DPA signature audit rows; empty when the read fails (notice stays unsent). */
  public List<DpaSignatureDTO> readSignatures(Long tenantId) {
    try {
      return createControllerApi().getDataProcessingAgreementSignatures(tenantId);
    } catch (RuntimeException exception) {
      // Deliberately every RuntimeException: the technical-user login inside
      // addTechnicalUserHeaders wraps HTTP failures in BadRequestException, which is NOT a
      // RestClientException and would otherwise escape and turn the anonymous hint endpoint's
      // documented 202 into a 400. Nothing here may surface to that caller.
      //
      // A failed read means no notice is ever sent and no ledger row is claimed, so this line is
      // the only trace of a silently skipped notice — it carries the exception, not just its
      // class name, or the status and body are lost with it.
      log.warn(
          "Could not read DPA signatures for tenant {} from TenantService", tenantId, exception);
      return List.of();
    }
  }

  private TenantControllerApi createControllerApi() {
    var controllerApi = tenantAdminServiceApiControllerFactory.createControllerApi();
    addTechnicalUserHeaders(controllerApi.getApiClient());
    return controllerApi;
  }

  private void addTechnicalUserHeaders(ApiClient apiClient) {
    var techUser = identityClientConfig.getTechnicalUser();
    var identityLogin =
        identityAuthentication.login(techUser.getUsername(), techUser.getPassword());
    HttpHeaders headers =
        securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(identityLogin.accessToken());
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
