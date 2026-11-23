package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.service.CaseHandoverPolicyCacheService;
import de.caritas.cob.userservice.api.tenant.SubdomainTenantResolver;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverPolicies;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * FE#1725 story 30: who may read or write which Träger's handover rules. Real bearer token, real
 * tenant resolution, real AdminScope; only TenantService is stubbed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
class CaseHandoverReasonPolicyAuthorizationIT {

  private static final long OWN = 7L;
  private static final long OTHER = 8L;
  private static final String URL = "/users/case-handover/reason-policies";
  private static final String BODY =
      "[{\"code\":\"COUNSELLOR_ASKED_FOR_ADVICE\",\"label\":\"Rat benötigt\",\"enabled\":true,"
          + "\"accessAllowed\":true,\"clientConsent\":\"OPT_IN\"}]";
  private static final String STORED =
      """
      {"reasons":{"COUNSELLOR_ASKED_FOR_ADVICE":{
        "code":"COUNSELLOR_ASKED_FOR_ADVICE",
        "labels":{"value":{"de":"Rat benötigt"},"mode":"SUGGESTED"},
        "enabled":{"value":true,"mode":"SUGGESTED"},
        "accessAllowed":{"value":true,"mode":"SUGGESTED"},
        "clientConsent":{"value":"OPT_IN","mode":"SUGGESTED"},
        "clientConsentRequired":{"value":true,"mode":"SUGGESTED"},
        "approvalRoles":{"value":["CLIENT"],"mode":"SUGGESTED"},
        "clientNotificationTemplates":{"value":{"de":"x"},"mode":"SUGGESTED"}}}}
      """;

  @Autowired private MockMvc mvc;

  @MockitoBean private TenantService tenantService;
  @MockitoBean private SubdomainTenantResolver subdomainTenantResolver;
  @MockitoBean private CaseHandoverPolicyCacheService policies;

  @BeforeEach
  void callerOnTheSubdomainOfTheOwnTraeger() {
    when(subdomainTenantResolver.canResolve(any())).thenReturn(true);
    when(subdomainTenantResolver.resolve(any())).thenReturn(Optional.of(OWN));
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    // HttpTenantFilter admits the request only after a fresh operational-status lookup.
    when(tenantService.getRestrictedTenantDataFresh(anyLong()))
        .thenReturn(new RestrictedTenantDTO().active(true).subdomain("synthetic"));
    when(policies.getEffective(anyLong())).thenAnswer(call -> stored());
    when(policies.updateEffective(anyLong(), any())).thenAnswer(call -> stored());
  }

  @Test
  void platformAdmin_Should_WriteTheNamedTraeger() throws Exception {
    write(OTHER, token(0, "agency-admin", "tenant-admin")).andExpect(status().isOk());
    verify(policies).updateEffective(eq(OTHER), any());
  }

  @Test
  void platformAdmin_Should_Answer400_When_NoTraegerIsNamed() throws Exception {
    write(null, token(0, "agency-admin", "tenant-admin")).andExpect(status().isBadRequest());
    verify(policies, never()).updateEffective(anyLong(), any());
  }

  @Test
  void traegerAdmin_Should_WriteTheirOwnTraeger() throws Exception {
    write(OWN, token(OWN, "tenant-admin")).andExpect(status().isOk());
    verify(policies).updateEffective(eq(OWN), any());
  }

  @Test
  void traegerAdmin_Should_Answer403_When_NamingAnotherTraeger() throws Exception {
    write(OTHER, token(OWN, "tenant-admin")).andExpect(status().isForbidden());
    read(OTHER, token(OWN, "tenant-admin")).andExpect(status().isForbidden());
    verify(policies, never()).updateEffective(anyLong(), any());
  }

  @Test
  void agencyAdmin_Should_Answer403_When_Writing_EvenWithUserAdmin() throws Exception {
    write(OWN, token(OWN, "restricted-agency-admin", "user-admin"))
        .andExpect(status().isForbidden());
    write(null, token(OWN, "restricted-agency-admin", "user-admin"))
        .andExpect(status().isForbidden());
    verify(policies, never()).updateEffective(anyLong(), any());
  }

  @Test
  void agencyAdmin_Should_ReadTheirOwnTraeger() throws Exception {
    read(OWN, token(OWN, "restricted-agency-admin", "user-admin")).andExpect(status().isOk());
    read(OTHER, token(OWN, "restricted-agency-admin", "user-admin"))
        .andExpect(status().isForbidden());
  }

  @Test
  void tenantZeroAdminWithoutPlatformRoles_Should_NotReachAnyTraeger() throws Exception {
    write(OTHER, token(0, "tenant-admin")).andExpect(status().isForbidden());
    verify(policies, never()).updateEffective(anyLong(), any());
  }

  /**
   * Least privilege (ORISO-Helm#367): the technical user is a machine identity, so it may neither
   * write nor read a Träger's handover rules, although AdminScope grants it platform reach.
   */
  @Test
  void technicalUser_Should_Answer403_When_WritingOrReadingAnyTraeger() throws Exception {
    var technical =
        jwt()
            .jwt(claims -> claims.claim("realm_access", Map.of("roles", List.of("technical"))))
            .authorities(new SimpleGrantedAuthority(AuthorityValue.TECHNICAL_DEFAULT));
    write(OTHER, technical).andExpect(status().isForbidden());
    write(null, technical).andExpect(status().isForbidden());
    read(OTHER, technical).andExpect(status().isForbidden());
    verify(policies, never()).updateEffective(anyLong(), any());
    verify(policies, never()).getEffective(anyLong());
  }

  private ResultActions write(Long tenantId, JwtRequestPostProcessor token) throws Exception {
    var request =
        put(URL)
            .with(token)
            .cookie(new Cookie("CSRF-TOKEN", "test"))
            .header("X-CSRF-TOKEN", "test")
            .contentType(MediaType.APPLICATION_JSON)
            .content(BODY);
    if (tenantId != null) {
      request.param("tenantId", tenantId.toString());
    }
    return mvc.perform(request);
  }

  private ResultActions read(Long tenantId, JwtRequestPostProcessor token) throws Exception {
    var request = get(URL).with(token);
    if (tenantId != null) {
      request.param("tenantId", tenantId.toString());
    }
    return mvc.perform(request);
  }

  private static CaseHandoverPolicies stored() {
    return JsonMapper.builder().build().readValue(STORED, CaseHandoverPolicies.class);
  }

  /** Admin authorities as the realm roles map them; the roles decide the reach. */
  private static JwtRequestPostProcessor token(long tenantId, String... roles) {
    List<String> roleList = List.of(roles);
    var authorities =
        roleList.stream()
            .map(
                role ->
                    switch (role) {
                      case "tenant-admin" -> AuthorityValue.TENANT_ADMIN;
                      case "user-admin" -> AuthorityValue.USER_ADMIN;
                      case "restricted-agency-admin" -> AuthorityValue.RESTRICTED_AGENCY_ADMIN;
                      default -> role;
                    })
            .<GrantedAuthority>map(SimpleGrantedAuthority::new)
            .toList();
    return jwt()
        .jwt(
            claims ->
                claims.claim("tenantId", tenantId).claim("realm_access", Map.of("roles", roleList)))
        .authorities(authorities);
  }
}
