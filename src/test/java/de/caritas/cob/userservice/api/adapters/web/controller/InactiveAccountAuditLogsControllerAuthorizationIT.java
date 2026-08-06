package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.TECHNICAL_DEFAULT;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.service.InactiveAccountAuditLogsService;
import de.caritas.cob.userservice.api.service.InactiveAccountAuditLogsService.InactiveAccountAuditLogsResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
      "spring.datasource.url=jdbc:h2:mem:inactive-audit-auth;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
class InactiveAccountAuditLogsControllerAuthorizationIT {

  private static final String ENDPOINT = "/service/users/inactive-accounts/audit-logs";

  @Autowired private MockMvc mvc;

  @MockitoBean private InactiveAccountAuditLogsService inactiveAccountAuditLogsService;

  @BeforeEach
  void returnEmptyAuditLogPage() {
    when(inactiveAccountAuditLogsService.listAuditLogs(anyInt(), anyInt(), isNull(), isNull()))
        .thenReturn(
            InactiveAccountAuditLogsResult.builder()
                .data(List.of())
                .total(0)
                .page(1)
                .perPage(20)
                .build());
  }

  @Test
  void listAuditLogs_Should_ReturnUnauthorized_WhenNoAuthentication() throws Exception {
    mvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());

    verifyNoInteractions(inactiveAccountAuditLogsService);
  }

  @Test
  void listAuditLogs_Should_ReturnOk_WhenTechnicalAccountIsAuthorized() throws Exception {
    mvc.perform(
            get(ENDPOINT).with(jwt().authorities(new SimpleGrantedAuthority(TECHNICAL_DEFAULT))))
        .andExpect(status().isOk());
  }

  @Test
  void listAuditLogs_Should_ReturnOk_WhenPlatformAdminIsAuthorized() throws Exception {
    mvc.perform(
            get(ENDPOINT)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim(
                                        "realm_access",
                                        Map.of("roles", List.of("agency-admin", "tenant-admin")))
                                    .claim("tenantId", 0))))
        .andExpect(status().isOk());
  }

  @Test
  void listAuditLogs_Should_ReturnForbidden_WhenTenantScopedAdminUsesPlatformRoles()
      throws Exception {
    mvc.perform(
            get(ENDPOINT)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim(
                                        "realm_access",
                                        Map.of("roles", List.of("agency-admin", "tenant-admin")))
                                    .claim("tenantId", 1))))
        .andExpect(status().isForbidden());

    verifyNoInteractions(inactiveAccountAuditLogsService);
  }

  @Test
  void listAuditLogs_Should_ReturnForbidden_WhenTenantIdIsFractionalZero() throws Exception {
    mvc.perform(
            get(ENDPOINT)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim(
                                        "realm_access",
                                        Map.of("roles", List.of("agency-admin", "tenant-admin")))
                                    .claim("tenantId", 0.5))))
        .andExpect(status().isForbidden());

    verifyNoInteractions(inactiveAccountAuditLogsService);
  }

  @Test
  void listAuditLogs_Should_ReturnForbidden_WhenTenantAdminLacksAgencyAdminRole() throws Exception {
    mvc.perform(
            get(ENDPOINT)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("realm_access", Map.of("roles", List.of("tenant-admin")))
                                    .claim("tenantId", 0))))
        .andExpect(status().isForbidden());

    verifyNoInteractions(inactiveAccountAuditLogsService);
  }
}
