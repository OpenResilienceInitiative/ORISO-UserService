package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeDraftService;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeDraftService.DraftView;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@TestPropertySource(
    properties = {
      "spring.profiles.active=testing",
      "spring.datasource.url=jdbc:h2:mem:service-notice-auth;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
      "spring.datasource.username=sa",
      "spring.datasource.password=sa",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.sql.init.mode=never",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
      "keycloak.auth-server-url=https://auth.testing",
      "keycloak.realm=testing",
      "keycloak.config.admin-username=admin",
      "keycloak.config.admin-password=secret",
      "identity.openid-connect-url=https://auth.testing/realms/testing/protocol/openid-connect",
      "consulting.type.service.api.url=https://consulting-type.testing/service",
      "tenant.service.api.url=https://tenant.testing/service",
      "matrix.apiUrl=https://matrix.testing",
      "matrix.registrationSharedSecret=secret"
    })
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("testing")
class ServiceNoticeDraftControllerAuthorizationIT {

  private static final String DRAFT = "/service/users/admin/service-notices/drafts/maintenance-1";

  @Autowired private MockMvc mvc;
  @MockitoBean private ServiceNoticeDraftService drafts;
  @MockitoBean private AuthenticatedUser authenticatedUser;

  @Test
  void anonymousTenantAdminAndTechnicalCallerCannotReadThePlatformDraft() throws Exception {
    mvc.perform(get(DRAFT)).andExpect(status().isUnauthorized());
    mvc.perform(
            get(DRAFT)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim(
                                        "realm_access",
                                        Map.of("roles", List.of("agency-admin", "tenant-admin")))
                                    .claim("tenantId", 7))))
        .andExpect(status().isForbidden());
    mvc.perform(get(DRAFT).with(jwt().authorities(new SimpleGrantedAuthority("technical"))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(drafts);
  }

  @Test
  void exactPlatformRolePairCanReadDraftWithoutCacheStorage() throws Exception {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    when(drafts.get("maintenance-1"))
        .thenReturn(
            new DraftView(
                "maintenance-1",
                "DRAFT",
                LocalDate.of(2026, 10, 2),
                LocalTime.of(14, 0),
                LocalTime.of(15, 0),
                "https://status.operator.dev/maintenance"));
    mvc.perform(
            get(DRAFT)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim(
                                        "realm_access",
                                        Map.of("roles", List.of("agency-admin", "tenant-admin")))
                                    .claim("tenantId", 0))))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
  }
}
