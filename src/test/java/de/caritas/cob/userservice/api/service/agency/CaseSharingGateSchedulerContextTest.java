package de.caritas.cob.userservice.api.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyserivce.generated.web.AgencyControllerApi;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyCaseSharingPolicy;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyResponseDTO;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CaseSharingModel;
import de.caritas.cob.userservice.api.service.httpheader.HttpHeadersResolver;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.ScopeNotActiveException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * FE#2494 *4: the case sharing model snapshot runs without a user (technical tenant 0, no request
 * scope). The mode lookup must still reach AgencyService anonymously with {@code tenantId: 0}:
 * {@code /agencies/**} is public there, tenant 0 disables its tenant filter, and the served
 * settings are resolved from the agency's own tenant. Verified on Dev on 2026-11-25: {@code GET
 * /service/agencies/1} with {@code tenantId: 0} and no token answers 200 with {@code
 * settings.caseSharing}.
 */
class CaseSharingGateSchedulerContextTest {

  private static final long CENTRE = 883L;
  private static final long TENANT = 1L;

  private final AgencyControllerApi agencyApi = mock(AgencyControllerApi.class);
  private final ApiClient apiClient = mock(ApiClient.class);
  private CaseSharingGate gate;

  @BeforeEach
  void setUp() {
    var factory = mock(AgencyServiceApiControllerFactory.class);
    when(factory.createControllerApi()).thenReturn(agencyApi);
    when(agencyApi.getApiClient()).thenReturn(apiClient);

    // No request scope in a scheduler: the user token is unavailable.
    var user = mock(AuthenticatedUser.class);
    when(user.getAccessToken())
        .thenThrow(new ScopeNotActiveException("authenticatedUser", "request", null));
    var security = new SecurityHeaderSupplier(user);
    ReflectionTestUtils.setField(security, "csrfHeaderProperty", "X-CSRF-TOKEN");
    ReflectionTestUtils.setField(security, "csrfCookieProperty", "CSRF-TOKEN");
    var tenant = new TenantHeaderSupplier(mock(HttpHeadersResolver.class));
    ReflectionTestUtils.setField(tenant, "multitenancy", true);

    gate = new CaseSharingGate(new EffectiveAgencySettingsLookup(factory, security, tenant));
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void resolvesTeamberatungAnonymouslyWithTheTechnicalTenant() {
    when(agencyApi.getAgenciesByIds(List.of(CENTRE)))
        .thenReturn(
            List.of(
                new AgencyResponseDTO()
                    .id(CENTRE)
                    .tenantId(TENANT)
                    .settings(
                        new Settings(
                            new AgencyCaseSharingPolicy()
                                .value(AgencyCaseSharingPolicy.ValueEnum.TEAM_COUNSELLING)))));

    assertThat(gate.currentModel(CENTRE, TENANT)).contains(CaseSharingModel.TEAM_COUNSELLING);
    verify(apiClient).addDefaultHeader("tenantId", "0");
    verify(apiClient, never()).addDefaultHeader(eq("Authorization"), anyString());
  }
}
