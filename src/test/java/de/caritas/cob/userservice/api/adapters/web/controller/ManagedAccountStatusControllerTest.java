package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.ManagedAccountStatusService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.workflow.accountinactivity.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
@org.springframework.test.context.ActiveProfiles("testing")
@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(
    replace =
        org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE)
class ManagedAccountStatusControllerTest {
  private MockMvc http;
  private Admin target;
  private AuthenticatedUser actor;
  private IdentityAccountStatusLookup identities;
  private IdentitySecondFactor factors;
  private IdentityProfileLookup profiles;
  private ManagedAccountStatusService service;
  private AccountInactivityService lifecycle;
  private TenantService tenants;

  @org.springframework.beans.factory.annotation.Autowired
  private AgencyInviteLinkRepository inviteLinks;

  private de.caritas.cob.userservice.tenantservice.generated.web.TenantControllerApi tenantApi;
  @org.springframework.beans.factory.annotation.Autowired private AdminRepository admins;
  @org.springframework.beans.factory.annotation.Autowired private ConsultantRepository consultants;
  @org.springframework.beans.factory.annotation.Autowired private JdbcTemplate jdbc;
  @org.springframework.beans.factory.annotation.Autowired private javax.sql.DataSource dataSource;

  @org.springframework.beans.factory.annotation.Autowired
  private jakarta.persistence.EntityManager entityManager;

  @org.springframework.beans.factory.annotation.Autowired
  private AdminAgencyRepository adminAgencies;

  @org.springframework.beans.factory.annotation.Autowired
  private ConsultantAgencyRepository consultantAgencies;

  @org.springframework.beans.factory.annotation.Autowired private UserRepository users;
  @org.springframework.beans.factory.annotation.Autowired private SessionRepository sessions;
  @org.springframework.beans.factory.annotation.Autowired private UserAgencyRepository userAgencies;

  @BeforeEach
  void setup() {
    actor = new AuthenticatedUser();
    actor.setUserId("observer");
    actor.setTenantId(0L);
    actor.setRoles(Set.of("agency-admin", "tenant-admin", "user-admin"));
    actor.setGrantedAuthorities(Set.of(USER_ADMIN, TENANT_ADMIN));
    identities = mock(IdentityAccountStatusLookup.class);
    factors = mock(IdentitySecondFactor.class);
    profiles = mock(IdentityProfileLookup.class);
    target =
        Admin.builder()
            .id("target")
            .username(
                new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
                    .encodeUsername("target-login"))
            .firstName("Test")
            .lastName("Account")
            .email("target@example.invalid")
            .tenantId(0L)
            .type(Admin.AdminType.TENANT)
            .build();
    admins.saveAndFlush(target);
    when(profiles.findById("target"))
        .thenReturn(
            Optional.of(
                new IdentityProfile(
                    "target", target.getUsername(), "Test", "Account", "target@example.invalid")));
    when(identities.findEnabledById("target")).thenReturn(Optional.of(true));
    when(factors.getOtpCredential(target.getUsername()))
        .thenReturn(new IdentityOtpCredential(false, null, null, null));
    var scope =
        new AdminScope(
            actor,
            admins,
            adminAgencies,
            consultants,
            consultantAgencies,
            mock(AgencyService.class),
            users,
            sessions,
            userAgencies);
    ReflectionTestUtils.setField(scope, "multitenancyEnabled", true);
    lifecycle =
        new AccountInactivityService(
            jdbc,
            new DataSourceTransactionManager(dataSource),
            Clock.systemUTC(),
            mock(AccountInactivityEffects.class));
    lifecycle.assignAtCreation("target", 0L, 6, 1, Instant.now());
    var factory = mock(TenantServiceApiControllerFactory.class);
    tenantApi =
        mock(de.caritas.cob.userservice.tenantservice.generated.web.TenantControllerApi.class);
    when(factory.createControllerApi()).thenReturn(tenantApi);
    tenants = new TenantService(factory, new ConcurrentMapCacheManager());
    service =
        new ManagedAccountStatusService(
            actor, scope, admins, consultants, identities, profiles, factors, lifecycle, tenants);
    http =
        MockMvcBuilders.standaloneSetup(new ManagedAccountStatusController(service))
            .addFilters(
                new de.caritas.cob.userservice.api.adapters.web.controller.interceptor
                    .ManagedAccountPauseAccessFilter(service))
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();
  }

