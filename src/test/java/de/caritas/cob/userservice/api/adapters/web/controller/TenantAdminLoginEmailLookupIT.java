package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.admin.service.admin.AccountLoginStatusService;
import de.caritas.cob.userservice.api.admin.service.admin.ManagedAccountStatusService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "multitenancy.enabled=true",
      "feature.multitenancy.with.single.domain.enabled=true",
      "keycloak.config.app-client-id=directory-test-app"
    })
@ActiveProfiles("testing")
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class TenantAdminLoginEmailLookupIT {

  private static final String SINGLE_LOOKUP = "/useradmin/tenantadmins";
  private static final String BATCH_LOOKUP = SINGLE_LOOKUP + "/login-emails";
  private static final String HUMAN_APP = "directory-test-app";
  private static final String CALLER_SUBJECT = "fixture-subject";
  private static final long TENANT_WITH_ADMINS = 900001L;
  private static final long EMPTY_TENANT = 900002L;
  private static final long UNREQUESTED_TENANT = 900003L;

  @Autowired private MockMvc mvc;

  @Autowired
  @Qualifier("jwtAuthenticationConverter")
  private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter;

  @MockitoSpyBean private AdminRepository adminRepository;
  @MockitoBean private AccountLoginStatusService accountLoginStatus;
  @MockitoBean private AccountInactivityService accountInactivity;
  @MockitoBean private ManagedAccountStatusService managedAccountStatus;
  @MockitoBean private TenantService tenantService;

  @BeforeEach
  void admitFixtureAccounts() {
    when(accountInactivity.admit(CALLER_SUBJECT, null)).thenReturn(true);
  }

  @Test
  void platformAdminReceivesOnlyRequestedLoginEmailsAndAnEntryForEachTenant() throws Exception {
    var pausedOwner = admin("owner-two", TENANT_WITH_ADMINS, AdminType.TENANT);
    pausedOwner.setManagedPaused(true);
    adminRepository.saveAllAndFlush(
        List.of(
            admin("owner-one", TENANT_WITH_ADMINS, AdminType.TENANT),
            pausedOwner,
            admin("agency-admin", TENANT_WITH_ADMINS, AdminType.AGENCY),
            admin("unrequested-owner", UNREQUESTED_TENANT, AdminType.TENANT)));
    clearInvocations(adminRepository);

    mvc.perform(
            get(BATCH_LOOKUP)
                .param(
                    "tenantIds", String.valueOf(EMPTY_TENANT), String.valueOf(TENANT_WITH_ADMINS))
                .with(caller(HUMAN_APP, 0L, "tenant-admin", "agency-admin", "technical")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].tenantId").value(EMPTY_TENANT))
        .andExpect(jsonPath("$[0].emails").isEmpty())
        .andExpect(jsonPath("$[1].tenantId").value(TENANT_WITH_ADMINS))
        .andExpect(
            jsonPath(
                "$[1].emails",
                containsInAnyOrder("owner-one@example.test", "owner-two@example.test")))
        .andExpect(jsonPath("$[1].length()").value(2));

    verifyNoInteractions(accountLoginStatus, tenantService);
    verify(adminRepository)
        .findLoginEmailsByTenantIdsAndType(
            List.of(EMPTY_TENANT, TENANT_WITH_ADMINS), AdminType.TENANT);
    verifyNoMoreInteractions(adminRepository);
  }

  @Test
  void oldSingleTenantLookupStillReturnsExistingDtoForScopeOwner() throws Exception {
    adminRepository.saveAndFlush(admin("owner-three", TENANT_WITH_ADMINS, AdminType.TENANT));
    when(tenantService.getRestrictedTenantDataFresh(TENANT_WITH_ADMINS))
        .thenReturn(new RestrictedTenantDTO().subdomain("example"));

    mvc.perform(
            get(SINGLE_LOOKUP)
                .param("tenantId", String.valueOf(TENANT_WITH_ADMINS))
                .with(caller(HUMAN_APP, TENANT_WITH_ADMINS, "tenant-admin", "agency-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]._embedded.email").value("owner-three@example.test"))
        .andExpect(jsonPath("$[0]._embedded.contactEmail").value("contact@example.test"));
  }

  @Test
  void scopeOwnerCannotUsePlatformBatchEvenForTheirOwnTenant() throws Exception {
    when(tenantService.getRestrictedTenantDataFresh(TENANT_WITH_ADMINS))
        .thenReturn(new RestrictedTenantDTO().subdomain("example"));

    mvc.perform(
            get(BATCH_LOOKUP)
                .param("tenantIds", String.valueOf(TENANT_WITH_ADMINS))
                .with(caller(HUMAN_APP, TENANT_WITH_ADMINS, "tenant-admin", "agency-admin")))
        .andExpect(status().isForbidden());

    verifyNoInteractions(adminRepository, accountLoginStatus);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "directory-test-service")
  void platformClaimAndRoleWithoutTheConfiguredAppAreForbidden(String clientId) throws Exception {
    mvc.perform(
            get(BATCH_LOOKUP)
                .param("tenantIds", String.valueOf(TENANT_WITH_ADMINS))
                .with(caller(clientId, 0L, "tenant-admin", "agency-admin", "technical")))
        .andExpect(status().isForbidden());

    verifyNoInteractions(adminRepository, accountLoginStatus);
  }

  @Test
  void headRequestUsesTheSamePlatformRule() throws Exception {
    mvc.perform(
            head(BATCH_LOOKUP)
                .param("tenantIds", String.valueOf(TENANT_WITH_ADMINS))
                .with(
                    caller(
                        "directory-test-service", 0L, "tenant-admin", "agency-admin", "technical")))
        .andExpect(status().isForbidden());

    verifyNoInteractions(adminRepository, accountLoginStatus);
  }

  @Test
  void platformClaimAndAppWithoutBothPlatformRolesAreForbidden() throws Exception {
    mvc.perform(
            get(BATCH_LOOKUP)
                .param("tenantIds", String.valueOf(TENANT_WITH_ADMINS))
                .with(caller(HUMAN_APP, 0L, "tenant-admin", "technical")))
        .andExpect(status().isForbidden());

    verifyNoInteractions(adminRepository, accountLoginStatus);
  }

  @Test
  void unauthenticatedRequestsAreRejected() throws Exception {
    when(tenantService.getRestrictedTenantDataFresh(TENANT_WITH_ADMINS))
        .thenReturn(new RestrictedTenantDTO().subdomain("example"));

    mvc.perform(get(BATCH_LOOKUP).header("tenantId", TENANT_WITH_ADMINS).param("tenantIds", "1"))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(adminRepository, accountLoginStatus);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "0", "-1", "1,1", "invalid"})
  void invalidIdsAreRejectedBeforeReadingAdmins(String tenantIds) throws Exception {
    mvc.perform(
            get(BATCH_LOOKUP)
                .param("tenantIds", tenantIds)
                .with(caller(HUMAN_APP, 0L, "tenant-admin", "agency-admin")))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(adminRepository, accountLoginStatus);
  }

  @Test
  void oneHundredIdsAreAccepted() throws Exception {
    mvc.perform(
            get(BATCH_LOOKUP)
                .param("tenantIds", ids(100))
                .with(caller(HUMAN_APP, 0L, "tenant-admin", "agency-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(100));

    verifyNoInteractions(accountLoginStatus, tenantService);
  }

  @Test
  void moreThanOneHundredIdsAreRejectedBeforeReadingAdmins() throws Exception {
    mvc.perform(
            get(BATCH_LOOKUP)
                .param("tenantIds", ids(101))
                .with(caller(HUMAN_APP, 0L, "tenant-admin", "agency-admin")))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(adminRepository, accountLoginStatus);
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

  private static Admin admin(String name, long tenantId, AdminType type) {
    return Admin.builder()
        .id(name)
        .username(name)
        .firstName("Directory")
        .lastName("Fixture")
        .email(name + "@example.test")
        .contactEmail("contact@example.test")
        .tenantId(tenantId)
        .type(type)
        .build();
  }

  private static String[] ids(int count) {
    return IntStream.rangeClosed(1, count).mapToObj(String::valueOf).toArray(String[]::new);
  }
}
