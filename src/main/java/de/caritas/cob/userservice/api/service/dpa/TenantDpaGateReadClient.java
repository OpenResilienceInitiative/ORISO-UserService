package de.caritas.cob.userservice.api.service.dpa;

import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.DpaGateStatusDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Fresh owner read as the service identity, including for unauthenticated enquiry creation. */
@Service
@RequiredArgsConstructor
public class TenantDpaGateReadClient {
  private final TenantServiceApiControllerFactory tenantFactory;
  private final IdentityAuthentication identityAuthentication;
  private final IdentityClientConfig identityClientConfig;
  private final SecurityHeaderSupplier securityHeaderSupplier;

  public DpaGateStatusDTO read(Long servingTenantId) {
    // The serving organisation is already resolved; service reads may cross tenant boundaries.
    return de.caritas.cob.userservice.api.tenant.TenantContext.supplyAcrossTenants(
        () -> readAsService(servingTenantId));
  }

  private DpaGateStatusDTO readAsService(Long tenantId) {
    var technical =
        identityClientConfig.getTaskIdentity(
            de.caritas.cob.userservice.api.config.auth.TaskIdentity.RUNTIME_POLICY);
    IdentityLogin login = identityAuthentication.loginTask(technical);
    if (login == null || login.accessToken() == null || login.accessToken().isBlank()) {
      throw new IllegalStateException("Service authentication unavailable");
    }
    var api = tenantFactory.createControllerApi();
    securityHeaderSupplier
        .getKeycloakAndCsrfHttpHeaders(login.accessToken())
        .forEach(
            (name, values) ->
                values.forEach(value -> api.getApiClient().addDefaultHeader(name, value)));
    api.getApiClient().addDefaultHeader("tenantId", "0");
    // Retain JSON field presence: generated DTOs otherwise make null decision fields
    // indistinguishable from an older owner that never supplied those fields.
    var response =
        api.getApiClient()
            .invokeAPI(
                "/tenantadmin/{id}/dpa/gate",
                org.springframework.http.HttpMethod.GET,
                java.util.Map.of("id", tenantId),
                new org.springframework.util.LinkedMultiValueMap<>(),
                null,
                new org.springframework.http.HttpHeaders(),
                new org.springframework.util.LinkedMultiValueMap<>(),
                new org.springframework.util.LinkedMultiValueMap<>(),
                java.util.List.of(org.springframework.http.MediaType.APPLICATION_JSON),
                null,
                new String[0],
                new org.springframework.core.ParameterizedTypeReference<
                    java.util.Map<String, Object>>() {});
    return decode(response.getBody());
  }

  private DpaGateStatusDTO decode(java.util.Map<String, Object> data) {
    if (data == null) throw new IllegalStateException("AVV contract unavailable");
    for (var key :
        java.util.List.of(
            "dpaPublished", "dpaSigned", "renewalGraceActive", "newCounsellingAllowed")) {
      if (data.get(key) != null && !(data.get(key) instanceof Boolean)) {
        throw new IllegalStateException("Invalid AVV contract type");
      }
    }
    for (var key : java.util.List.of("dpaStatus", "currentDpaVersion", "signingDeadlineAt")) {
      if (data.get(key) != null && !(data.get(key) instanceof String)) {
        throw new IllegalStateException("Invalid AVV contract type");
      }
    }
    boolean decisionFieldsPresent =
        java.util.List.of(
                "dpaStatus",
                "currentDpaVersion",
                "signingDeadlineAt",
                "renewalGraceActive",
                "newCounsellingAllowed")
            .stream()
            .anyMatch(data::containsKey);
    if (decisionFieldsPresent
        && (!(data.get("dpaStatus") instanceof String)
            || !(data.get("renewalGraceActive") instanceof Boolean)
            || !(data.get("newCounsellingAllowed") instanceof Boolean))) {
      throw new IllegalStateException("Incomplete AVV decision contract");
    }
    return new DpaGateStatusDTO()
        .dpaPublished((Boolean) data.get("dpaPublished"))
        .dpaSigned((Boolean) data.get("dpaSigned"))
        .dpaStatus((String) data.get("dpaStatus"))
        .currentDpaVersion((String) data.get("currentDpaVersion"))
        .signingDeadlineAt((String) data.get("signingDeadlineAt"))
        .renewalGraceActive((Boolean) data.get("renewalGraceActive"))
        .newCounsellingAllowed((Boolean) data.get("newCounsellingAllowed"));
  }
}
