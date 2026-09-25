package de.caritas.cob.userservice.api.tenant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tenant 0 turns the tenant filter off, so a token may only claim it for the platform admin; the
 * technical user reaches it through its role. Real bearer token and tenant resolution.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
class TenantZeroClaimIT {

  private static final long SUBDOMAIN_TENANT = 2L;
  private static final String OWN_ROUTE = "/users/tutorials/progress";

  @Autowired private MockMvc mockMvc;

  @MockitoBean TenantService tenantService;
  @MockitoBean SubdomainTenantResolver subdomainTenantResolver;
  @MockitoBean AuthenticatedUser authenticatedUser;

  @BeforeEach
  void callerOnTheSubdomainOfTenantTwo() {
    when(subdomainTenantResolver.canResolve(any())).thenReturn(true);
    when(subdomainTenantResolver.resolve(any())).thenReturn(Optional.of(SUBDOMAIN_TENANT));
    when(tenantService.getRestrictedTenantData(SUBDOMAIN_TENANT))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    when(authenticatedUser.getUserId()).thenReturn("tenant-zero-claim-caller");
  }

  @Test
  void counsellor_Should_BeRefused_When_TokenClaimsTenantZero() throws Exception {
    mockMvc
        .perform(ownRoute().with(token(0, AuthorityValue.CONSULTANT_DEFAULT, "consultant")))
        .andExpect(status().isForbidden());
  }

  @Test
  void adviceSeeker_Should_BeRefused_When_TokenClaimsTenantZero() throws Exception {
    mockMvc
        .perform(ownRoute().with(token(0, AuthorityValue.USER_DEFAULT, "user")))
        .andExpect(status().isForbidden());
  }

  @Test
  void counsellor_Should_BeServed_When_TokenClaimsTheSubdomainsTenant() throws Exception {
    mockMvc
        .perform(
            ownRoute()
                .with(token(SUBDOMAIN_TENANT, AuthorityValue.CONSULTANT_DEFAULT, "consultant")))
        .andExpect(status().isOk());
  }

  @Test
  void platformAdmin_Should_BeServed_When_TokenClaimsTenantZero() throws Exception {
    mockMvc
        .perform(
            ownRoute().with(token(0, AuthorityValue.TENANT_ADMIN, "agency-admin", "tenant-admin")))
        .andExpect(status().isOk());
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      ownRoute() {
    return get(OWN_ROUTE).param("surface", "frontend");
  }

  private static JwtRequestPostProcessor token(long tenantId, String authority, String... roles) {
    return jwt()
        .jwt(
            claims ->
                claims
                    .claim("tenantId", tenantId)
                    .claim("realm_access", Map.of("roles", List.of(roles))))
        .authorities(new SimpleGrantedAuthority(authority));
  }
}
