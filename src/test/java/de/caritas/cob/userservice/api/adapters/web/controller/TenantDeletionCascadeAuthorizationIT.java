package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.TenantDeletionResultDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantDeletionCascadeService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Only the platform administrator in person may empty a Träger (ORISO-Admin#1525). */
@SpringBootTest(
    properties = {
      "multitenancy.enabled=true",
      "feature.multitenancy.with.single.domain.enabled=true",
      "keycloak.config.app-client-id=directory-test-app"
    })
@ActiveProfiles("testing")
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TenantDeletionCascadeAuthorizationIT {

  private static final String PATH = "/useradmin/tenants/121/deletion";
  private static final String HUMAN_APP = "directory-test-app";
  private static final String CALLER_SUBJECT = "fixture-subject";
  private static final long TRAEGER = 121L;

  @Autowired private MockMvc mvc;

  @Autowired
  @Qualifier("jwtAuthenticationConverter")
  private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter;

  @MockitoBean private TenantDeletionCascadeService cascadeService;
  @MockitoBean private AccountInactivityService accountInactivity;
  @MockitoBean private TenantService tenantService;

  @BeforeEach
  void admitFixtureAccounts() {
    when(accountInactivity.admit(CALLER_SUBJECT, null)).thenReturn(true);
    when(tenantService.getRestrictedTenantDataFresh(TRAEGER))
        .thenReturn(new RestrictedTenantDTO().subdomain("example"));
  }

  @Test
  void platformAdministratorEmptiesTheTraeger() throws Exception {
    when(cascadeService.cascade(121L))
        .thenReturn(
            new TenantDeletionResultDTO()
                .complete(true)
                .invites(1L)
                .consultants(2L)
                .adviceSeekers(3L)
                .agencyAdmins(0L)
                .tenantAdmins(1L)
                .agencies(1L)
                .failures(List.of()));

    mvc.perform(post(PATH).with(csrf()).with(caller(HUMAN_APP, 0L, "tenant-admin", "agency-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.complete").value(true))
        .andExpect(jsonPath("$.consultants").value(2));

    verify(cascadeService).cascade(121L);
  }

  @Test
  void traegerAdminIsForbiddenEvenForTheirOwnTraeger() throws Exception {
    mvc.perform(
            post(PATH)
                .with(csrf())
                .with(caller(HUMAN_APP, TRAEGER, "tenant-admin", "agency-admin")))
        .andExpect(status().isForbidden());

    verifyNoInteractions(cascadeService);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "directory-test-service")
  void platformClaimWithoutTheHumanAppIsForbidden(String clientId) throws Exception {
    mvc.perform(
            post(PATH)
                .with(csrf())
                .with(caller(clientId, 0L, "tenant-admin", "agency-admin", "technical")))
        .andExpect(status().isForbidden());

    verifyNoInteractions(cascadeService);
  }

  @Test
  void unauthenticatedRequestIsRejected() throws Exception {
    mvc.perform(post(PATH).with(csrf()).header("tenantId", TRAEGER))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(cascadeService);
  }

  private RequestPostProcessor caller(String clientId, long tenantId, String... roles) {
    return jwt()
        .jwt(
            token -> {
              token
                  .subject(CALLER_SUBJECT)
                  .claim("preferred_username", "fixture-user")
                  .claim("tenantId", tenantId)
                  .claim("realm_access", Map.of("roles", List.of(roles)));
              if (clientId != null) {
                token.claim("azp", clientId);
              }
            })
        .authorities(token -> jwtAuthenticationConverter.convert(token).getAuthorities());
  }
}
