package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.TECHNICAL_DEFAULT;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.service.DeletionAuditLogsService;
import de.caritas.cob.userservice.api.service.DeletionAuditLogsService.DeletionAuditLogsResult;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionAuditService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@TestPropertySource(
    properties = {
      "spring.profiles.active=testing",
      "spring.datasource.url=jdbc:h2:mem:deletion-audit-auth;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MariaDB;NON_KEYWORDS=USER,VALUE,DAY",
      "spring.datasource.username=sa",
      "spring.datasource.password=sa",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.sql.init.mode=always",
      "spring.sql.init.data-locations=classpath:database/AccountInactivityDatabase.sql",
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
class DeletionAuditLogsControllerAuthorizationIT {

  private static final String ENDPOINT = "/service/users/deletion-audit-logs";

  @Autowired private MockMvc mvc;

  @MockitoBean private DeletionAuditLogsService deletionAuditLogsService;
  @MockitoBean private DeletionAuditService deletionAuditService;
  @MockitoBean private AdminScope adminScope;

  @BeforeEach
  void returnEmptyAuditLogPage() {
    when(deletionAuditLogsService.listAuditLogs(anyInt(), anyInt(), isNull(), isNull(), isNull()))
        .thenReturn(
            DeletionAuditLogsResult.builder().data(List.of()).total(0).page(1).perPage(20).build());
  }

  @Test
  void listAuditLogs_Should_ReturnUnauthorized_WhenNoAuthentication() throws Exception {
    mvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());

    verifyNoInteractions(deletionAuditLogsService);
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

    verifyNoInteractions(deletionAuditLogsService);
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

    verifyNoInteractions(deletionAuditLogsService);
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

    verifyNoInteractions(deletionAuditLogsService);
  }

  private static final String AGENCY_REPORT =
      "{\"targetType\":\"AGENCY\",\"targetId\":\"268\",\"tenantId\":4,"
          + "\"outcome\":\"SCHEDULED\",\"actorId\":\"admin-7\",\"actorRole\":\"AGENCY_ADMIN\"}";

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      postWithCsrf() {
    return post(ENDPOINT)
        .header("X-CSRF-Token", "t")
        .cookie(new jakarta.servlet.http.Cookie("CSRF-TOKEN", "t"));
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor adminOfTenant(
      long tenantId, String authority, String... roles) {
    return jwt()
        .jwt(
            token ->
                token
                    .claim("realm_access", Map.of("roles", List.of(roles)))
                    .claim("tenantId", tenantId))
        .authorities(new SimpleGrantedAuthority(authority));
  }

  @Test
  void report_Should_ReturnUnauthorized_WhenNoAuthentication() throws Exception {
    mvc.perform(postWithCsrf().contentType(MediaType.APPLICATION_JSON).content(AGENCY_REPORT))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(deletionAuditService);
  }

  @Test
  void report_Should_RecordTheNamedActor_WhenTechnicalAccountReports() throws Exception {
    mvc.perform(
            postWithCsrf()
                .with(jwt().authorities(new SimpleGrantedAuthority(TECHNICAL_DEFAULT)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(AGENCY_REPORT))
        .andExpect(status().isNoContent());

    verify(deletionAuditService)
        .recordReported(
            DeletionAuditTargetType.AGENCY,
            "268",
            4L,
            DeletionAuditOutcome.SCHEDULED,
            "admin-7",
            "AGENCY_ADMIN");
  }

  @Test
  void report_Should_RecordAsSystem_WhenTechnicalAccountNamesNoActor() throws Exception {
    mvc.perform(
            postWithCsrf()
                .with(jwt().authorities(new SimpleGrantedAuthority(TECHNICAL_DEFAULT)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"targetType\":\"AGENCY\",\"targetId\":\"268\",\"tenantId\":4,"
                        + "\"outcome\":\"EXECUTED\"}"))
        .andExpect(status().isNoContent());

    verify(deletionAuditService)
        .recordAsSystem(DeletionAuditTargetType.AGENCY, "268", 4L, DeletionAuditOutcome.EXECUTED);
  }

  @Test
  void report_Should_TakeTheActorFromTheToken_AndIgnoreTheBody_WhenAnAdminForwardsItsOwnToken()
      throws Exception {
    mvc.perform(
            postWithCsrf()
                .with(
                    adminOfTenant(
                        4, AuthorityValue.RESTRICTED_AGENCY_ADMIN, "restricted-agency-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    AGENCY_REPORT
                        .replace("admin-7", "someone-else")
                        .replace("AGENCY_ADMIN", "PLATFORM_ADMIN")))
        .andExpect(status().isNoContent());

    verify(deletionAuditService)
        .recordAsCurrentActor(
            DeletionAuditTargetType.AGENCY, "268", 4L, DeletionAuditOutcome.SCHEDULED);
  }

  @Test
  void report_Should_ReturnForbidden_WhenAnAdminReportsForAnotherTenant() throws Exception {
    mvc.perform(
            postWithCsrf()
                .with(
                    adminOfTenant(
                        5, AuthorityValue.RESTRICTED_AGENCY_ADMIN, "restricted-agency-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(AGENCY_REPORT))
        .andExpect(status().isForbidden());

    verifyNoInteractions(deletionAuditService);
  }

  @Test
  void report_Should_AcceptAPlainAgencyAdmin_WhoMayDeleteABeratungsstelleInAgencyService()
      throws Exception {
    mvc.perform(
            postWithCsrf()
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("realm_access", Map.of("roles", List.of("agency-admin")))
                                    .claim("tenantId", 4L))
                        .authorities())
                .contentType(MediaType.APPLICATION_JSON)
                .content(AGENCY_REPORT))
        .andExpect(status().isNoContent());

    verify(deletionAuditService)
        .recordAsCurrentActor(
            DeletionAuditTargetType.AGENCY, "268", 4L, DeletionAuditOutcome.SCHEDULED);
  }

  @Test
  void report_Should_ReturnForbidden_WhenAnAdminReportsAnAgencyAsExecuted() throws Exception {
    // Only the nightly purge (technical account, via the AgencyService outbox) executes an
    // agency deletion; a person's EXECUTED row would make the outbox pull skip the real one.
    mvc.perform(
            postWithCsrf()
                .with(
                    adminOfTenant(
                        4, AuthorityValue.RESTRICTED_AGENCY_ADMIN, "restricted-agency-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(AGENCY_REPORT.replace("SCHEDULED", "EXECUTED")))
        .andExpect(status().isForbidden());

    verifyNoInteractions(deletionAuditService);
  }

  @Test
  void report_Should_RecordATenantAsExecuted_WhenItsAdminDeletedIt() throws Exception {
    mvc.perform(
            postWithCsrf()
                .with(adminOfTenant(4, AuthorityValue.TENANT_ADMIN, "tenant-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"targetType\":\"TENANT\",\"targetId\":\"4\",\"tenantId\":4,"
                        + "\"outcome\":\"EXECUTED\"}"))
        .andExpect(status().isNoContent());

    verify(adminScope).assertMay(Target.tenant(4L));
    verify(deletionAuditService)
        .recordAsCurrentActor(
            DeletionAuditTargetType.TENANT, "4", 4L, DeletionAuditOutcome.EXECUTED);
  }

  @Test
  void report_Should_ReturnForbidden_WhenATenantAdminReportsAnotherTenantAsTheTarget()
      throws Exception {
    doThrow(new ForbiddenException("act on another Träger"))
        .when(adminScope)
        .assertMay(Target.tenant(9L));

    mvc.perform(
            postWithCsrf()
                .with(adminOfTenant(4, AuthorityValue.TENANT_ADMIN, "tenant-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"targetType\":\"TENANT\",\"targetId\":\"9\",\"tenantId\":4,"
                        + "\"outcome\":\"EXECUTED\"}"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(deletionAuditService);
  }

  @Test
  void report_Should_ReturnForbidden_WhenAnAgencyAdminReportsAnAgencyItDoesNotAdminister()
      throws Exception {
    doThrow(new ForbiddenException("act on agency 268"))
        .when(adminScope)
        .assertMay(Target.agencies(List.of(268L)));

    mvc.perform(
            postWithCsrf()
                .with(
                    adminOfTenant(
                        4, AuthorityValue.RESTRICTED_AGENCY_ADMIN, "restricted-agency-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(AGENCY_REPORT))
        .andExpect(status().isForbidden());

    verifyNoInteractions(deletionAuditService);
  }

  @Test
  void report_Should_ReturnBadRequest_WhenTechnicalAccountNamesARoleButNoActor() throws Exception {
    mvc.perform(
            postWithCsrf()
                .with(jwt().authorities(new SimpleGrantedAuthority(TECHNICAL_DEFAULT)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(AGENCY_REPORT.replace("\"actorId\":\"admin-7\",", "")))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(deletionAuditService);
  }

  @Test
  void report_Should_ReturnBadRequest_WhenTechnicalAccountNamesAnActorButNoRole() throws Exception {
    mvc.perform(
            postWithCsrf()
                .with(jwt().authorities(new SimpleGrantedAuthority(TECHNICAL_DEFAULT)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(AGENCY_REPORT.replace(",\"actorRole\":\"AGENCY_ADMIN\"", "")))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(deletionAuditService);
  }

  @Test
  void report_Should_ReturnForbidden_WhenCallerIsAConsultant() throws Exception {
    mvc.perform(
            postWithCsrf()
                .with(adminOfTenant(4, AuthorityValue.CONSULTANT_DEFAULT, "consultant"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(AGENCY_REPORT))
        .andExpect(status().isForbidden());

    verifyNoInteractions(deletionAuditService);
  }

  @Test
  void report_Should_ReturnBadRequest_WhenTargetTypeIsNotAgencyOrTenant() throws Exception {
    mvc.perform(
            postWithCsrf()
                .with(jwt().authorities(new SimpleGrantedAuthority(TECHNICAL_DEFAULT)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(AGENCY_REPORT.replace("AGENCY\"", "CONSULTANT\"")))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(deletionAuditService);
  }

  @Test
  void report_Should_ReturnBadRequest_WhenTargetIdIsNotAnOpaqueNumber() throws Exception {
    mvc.perform(
            postWithCsrf()
                .with(jwt().authorities(new SimpleGrantedAuthority(TECHNICAL_DEFAULT)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(AGENCY_REPORT.replace("\"268\"", "\"Beratungsstelle Nord\"")))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(deletionAuditService);
  }

  @Test
  void report_Should_ReturnBadRequest_WhenActorRoleIsFreeText() throws Exception {
    mvc.perform(
            postWithCsrf()
                .with(jwt().authorities(new SimpleGrantedAuthority(TECHNICAL_DEFAULT)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(AGENCY_REPORT.replace("AGENCY_ADMIN", "max.mustermann@example.org")))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(deletionAuditService);
  }
}
