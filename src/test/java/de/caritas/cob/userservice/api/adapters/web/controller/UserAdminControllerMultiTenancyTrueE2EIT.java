package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.adapters.web.controller.UserAdminControllerIT.AGENCY_ADMIN_PATH;
import static de.caritas.cob.userservice.api.adapters.web.controller.UserAdminControllerIT.TENANT_ADMIN_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.TaskIdentityGrant;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.IdentityConfig;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInvitePurpose;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.testHelper.AccountInactivityPolicyHttpFixture;
import de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures;
import de.caritas.cob.userservice.api.testHelper.ExistingAccountSetupFixtureCleanup;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
@Transactional
class UserAdminControllerMultiTenancyTrueE2EIT extends AccountInactivityPolicyHttpFixture {

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);
  @Autowired private MockMvc mockMvc;

  @Autowired
  private de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService
      lifecycle;

  private final java.util.Map<String, Instant> fixtureLifecycleRows =
      new java.util.LinkedHashMap<>();
  @MockitoBean private org.springframework.security.oauth2.jwt.JwtDecoder taskJwtDecoder;

  @MockitoBean(name = "restTemplate")
  private org.springframework.web.client.RestTemplate taskAuthHttp;

  @MockitoBean private TaskIdentityGrant taskGrants;
  @Autowired private TaskIdentityConfiguration taskIdentities;
  @Autowired private Environment environment;
  private BoundedIdentityHttpFixtures.Provider identityProvider;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean
  @org.springframework.beans.factory.annotation.Qualifier("keycloakRestTemplate")
  private org.springframework.web.client.RestTemplate keycloakRestTemplate;

  @Autowired private IdentityConfig identityConfig;

  @Autowired private AccountInviteRepository accountInvites;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private PlatformTransactionManager transactions;

  @MockitoBean private EmailBrandingResolver branding;

  @MockitoBean private TenantEmailBrandValues brandValues;

  @MockitoBean private OrisoEmailRenderer renderer;

  @MockitoBean private InviteMailDispatchService mail;

  private String createdIdentityId;
  private String cleanupIdentityId;

  @MockitoBean AgencyServiceApiControllerFactory agencyServiceApiControllerFactory;

  @MockitoBean TenantService tenantService;

  @MockitoBean TenantResolverService tenantResolverService;

  @MockitoBean AuthenticatedUser authenticatedUser;

  @MockitoBean SessionTopicEnrichmentService sessionTopicEnrichmentService;

  @AfterEach
  void reset() {
    try {
      ExistingAccountSetupFixtureCleanup.admin(jdbc, transactions, cleanupIdentityId);
    } finally {
      identityConfig.setDisplayNameAllowedForConsultants(false);
      fixtureLifecycleRows.forEach(
          (id, capturedAt) -> lifecycle.discardUncompletedCreation(id, 24, 0, capturedAt));
      fixtureLifecycleRows.clear();
      if (cleanupIdentityId != null)
        new org.springframework.transaction.support.TransactionTemplate(transactions)
            .executeWithoutResult(
                status -> {
                  jdbc.update(
                      "DELETE FROM identity_creation_attempt WHERE account_id = ?",
                      cleanupIdentityId);
                  jdbc.update(
                      "DELETE FROM account_inactivity WHERE identity_id = ?", cleanupIdentityId);
                });
    }
  }

  @BeforeEach
  public void setUp() {

    createdIdentityId = null;
    cleanupIdentityId = null;
    identityProvider =
        BoundedIdentityHttpFixtures.givenProvider(
            keycloakRestTemplate,
            taskGrants,
            taskIdentities,
            environment,
            objectMapper,
            id -> {
              createdIdentityId = id;
              cleanupIdentityId = id;
            });
    BoundedIdentityHttpFixtures.givenTaskGrants(taskAuthHttp, taskJwtDecoder, taskIdentities);
    givenVerifiedHuman(
        "1c80e100-266f-4a02-a3a3-703f236f4a63", 0L, List.of("tenant-admin", "agency-admin"));
    var resolved = new EmailBranding("Test product", null, "#124078", null, null);
    when(branding.resolve(Mockito.nullable(Long.class))).thenReturn(resolved);
    when(brandValues.values(Mockito.eq(resolved), Mockito.nullable(Long.class)))
        .thenReturn(Map.of("platformName", "Test product"));
    when(renderer.render(Mockito.eq("konto-einrichten"), Mockito.any(), Mockito.any()))
        .thenReturn(new OrisoEmailRenderer.RenderedEmail("Setup", "<p>Setup</p>", "Setup"));
    when(mail.sendRendered(Mockito.anyString(), Mockito.any(), Mockito.any()))
        .thenAnswer(
            invocation -> new InviteMailSendReceipt(invocation.getArgument(0), Instant.now()));
  }

  private void givenCurrentSetupIdentity(CreateAdminDTO input, AccountInviteTargetRole role) {
    // The actual bounded creation command supplies the identity and setup-required projection.

  }

  private void assertIssuedSetup(AccountInviteTargetRole role, Long tenantId) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              var invite =
                  accountInvites.findByActiveSetupIdentityKey(createdIdentityId).orElseThrow();
              assertThat(invite.getPurpose())
                  .isEqualTo(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP);
              assertThat(invite.getProvisionedUserId()).isEqualTo(createdIdentityId);
              assertThat(invite.getTargetRole()).isEqualTo(role);
              assertThat(invite.getTenantId()).isEqualTo(tenantId);
            });
    assertThat(identityProvider.commands())
        .anySatisfy(
            command -> {
              assertThat(command.operation()).isEqualTo("account.read");
              assertThat(command.target()).isEqualTo(createdIdentityId);
            });
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void createNewAgencyAdmin_Should_returnOk_When_requiredCreateAgencyAdminIsGiven()
      throws Exception {
    // given
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("agencyadmin@email.com");
    createAdminDTO.setTenantId(95);
    givenCurrentSetupIdentity(createAdminDTO, AccountInviteTargetRole.AGENCY_ADMIN);
    givenTenant();
    givenTenantSuperAdmin();
    givenPlatformAdmin();

    // when

    MvcResult mvcResult =
        this.mockMvc
            .perform(
                post(AGENCY_ADMIN_PATH)
                    .cookie(CSRF_COOKIE)
                    .header(CSRF_HEADER, CSRF_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createAdminDTO)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("_embedded.id", notNullValue()))
            .andExpect(jsonPath("_embedded.username", notNullValue()))
            .andExpect(jsonPath("_embedded.lastname", notNullValue()))
            .andExpect(jsonPath("_embedded.email", is("agencyadmin@email.com")))
            .andExpect(jsonPath("_embedded.tenantId", is("95")))
            .andReturn();
    String content = mvcResult.getResponse().getContentAsString();
    JsonPath.read(content, "_embedded.id");
    assertIssuedSetup(AccountInviteTargetRole.AGENCY_ADMIN, 95L);
    var inactivity =
        jdbc.queryForMap(
            "SELECT tenant_id,assigned_months,revision,status FROM account_inactivity WHERE"
                + " identity_id=?",
            createdIdentityId);
    assertThat(((Number) inactivity.get("TENANT_ID")).longValue()).isEqualTo(95L);
    assertThat(((Number) inactivity.get("ASSIGNED_MONTHS")).intValue()).isEqualTo(24);
    assertThat(((Number) inactivity.get("REVISION")).longValue()).isZero();
    assertThat(inactivity.get("STATUS")).isEqualTo("ACTIVE");
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void createNewAgencyAdmin_Should_return500_When_platformAdminOmitsTargetTenantId()
      throws Exception {
    // given
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("agencyadmin@email.com");
    createAdminDTO.setTenantId(null);
    givenTenant();
    givenTenantSuperAdmin();
    givenPlatformAdmin();

    // when

    this.mockMvc
        .perform(
            post(AGENCY_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isInternalServerError())
        .andReturn();
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void createNewAgencyAdmin_Should_returnForbidden_When_tenantSuperAdminHasNoTenant()
      throws Exception {
    // given a tenant super admin whose token carries no tenant
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("agencyadmin@email.com");
    createAdminDTO.setTenantId(95);
    givenTenant();
    givenTenantSuperAdmin();
    givenCallerBelongsToTenant(null);

    // when, then
    this.mockMvc
        .perform(
            post(AGENCY_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void createNewAgencyAdmin_Should_returnForbidden_When_tenantAdminTargetsForeignTenant()
      throws Exception {
    // given a tenant admin of tenant 95 posting an agency admin for tenant 7
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("agencyadmin@email.com");
    createAdminDTO.setTenantId(7);
    givenTenant();
    givenTenantSuperAdmin();
    givenCallerBelongsToTenant(95L);

    // when, then
    this.mockMvc
        .perform(
            post(AGENCY_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isForbidden());
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void createNewAgencyAdmin_Should_returnOk_When_tenantAdminTargetsOwnTenant() throws Exception {
    // given
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("agencyadmin@email.com");
    createAdminDTO.setTenantId(95);
    givenCurrentSetupIdentity(createAdminDTO, AccountInviteTargetRole.AGENCY_ADMIN);
    givenTenant();
    givenTenantSuperAdmin();
    givenCallerBelongsToTenant(95L);

    // when, then
    this.mockMvc
        .perform(
            post(AGENCY_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("_embedded.tenantId", is("95")));
    assertIssuedSetup(AccountInviteTargetRole.AGENCY_ADMIN, 95L);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void createNewTenantAdmin_Should_returnForbidden_When_tenantAdminTargetsForeignTenant()
      throws Exception {
    // given a tenant admin of tenant 95 posting a tenant admin for tenant 7
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("tenantadmin@email.com");
    createAdminDTO.setTenantId(7);
    givenTenant();
    givenCallerBelongsToTenant(95L);

    // when, then
    this.mockMvc
        .perform(
            post(TENANT_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isForbidden());
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void createNewTenantAdmin_Should_returnOk_When_tenantAdminTargetsOwnTenant() throws Exception {
    // given
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("tenantadmin@email.com");
    createAdminDTO.setTenantId(95);
    givenCurrentSetupIdentity(createAdminDTO, AccountInviteTargetRole.TENANT_ADMIN);
    givenTenant();
    givenCallerBelongsToTenant(95L);

    // when, then
    this.mockMvc
        .perform(
            post(TENANT_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("_embedded.tenantId", is("95")));
    assertIssuedSetup(AccountInviteTargetRole.TENANT_ADMIN, 95L);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void createNewTenantAdmin_Should_returnOk_When_platformAdminCreatesPlatformAdmin()
      throws Exception {
    // given
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("platformadmin@email.com");
    createAdminDTO.setTenantId(0);
    givenCurrentSetupIdentity(createAdminDTO, AccountInviteTargetRole.TENANT_ADMIN);
    givenTenant();
    givenPlatformAdmin();
    when(tenantResolverService.resolve(any())).thenReturn(0L);

    // when, then
    this.mockMvc
        .perform(
            post(TENANT_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("_embedded.tenantId", is("0")));
    assertIssuedSetup(AccountInviteTargetRole.TENANT_ADMIN, 0L);
  }

  // Production only builds a platform admin from tenant 0 plus both super-admin roles: the real
  // predicates run on that state, so a broken rule fails here instead of being stubbed to true.
  private void givenPlatformAdmin() {
    givenCaller(0L, UserRole.AGENCY_ADMIN, UserRole.TENANT_ADMIN);
  }

  private void givenCaller(Long tenantId, UserRole... roles) {
    Mockito.doCallRealMethod().when(authenticatedUser).setRoles(any());
    Mockito.doCallRealMethod().when(authenticatedUser).setTenantId(any());
    authenticatedUser.setRoles(
        Arrays.stream(roles).map(UserRole::getValue).collect(Collectors.toSet()));
    authenticatedUser.setTenantId(tenantId);
    Mockito.doCallRealMethod().when(authenticatedUser).getRoles();
    Mockito.doCallRealMethod().when(authenticatedUser).isAgencySuperAdmin();
    Mockito.doCallRealMethod().when(authenticatedUser).isTenantSuperAdmin();
    Mockito.doCallRealMethod().when(authenticatedUser).isPlatformAdmin();
    when(authenticatedUser.getTenantId()).thenReturn(tenantId);
    givenVerifiedHuman(
        "1c80e100-266f-4a02-a3a3-703f236f4a63",
        tenantId,
        authenticatedUser.getRoles() == null
            ? List.of("tenant-admin")
            : new java.util.ArrayList<>(authenticatedUser.getRoles()));
  }

  private void givenCallerBelongsToTenant(Long tenantId) {
    when(authenticatedUser.getTenantId()).thenReturn(tenantId);
    givenVerifiedHuman(
        "1c80e100-266f-4a02-a3a3-703f236f4a63",
        tenantId,
        authenticatedUser.getRoles() == null
            ? List.of("tenant-admin")
            : new java.util.ArrayList<>(authenticatedUser.getRoles()));
  }

  private void givenTenantSuperAdmin() {
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(true);
  }

  private void givenTenant() {
    when(tenantResolverService.resolve(any())).thenReturn(95L);
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain"));
  }

  private void givenVerifiedHuman(String id, Long tenant, List<String> roles) {
    var previous =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    if (previous == null) return;
    if (lifecycle.snapshot(id).isEmpty()) {
      var capturedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      lifecycle.assignAtCreation(id, tenant, 24, 0, capturedAt);
      fixtureLifecycleRows.put(id, capturedAt);
    }
    var token =
        Jwt.withTokenValue("synthetic-human-session")
            .header("alg", "RS256")
            .subject(id)
            .claim("azp", "app")
            .claim("tenantId", tenant == null ? null : tenant.toString())
            .claim("realm_access", Map.of("roles", roles))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    TestSecurityContextHolder.setAuthentication(
        new JwtAuthenticationToken(token, previous.getAuthorities()));
  }
}
