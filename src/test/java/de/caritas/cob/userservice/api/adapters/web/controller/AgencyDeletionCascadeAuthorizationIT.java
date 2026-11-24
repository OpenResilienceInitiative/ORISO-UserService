package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDeletionPreviewDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDeletionResultDTO;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyDeletionCascadeService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/**
 * Deleting a Beratungsstelle (ORISO-Admin#1524) is for Träger and platform admins, like deleting it
 * in AgencyService; an agency admin may neither preview nor run it. The scope check itself is
 * covered in AgencyDeletionCascadeServiceTest.
 */
@SpringBootTest(
    properties = {
      "multitenancy.enabled=true",
      "feature.multitenancy.with.single.domain.enabled=true",
      "keycloak.config.app-client-id=directory-test-app"
    })
@ActiveProfiles("testing")
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AgencyDeletionCascadeAuthorizationIT {

  private static final String PATH = "/useradmin/agencies/7/deletion";
  private static final String HUMAN_APP = "directory-test-app";
  private static final String CALLER_SUBJECT = "fixture-subject";
  private static final long TRAEGER = 121L;

  @Autowired private MockMvc mvc;

  @Autowired
  @Qualifier("jwtAuthenticationConverter")
  private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter;

  @MockitoBean private AgencyDeletionCascadeService cascadeService;
  @MockitoBean private AccountInactivityService accountInactivity;
  @MockitoBean private TenantService tenantService;

  @BeforeEach
  void admitFixtureAccounts() {
    when(accountInactivity.admit(CALLER_SUBJECT, null)).thenReturn(true);
    when(tenantService.getRestrictedTenantDataFresh(TRAEGER))
        .thenReturn(new RestrictedTenantDTO().subdomain("example"));
  }

  @Test
  void traegerAdminRunsTheCascade() throws Exception {
    when(cascadeService.cascade(7L))
        .thenReturn(
            new AgencyDeletionResultDTO()
                .complete(true)
                .invites(0L)
                .adviceSeekers(2L)
                .keptAdviceSeekerSessions(1L)
                .openEnquiries(1L)
                .consultantsDeleted(1L)
                .consultantsUnassigned(1L)
                .agencyAdmins(0L)
                .groupChats(0L)
                .failures(List.of()));

    mvc.perform(
            post(PATH)
                .with(csrf())
                .with(caller(HUMAN_APP, TRAEGER, "tenant-admin", "agency-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.adviceSeekers").value(2));

    verify(cascadeService).cascade(7L);
  }

  @Test
  void traegerAdminSeesThePreview() throws Exception {
    when(cascadeService.preview(7L))
        .thenReturn(
            new AgencyDeletionPreviewDTO()
                .adviceSeekers(2L)
                .keptAdviceSeekerSessions(1L)
                .openEnquiries(1L)
                .consultantsDeleted(1L)
                .consultantsUnassigned(1L)
                .agencyAdmins(0L)
                .groupChats(0L));

    mvc.perform(get(PATH).with(caller(HUMAN_APP, TRAEGER, "tenant-admin", "agency-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.consultantsDeleted").value(1));
  }

  @Test
  void agencyAdminMayNeitherPreviewNorDelete() throws Exception {
    mvc.perform(get(PATH).with(caller(HUMAN_APP, TRAEGER, "restricted-agency-admin")))
        .andExpect(status().isForbidden());
    mvc.perform(post(PATH).with(csrf()).with(caller(HUMAN_APP, TRAEGER, "restricted-agency-admin")))
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