  @Test
  void authorizedAdminPausesOnlyTheSelectedAccountAndCanReloadAndReactivate() throws Exception {
    http.perform(
            put("/service/useradmin/accounts/target/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false))
        .andExpect(jsonPath("$.loginEnabled").value(true));
    entityManager.flush();
    entityManager.clear();
    http.perform(get("/service/useradmin/accounts/target/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paused").value(true));
    http.perform(
            put("/service/useradmin/accounts/target/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  @org.springframework.transaction.annotation.Transactional(
      propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  void staleProfileEditsPreserveAPauseCommittedThroughTheAccountHttpBoundary() throws Exception {
    var counsellor = new de.caritas.cob.userservice.api.model.Consultant();
    counsellor.setId("target");
    counsellor.setTenantId(0L);
    counsellor.setUsername(target.getUsername());
    counsellor.setFirstName("Test");
    counsellor.setLastName("Account");
    counsellor.setEmail("target@example.invalid");
    counsellor.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
    counsellor.setEncourage2fa(true);
    counsellor.setNotifyEnquiriesRepeating(true);
    counsellor.setNotifyNewChatMessageFromAdviceSeeker(true);
    counsellor.setWalkThroughEnabled(false);
    counsellor.setMagicLinkLoginEnabled(false);
    counsellor.setStatus(de.caritas.cob.userservice.api.model.ConsultantStatus.CREATED);
    consultants.saveAndFlush(counsellor);
    var staleContext = entityManager.getEntityManagerFactory().createEntityManager();
    try {
      staleContext.getTransaction().begin();
      var staleAdmin = staleContext.find(Admin.class, "target");
      var staleConsultant =
          staleContext.find(de.caritas.cob.userservice.api.model.Consultant.class, "target");
      http.perform(
              put("/service/useradmin/accounts/target/status")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"active\":false}"))
          .andExpect(status().isOk());
      staleAdmin.setFirstName("Edited admin");
      staleConsultant.setFirstName("Edited counsellor");
      staleContext.getTransaction().commit();
      http.perform(get("/service/useradmin/accounts/target/status"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.paused").value(true));
      var freshAdmin = admins.findById("target").orElseThrow();
      var freshConsultant = consultants.findById("target").orElseThrow();
      org.junit.jupiter.api.Assertions.assertTrue(freshAdmin.isManagedPaused());
      org.junit.jupiter.api.Assertions.assertTrue(freshConsultant.isManagedPaused());
      org.junit.jupiter.api.Assertions.assertEquals("Edited admin", freshAdmin.getFirstName());
      org.junit.jupiter.api.Assertions.assertEquals(
          "Edited counsellor", freshConsultant.getFirstName());
    } finally {
      if (staleContext.getTransaction().isActive()) {
        staleContext.getTransaction().rollback();
      }
      staleContext.close();
      consultants.deleteById("target");
      admins.deleteById("target");
      jdbc.update("DELETE FROM account_inactivity WHERE identity_id=?", "target");
    }
  }

  @Test
  void aPausedIdentityCannotUseAnExistingBearerSession() throws Exception {
    http.perform(
            put("/service/useradmin/accounts/target/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isOk());
    entityManager.flush();
    entityManager.clear();
    var jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture-token")
            .header("alg", "RS256")
            .subject("target")
            .build();
    http.perform(
            get("/service/useradmin/accounts/target/status")
                .principal(
                    new org.springframework.security.oauth2.server.resource.authentication
                        .JwtAuthenticationToken(jwt)))
        .andExpect(status().isForbidden());
  }

  @Test
  void pausedTenantDeniesOrdinaryRoutesButKeepsScopedManagementUsable() throws Exception {
    var paused =
        new de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO()
            .id(1L)
            .name("Fixture Träger")
            .active(false)
            .subdomain("fixture");
    when(tenantApi.getRestrictedTenantDataByTenantId(1L)).thenReturn(paused);
    var headers = new de.caritas.cob.userservice.api.service.httpheader.HttpHeadersResolver();
    var agencies = mock(AgencyService.class);
    var resolver =
        new de.caritas.cob.userservice.api.tenant.TenantResolverService(
            new de.caritas.cob.userservice.api.tenant.CustomHeaderTenantResolver(
                new de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier(
                    headers)),
            new de.caritas.cob.userservice.api.tenant.SubdomainTenantResolver(
                new de.caritas.cob.userservice.api.adapters.web.controller.interceptor
                    .SubdomainExtractor(),
                tenants),
            new de.caritas.cob.userservice.api.tenant.TechnicalOrSuperAdminUserTenantResolver(),
            new de.caritas.cob.userservice.api.tenant.AccessTokenTenantResolver(),
            new de.caritas.cob.userservice.api.tenant.MultitenancyWithSingleDomainTenantResolver(
                agencies,
                headers,
                mock(de.caritas.cob.userservice.api.service.ConsultantService.class),
                tenants,
                mock(
                    de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService
                        .class)));
    ReflectionTestUtils.setField(resolver, "multitenancyWithSingleDomain", true);
    var mvc =
        MockMvcBuilders.standaloneSetup(new ManagedAccountStatusController(service))
            .addFilters(
                new de.caritas.cob.userservice.api.adapters.web.controller.interceptor
                    .HttpTenantFilter(resolver, tenants))
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();
    var jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture-token")
            .header("alg", "RS256")
            .subject("observer")
            .claim("tenantId", 1L)
            .build();
    var principal =
        new org.springframework.security.oauth2.server.resource.authentication
            .JwtAuthenticationToken(jwt);
    mvc.perform(get("/service/users/data").principal(principal)).andExpect(status().isForbidden());
    var adminJwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture-admin-token")
            .header("alg", "RS256")
            .subject("observer")
            .claim("tenantId", 1L)
            .claim("realm_access", Map.of("roles", List.of("tenant-admin")))
            .build();
    var adminPrincipal =
        new org.springframework.security.oauth2.server.resource.authentication
            .JwtAuthenticationToken(adminJwt);
    // These routes are owned by other controllers. A 404 here proves admission reached routing.
    mvc.perform(get("/service/users/data").principal(adminPrincipal))
        .andExpect(status().isNotFound());
    mvc.perform(get("/service/users/account-invites/fixture-token/onboarding").principal(principal))
        .andExpect(status().isNotFound());
    mvc.perform(post("/users/password-reset/request").principal(principal))
        .andExpect(status().isNotFound());

    mvc.perform(get("/service/useradmin/accounts/target/status").principal(principal))
        .andExpect(status().isOk());
    for (String alias : List.of("/useradmin", "/service/useradmin")) {
      mvc.perform(
              get("/service" + alias + "/accounts/target/status")
                  .contextPath("/service")
                  .principal(principal))
          .andExpect(status().isOk());
    }
    for (String alias : List.of("/users", "/service/users")) {
      mvc.perform(
              get("/service" + alias + "/data").contextPath("/service").principal(adminPrincipal))
          .andExpect(status().isNotFound());
      mvc.perform(get("/service" + alias + "/data").contextPath("/service").principal(principal))
          .andExpect(status().isForbidden());
      mvc.perform(
              post("/service" + alias + "/password-reset/request")
                  .contextPath("/service")
                  .principal(principal))
          .andExpect(status().isNotFound());
      mvc.perform(
              get("/service" + alias + "/sessions")
                  .contextPath("/service")
                  .principal(adminPrincipal))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void pauseAdmissionReadsFreshCanonicalFlagsWithoutReusingLoadedEntities() throws Exception {
    org.junit.jupiter.api.Assertions.assertFalse(service.isManuallyPaused("target"));
    jdbc.update("UPDATE admin SET managed_paused=true WHERE admin_id=?", "target");
    org.junit.jupiter.api.Assertions.assertFalse(target.isManagedPaused());
    org.junit.jupiter.api.Assertions.assertTrue(service.isManuallyPaused("target"));
    jdbc.update("UPDATE admin SET managed_paused=false WHERE admin_id=?", "target");
    org.junit.jupiter.api.Assertions.assertFalse(service.isManuallyPaused("target"));
    var onlyConsultant = admissionConsultant("consultant-only");
    var mixedConsultant = admissionConsultant("target");
    consultants.saveAndFlush(onlyConsultant);
    consultants.saveAndFlush(mixedConsultant);
    jdbc.update(
        "UPDATE consultant SET managed_paused=true WHERE consultant_id=?", "consultant-only");
    org.junit.jupiter.api.Assertions.assertFalse(onlyConsultant.isManagedPaused());
    org.junit.jupiter.api.Assertions.assertTrue(service.isManuallyPaused("consultant-only"));
    jdbc.update("UPDATE consultant SET managed_paused=true WHERE consultant_id=?", "target");
    org.junit.jupiter.api.Assertions.assertTrue(service.isManuallyPaused("target"));
    jdbc.update("UPDATE consultant SET managed_paused=false WHERE consultant_id=?", "target");
    jdbc.update("UPDATE admin SET managed_paused=true WHERE admin_id=?", "target");
    org.junit.jupiter.api.Assertions.assertTrue(service.isManuallyPaused("target"));
    org.junit.jupiter.api.Assertions.assertFalse(service.isManuallyPaused("no-such-identity"));
    var jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture")
            .header("alg", "RS256")
            .subject("consultant-only")
            .build();
    http.perform(
            get("/service/useradmin/accounts/target/status")
                .principal(
                    new org.springframework.security.oauth2.server.resource.authentication
                        .JwtAuthenticationToken(jwt)))
        .andExpect(status().isForbidden());
  }

  private de.caritas.cob.userservice.api.model.Consultant admissionConsultant(String id) {
    var consultant = new de.caritas.cob.userservice.api.model.Consultant();
    consultant.setId(id);
    consultant.setTenantId(1L);
    consultant.setUsername(
        new de.caritas.cob.userservice.api.helper.UsernameTranscoder().encodeUsername(id));
    consultant.setFirstName("Test");
    consultant.setLastName("Admission");
    consultant.setEmail(id + "@example.invalid");
    consultant.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
    consultant.setEncourage2fa(true);
    consultant.setNotifyEnquiriesRepeating(true);
    consultant.setNotifyNewChatMessageFromAdviceSeeker(true);
    consultant.setWalkThroughEnabled(false);
    consultant.setMagicLinkLoginEnabled(false);
    consultant.setStatus(de.caritas.cob.userservice.api.model.ConsultantStatus.CREATED);
    return consultant;
  }

  @Test
  void unpausedConsultantInSameTenantCannotReadManagedAccountSecurityMetadata() throws Exception {
    target.setTenantId(1L);
    admins.saveAndFlush(target);
    consultants.saveAndFlush(admissionConsultant("reader"));
    // Establish a real existing, internally consistent target with a successful authorized read.
    http.perform(get("/service/useradmin/accounts/target/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountId").value("target"));
    clearInvocations(identities, profiles, factors);
    actor.setUserId("reader");
    actor.setTenantId(1L);
    actor.setRoles(Set.of("consultant"));
    actor.setGrantedAuthorities(Set.of());
    org.junit.jupiter.api.Assertions.assertFalse(service.isManuallyPaused("reader"));
    var jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture-reader")
            .header("alg", "RS256")
            .subject("reader")
            .claim("tenantId", 1L)
            .claim("realm_access", Map.of("roles", List.of("consultant")))
            .build();
    http.perform(
            get("/service/useradmin/accounts/target/status")
                .principal(
                    new org.springframework.security.oauth2.server.resource.authentication
                        .JwtAuthenticationToken(jwt)))
        .andExpect(status().isForbidden());
    verifyNoInteractions(identities, profiles, factors);
  }

  @Test
  void admissionFailureLogsOnlyExceptionClassAndReturnsGeneric502BeforeRouting() throws Exception {
    var databaseFailure = new IllegalStateException("private database credential or identity");
    var failedAdmins =
        mock(
            AdminRepository.class,
            invocation -> {
              throw databaseFailure;
            });
    var failedService =
        new ManagedAccountStatusService(
            actor,
            mock(AdminScope.class),
            failedAdmins,
            consultants,
            identities,
            profiles,
            factors,
            lifecycle,
            tenants);
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(
                de.caritas.cob.userservice.api.adapters.web.controller.interceptor
                    .ManagedAccountPauseAccessFilter.class);
    var events =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    events.start();
    logger.addAppender(events);
    try {
      var mvc =
          MockMvcBuilders.standaloneSetup(new ManagedAccountStatusController(service))
              .addFilters(
                  new de.caritas.cob.userservice.api.adapters.web.controller.interceptor
                      .ManagedAccountPauseAccessFilter(failedService))
              .build();
      var jwt =
          org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture")
              .header("alg", "RS256")
              .subject("private-subject")
              .build();
      mvc.perform(
              get("/service/useradmin/accounts/target/status")
                  .principal(
                      new org.springframework.security.oauth2.server.resource.authentication
                          .JwtAuthenticationToken(jwt)))
          .andExpect(status().isBadGateway());
      org.assertj.core.api.Assertions.assertThat(events.list).hasSize(1);
      org.assertj.core.api.Assertions.assertThat(events.list.getFirst().getFormattedMessage())
          .contains("IllegalStateException")
          .doesNotContain("credential", "private-subject");
      org.assertj.core.api.Assertions.assertThat(events.list.getFirst().getThrowableProxy())
          .isNull();
    } finally {
      logger.detachAppender(events);
      events.stop();
    }
  }

  @Test
  void accountListStatusAgreesWithPersistedManualPause() throws Exception {
    http.perform(
            put("/service/useradmin/accounts/target/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isOk());
    entityManager.flush();
    entityManager.clear();
    var listStatus =
        new de.caritas.cob.userservice.api.admin.service.admin.AccountLoginStatusService(
            identities, service);
    org.assertj.core.api.Assertions.assertThat(listStatus.activeOf("target")).isFalse();
  }

  @Test
  void publicInviteRedeemCannotCreateNewOrdinaryWorkForPausedTenant() throws Exception {
    var paused =
        new de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO()
            .id(1L)
            .name("Fixture Träger")
            .active(false);
    when(tenantApi.getRestrictedTenantDataByTenantId(1L)).thenReturn(paused);
    var link =
        de.caritas.cob.userservice.api.model.AgencyInviteLink.builder()
            .token("fixture-link")
            .tenantId(1L)
            .topicId(2L)
            .consultingTypeId(1)
            .linkKind("TENANT")
            .chatType("LIVE_CHAT")
            .anonymity("FULL")
            .createdByUserId("observer")
            .createDate(LocalDateTime.now())
            .status("ACTIVE")
            .build();
    inviteLinks.saveAndFlush(link);
    var topic = mock(de.caritas.cob.userservice.api.service.consultingtype.TopicService.class);
    var provisioning =
        mock(de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade.class);
    when(provisioning.createAnonymousEnquiry(any(), eq(true)))
        .thenReturn(
            new de.caritas.cob.userservice.api.adapters.web.dto.CreateAnonymousEnquiryResponseDTO()
                .sessionId(99L));
    var links =
        new de.caritas.cob.userservice.api.service.agencyinvitelink.AgencyInviteLinkService(
            inviteLinks,
            actor,
            topic,
            consultants,
            mock(de.caritas.cob.userservice.api.service.ConsultingTypeService.class),
            mock(AgencyService.class),
            provisioning,
            mock(AdminScope.class),
            tenants,
            mock(
                de.caritas.cob.userservice.api.service.temporaryaccount
                    .TemporaryAccountSettingsService.class),
            mock(
                de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkService
                    .class));
    var mvc =
        MockMvcBuilders.standaloneSetup(new AgencyInviteLinkController(links, topic))
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();
    mvc.perform(post("/users/invitelinks/fixture-link/redeem")).andExpect(status().isForbidden());
    verifyNoInteractions(provisioning);
    entityManager.flush();
    entityManager.clear();
    mvc.perform(get("/users/invitelinks/fixture-link/context"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(1L));
  }

  @Test
  void targetSecurityMetadataDoesNotBorrowTheObserversFactorOrExposeEnrollmentMaterial()
      throws Exception {
    http.perform(get("/service/useradmin/accounts/target/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("target-login"))
        .andExpect(jsonPath("$.twoFactor.required").value(true))
        .andExpect(jsonPath("$.twoFactor.configured").value(false))
        .andExpect(jsonPath("$.twoFactor.active").value(false));
    when(factors.getOtpCredential(target.getUsername()))
        .thenReturn(
            new IdentityOtpCredential(
                true,
                "enrollment-secret",
                "enrollment-qr",
                de.caritas.cob.userservice.api.identity.IdentityOtpType.APP));
    http.perform(get("/service/useradmin/accounts/target/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.twoFactor.active").value(true))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("enrollment-secret"))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("enrollment-qr"))));
  }

  @Test
  void independentlyDisabledLoginRefusesActivationAndRetainsSavedPause() throws Exception {
    http.perform(
            put("/service/useradmin/accounts/target/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isOk());
    when(identities.findEnabledById("target")).thenReturn(Optional.of(false));
    http.perform(
            put("/service/useradmin/accounts/target/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.restrictions").value(org.hamcrest.Matchers.hasItem("LOGIN_DISABLED")));
    entityManager.flush();
    entityManager.clear();
    http.perform(get("/service/useradmin/accounts/target/status"))
        .andExpect(jsonPath("$.paused").value(true))
        .andExpect(jsonPath("$.loginEnabled").value(false));
  }

  @Test
  void crossTenantAndMissingWriteAuthorityLeaveSavedStateUnchanged() throws Exception {
    target.setTenantId(1L);
    admins.saveAndFlush(target);
    actor.setTenantId(2L);
    actor.setRoles(Set.of("tenant-admin"));
    actor.setGrantedAuthorities(Set.of(TENANT_ADMIN));
    http.perform(
            put("/service/useradmin/accounts/target/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isForbidden());
    actor.setTenantId(1L);
    actor.setRoles(Set.of("user"));
    actor.setGrantedAuthorities(Set.of(USER_DEFAULT));
    http.perform(
            put("/service/useradmin/accounts/target/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isForbidden());
    actor.setTenantId(0L);
    actor.setRoles(Set.of("agency-admin", "tenant-admin"));
    actor.setGrantedAuthorities(Set.of(TENANT_ADMIN));
    entityManager.flush();
    entityManager.clear();
    http.perform(get("/service/useradmin/accounts/target/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paused").value(false));
  }

  @Test
  void unavailableAuthorityIsUnknownAndCannotReactivate() throws Exception {
    http.perform(
            put("/service/useradmin/accounts/target/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isOk());
    when(identities.findEnabledById("target"))
        .thenThrow(new jakarta.ws.rs.ProcessingException("unavailable"));
    when(factors.getOtpCredential(target.getUsername()))
        .thenThrow(new IllegalStateException("unavailable"));
    http.perform(get("/service/useradmin/accounts/target/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.loginEnabled").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.twoFactor.configured").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.twoFactor.active").value(org.hamcrest.Matchers.nullValue()));
    http.perform(
            put("/service/useradmin/accounts/target/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}"))
        .andExpect(status().isBadGateway());
    entityManager.flush();
    entityManager.clear();
    http.perform(get("/service/useradmin/accounts/target/status"))
        .andExpect(jsonPath("$.paused").value(true));
  }

  @Test
  void mixedIdentityRetainsItsIndependentAbsenceDuringPauseAndRefusedResume() throws Exception {
    var counsellor = new de.caritas.cob.userservice.api.model.Consultant();
    counsellor.setId("target");
    counsellor.setTenantId(0L);
    counsellor.setUsername(target.getUsername());
    counsellor.setFirstName("Test");
    counsellor.setLastName("Account");
    counsellor.setEmail("target@example.invalid");
    counsellor.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
    counsellor.setEncourage2fa(true);
    counsellor.setNotifyEnquiriesRepeating(true);
    counsellor.setNotifyNewChatMessageFromAdviceSeeker(true);
    counsellor.setWalkThroughEnabled(false);
    counsellor.setMagicLinkLoginEnabled(false);
    counsellor.setAbsent(true);
    counsellor.setAbsenceMessage("Fixture absence");
    counsellor.setStatus(de.caritas.cob.userservice.api.model.ConsultantStatus.CREATED);
    consultants.saveAndFlush(counsellor);
    http.perform(
            put("/service/useradmin/accounts/target/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isOk());
    entityManager.flush();
    entityManager.clear();
    http.perform(get("/service/useradmin/accounts/target/status"))
        .andExpect(jsonPath("$.paused").value(true))
        .andExpect(jsonPath("$.absent").value(true));
    http.perform(
            put("/service/useradmin/accounts/target/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.restrictions").value(org.hamcrest.Matchers.hasItem("ABSENT")));
  }

  @Test
  void incompleteProvisioningIsAnIndependentActivationRestriction() throws Exception {
    var counsellor = new de.caritas.cob.userservice.api.model.Consultant();
    counsellor.setId("target");
    counsellor.setTenantId(0L);
    counsellor.setUsername(target.getUsername());
    counsellor.setFirstName("Test");
    counsellor.setLastName("Account");
    counsellor.setEmail("target@example.invalid");
    counsellor.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
    counsellor.setEncourage2fa(true);
    counsellor.setNotifyEnquiriesRepeating(true);
    counsellor.setNotifyNewChatMessageFromAdviceSeeker(true);
    counsellor.setWalkThroughEnabled(false);
    counsellor.setMagicLinkLoginEnabled(false);
    counsellor.setStatus(de.caritas.cob.userservice.api.model.ConsultantStatus.IN_PROGRESS);
    consultants.saveAndFlush(counsellor);
    http.perform(get("/service/useradmin/accounts/target/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lifecycleRestricted").value(true));
    http.perform(
            put("/service/useradmin/accounts/target/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.restrictions")
                .value(org.hamcrest.Matchers.hasItem("LIFECYCLE_RESTRICTED")));
  }

  @Test
  void factorOwnershipCannotBeEstablishedThroughAnotherProvidersProfile() throws Exception {
    when(profiles.findById("target"))
        .thenReturn(
            Optional.of(new IdentityProfile("other-identity", "other-login", null, null, null)));
    http.perform(get("/service/useradmin/accounts/target/status"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.twoFactor.configured").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.twoFactor.active").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void missingActiveFlagIsRejectedBeforeSaving() throws Exception {
    http.perform(
            put("/service/useradmin/accounts/target/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    entityManager.flush();
    entityManager.clear();
    http.perform(get("/service/useradmin/accounts/target/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paused").value(false));
  }

  @Test
  void pausedTenantListReadDeduplicatesFreshTenantLookupsAndPreservesIndividualFlags()
      throws Exception {
    target.setTenantId(1L);
    admins.saveAndFlush(target);
    var sibling =
        Admin.builder()
            .id("sibling")
            .username("sibling-login")
            .firstName("Sibling")
            .lastName("Account")
            .email("sibling@example.invalid")
            .tenantId(1L)
            .type(Admin.AdminType.AGENCY)
            .build();
    admins.saveAndFlush(sibling);
    lifecycle.assignAtCreation("sibling", 1L, 6, 1, Instant.now());
    when(identities.findEnabledById("sibling")).thenReturn(Optional.of(true));
    var paused =
        new de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO()
            .id(1L)
            .name("Fixture Träger")
            .active(false);
    when(tenantApi.getRestrictedTenantDataByTenantId(1L)).thenReturn(paused);
    http.perform(get("/service/useradmin/accounts/target/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false))
        .andExpect(jsonPath("$.paused").value(false))
        .andExpect(jsonPath("$.loginEnabled").value(true))
        .andExpect(jsonPath("$.tenantActive").value(false));
    // A second remote read would fail; both rows must share the page's authoritative tenant read.
    when(tenantApi.getRestrictedTenantDataByTenantId(1L))
        .thenReturn(paused)
        .thenThrow(
            new org.springframework.web.client.RestClientException("one tenant lookup per page"));
    var lists =
        new de.caritas.cob.userservice.api.admin.service.admin.AccountLoginStatusService(
            identities, service);
    org.assertj.core.api.Assertions.assertThat(lists.activeByIds(List.of("target", "sibling")))
        .containsEntry("target", false)
        .containsEntry("sibling", false);
  }
}
