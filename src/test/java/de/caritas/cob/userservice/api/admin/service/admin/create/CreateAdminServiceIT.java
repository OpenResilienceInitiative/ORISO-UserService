package de.caritas.cob.userservice.api.admin.service.admin.create;

import static de.caritas.cob.userservice.api.config.auth.UserRole.TOPIC_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.UserRole.USER_ADMIN;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.EMAIL_NOT_VALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.TaskIdentityGrant;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInvitePurpose;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.ExistingAccountSetupIssuer;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.testHelper.AccountInactivityPolicyHttpFixture;
import de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures;
import de.caritas.cob.userservice.api.testHelper.ExistingAccountSetupFixtureCleanup;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@SpringBootTest(classes = UserServiceApplication.class)
@org.springframework.context.annotation.Import(
    de.caritas.cob.userservice.api.testHelper.VerifiedRequestCallerFixture.class)
@TestPropertySource(properties = "spring.profiles.active=testing,verified-request-caller")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CreateAdminServiceIT extends AccountInactivityPolicyHttpFixture {

  @MockitoBean(name = "keycloakRestTemplate")
  private RestTemplate boundedIdentityHttp;

  @MockitoBean private TaskIdentityGrant taskIdentityGrant;
  @Autowired private TaskIdentityConfiguration taskIdentities;
  @Autowired private Environment identityEnvironment;
  @Autowired private ObjectMapper identityMapper;
  private BoundedIdentityHttpFixtures.Provider nativeAccounts;

  private void givenBoundedAccounts() {
    nativeAccounts =
        BoundedIdentityHttpFixtures.givenProvider(
            boundedIdentityHttp,
            taskIdentityGrant,
            taskIdentities,
            identityEnvironment,
            identityMapper,
            id -> cleanupIdentityId = id);
    givenHuman("7ad454de-cf29-4557-b8b3-1bf986524de2", 1L, List.of("user-admin", "tenant-admin"));
  }

  private void givenHuman(String id, Long tenant, List<String> roles) {
    var token =
        Jwt.withTokenValue("verified-creator-session")
            .header("alg", "RS256")
            .subject(id)
            .claim("azp", "admin")
            .claim("preferred_username", "apau1")
            .claim("tenantId", tenant == null ? null : tenant.toString())
            .claim("realm_access", Map.of("roles", roles))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    var authentication =
        new JwtAuthenticationToken(
            token,
            List.of(
                    "AUTHORIZATION_USER_ADMIN",
                    "AUTHORIZATION_TENANT_ADMIN",
                    "AUTHORIZATION_CONSULTANT_CREATE")
                .stream()
                .map(SimpleGrantedAuthority::new)
                .toList());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    var request = new MockHttpServletRequest();
    request.setUserPrincipal(authentication);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  @org.junit.jupiter.api.AfterEach
  void clearBoundedCaller() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  private static final String VALID_USERNAME = "validUsername";
  private static final String VALID_EMAIL_ADDRESS = "valid@emailaddress.de";

  @Autowired private CreateAdminService createAdminService;
  @Autowired private AdminScope adminScope;
  @Autowired private ExistingAccountSetupIssuer accountSetupIssuer;
  @Autowired private AccountInviteRepository accountInvites;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactions;

  @MockitoBean private EmailBrandingResolver branding;
  @MockitoBean private TenantEmailBrandValues brandValues;
  @MockitoBean private OrisoEmailRenderer renderer;
  @MockitoBean private InviteMailDispatchService setupMail;

  private final EasyRandom easyRandom = new EasyRandom();
  private Object originalMultiTenancyEnabled;
  private Object originalIssuerMultiTenancyEnabled;
  private String cleanupIdentityId;

  // AdminScope is a singleton of the cached context; later classes must see its configured value.
  private Object configuredMultitenancy;

  @BeforeEach
  void setUp() {
    givenBoundedAccounts();
    MockitoAnnotations.openMocks(this);
    configuredMultitenancy = ReflectionTestUtils.getField(adminScope, "multitenancyEnabled");
    originalMultiTenancyEnabled =
        ReflectionTestUtils.getField(createAdminService, "multiTenancyEnabled");
    originalIssuerMultiTenancyEnabled =
        ReflectionTestUtils.getField(accountSetupIssuer, "multitenancyEnabled");
    var resolved = new EmailBranding("Test product", null, "#124078", null, null);
    when(branding.resolve(org.mockito.Mockito.nullable(Long.class))).thenReturn(resolved);
    when(brandValues.values(
            org.mockito.Mockito.eq(resolved), org.mockito.Mockito.nullable(Long.class)))
        .thenReturn(Map.of("platformName", "Test product"));
    when(renderer.render(org.mockito.Mockito.eq("konto-einrichten"), any(), any()))
        .thenReturn(new OrisoEmailRenderer.RenderedEmail("Setup", "<p>Setup</p>", "Setup"));
    when(setupMail.sendRendered(anyString(), any(), any()))
        .thenAnswer(
            invocation -> new InviteMailSendReceipt(invocation.getArgument(0), Instant.now()));
  }

  @AfterEach
  void afterTests() {
    try {
      ExistingAccountSetupFixtureCleanup.admin(jdbc, transactions, cleanupIdentityId);
    } finally {
      TenantContext.clear();
      // These services are shared context beans; a leaked flag changes later tenant handling.
      ReflectionTestUtils.setField(
          createAdminService, "multiTenancyEnabled", originalMultiTenancyEnabled);
      ReflectionTestUtils.setField(
          accountSetupIssuer, "multitenancyEnabled", originalIssuerMultiTenancyEnabled);
      ReflectionTestUtils.setField(adminScope, "multitenancyEnabled", configuredMultitenancy);
    }
  }

  private void prepareSetup(boolean multitenancy) {
    ReflectionTestUtils.setField(accountSetupIssuer, "multitenancyEnabled", multitenancy);
  }

  private void assertIssuedSetup(Admin admin) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              var invite =
                  accountInvites.findByActiveSetupIdentityKey(cleanupIdentityId).orElseThrow();
              assertThat(invite.getPurpose())
                  .isEqualTo(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP);
              assertThat(invite.getTargetRole()).isEqualTo(AccountInviteTargetRole.AGENCY_ADMIN);
              assertThat(invite.getProvisionedUserId()).isEqualTo(admin.getId());
              assertThat(invite.getTenantId()).isEqualTo(admin.getTenantId());
            });
    assertThat(nativeAccounts.projections().get(admin.getId()).passwordChangeRequired()).isTrue();
    assertThat(nativeAccounts.projections().get(admin.getId()).roles())
        .containsExactlyInAnyOrder("restricted-agency-admin", "user-admin");
  }

  @Test
  void
      createNewAdminAgency_Should_returnExpectedCreatedAdmin_When_inputDataIsCorrectAndMultitenancyDisabled() {
    // given
    TenantContext.clear();
    ReflectionTestUtils.setField(createAdminService, "multiTenancyEnabled", false);
    CreateAdminDTO createAdminDTO = this.easyRandom.nextObject(CreateAdminDTO.class);
    createAdminDTO.setUsername(
        VALID_USERNAME + java.util.UUID.randomUUID().toString().substring(0, 8));
    createAdminDTO.setEmail(VALID_EMAIL_ADDRESS);
    prepareSetup(false);

    // when
    Admin admin = this.createAdminService.createNewAgencyAdmin(createAdminDTO);
    assertIssuedSetup(admin);

    // then
    assertThat(nativeAccounts.projections().get(admin.getId()).tenantId()).isNull();

    assertThat(nativeAccounts.commands())
        .extracting(BoundedIdentityHttpFixtures.Command::operation)
        .contains("account.create", "account.commit");

    assertThat(admin).isNotNull();
    assertThat(admin.getTenantId()).isNull();
    assertThat(admin.getId()).isNotNull();
    assertDefaultInactivityPolicy(admin.getId());
    assertThat(admin.getType()).isEqualTo(AdminType.AGENCY);
    assertThat(admin.getUsername()).isNotNull();
    assertThat(admin.getFirstName()).isNotNull();
    assertThat(admin.getLastName()).isNotNull();
    assertThat(admin.getEmail()).isNotNull();
    assertThat(admin.getCreateDate()).isNotNull();
    assertThat(admin.getUpdateDate()).isNotNull();
  }

  @Test
  void
      createNewAdminAgency_Should_returnExpectedCreatedAdmin_When_inputDataIsCorrectAndMultitenancyEnabled() {
    // given
    ReflectionTestUtils.setField(createAdminService, "multiTenancyEnabled", true);
    ReflectionTestUtils.setField(adminScope, "multitenancyEnabled", true);
    TenantContext.setCurrentTenant(1L);

    CreateAdminDTO createAdminDTO = this.easyRandom.nextObject(CreateAdminDTO.class);
    createAdminDTO.setUsername(
        VALID_USERNAME + java.util.UUID.randomUUID().toString().substring(0, 8));
    createAdminDTO.setEmail(VALID_EMAIL_ADDRESS);
    createAdminDTO.setTenantId(1);
    prepareSetup(true);

    // when
    Admin admin = this.createAdminService.createNewAgencyAdmin(createAdminDTO);
    assertIssuedSetup(admin);

    // then
    assertThat(nativeAccounts.projections().get(admin.getId()).tenantId()).isEqualTo(1L);

    assertThat(nativeAccounts.commands())
        .extracting(BoundedIdentityHttpFixtures.Command::operation)
        .contains("account.create", "account.commit");

    assertThat(admin).isNotNull();
    assertThat(admin.getTenantId()).isEqualTo(1L);
    assertThat(admin.getId()).isNotNull();
    assertThat(admin.getType()).isEqualTo(AdminType.AGENCY);
    assertThat(admin.getUsername()).isNotNull();
    assertThat(admin.getFirstName()).isNotNull();
    assertThat(admin.getLastName()).isNotNull();
    assertThat(admin.getEmail()).isNotNull();
    assertThat(admin.getCreateDate()).isNotNull();
    assertThat(admin.getUpdateDate()).isNotNull();
  }

  @Test
  public void
      createNewAdminAgency_Should_returnExpectedCreatedAdmin_When_userIsSuperAdminAndInputDataIsCorrectAndMultitenancyEnabled() {
    // given
    ReflectionTestUtils.setField(createAdminService, "multiTenancyEnabled", true);
    TenantContext.setCurrentTenant(0L);
    givenHuman(
        "7b1e15fe-4039-4ce7-a078-05996ff06676",
        0L,
        List.of("user-admin", "tenant-admin", "agency-admin"));
    CreateAdminDTO createAdminDTO = this.easyRandom.nextObject(CreateAdminDTO.class);
    createAdminDTO.setTenantId(1);
    createAdminDTO.setUsername(
        VALID_USERNAME + java.util.UUID.randomUUID().toString().substring(0, 8));
    createAdminDTO.setEmail(VALID_EMAIL_ADDRESS);
    prepareSetup(true);

    // when
    Admin admin = this.createAdminService.createNewAgencyAdmin(createAdminDTO);
    assertIssuedSetup(admin);

    // then
    assertThat(nativeAccounts.projections().get(admin.getId()).tenantId()).isEqualTo(1L);

    assertThat(nativeAccounts.commands())
        .extracting(BoundedIdentityHttpFixtures.Command::operation)
        .contains("account.create", "account.commit");

    assertThat(admin).isNotNull();
    assertThat(admin.getTenantId()).isEqualTo(1L);
    assertThat(admin.getId()).isNotNull();
  }

  @Test
  public void
      createNewAdminAgency_Should_returnExpectedCreatedAdmin_When_userIsSuperAdminAndInputDataIsCorrectAndMultitenancyDisabled() {
    // given
    ReflectionTestUtils.setField(createAdminService, "multiTenancyEnabled", false);
    TenantContext.setCurrentTenant(0L);
    givenHuman(
        "7b1e15fe-4039-4ce7-a078-05996ff06676",
        0L,
        List.of("user-admin", "tenant-admin", "agency-admin"));
    CreateAdminDTO createAdminDTO = this.easyRandom.nextObject(CreateAdminDTO.class);
    createAdminDTO.setTenantId(1);
    createAdminDTO.setUsername(
        VALID_USERNAME + java.util.UUID.randomUUID().toString().substring(0, 8));
    createAdminDTO.setEmail(VALID_EMAIL_ADDRESS);
    prepareSetup(false);

    // when
    Admin admin = this.createAdminService.createNewAgencyAdmin(createAdminDTO);
    assertIssuedSetup(admin);

    // then
    assertThat(nativeAccounts.projections().get(admin.getId()).tenantId()).isNull();

    assertThat(nativeAccounts.commands())
        .extracting(BoundedIdentityHttpFixtures.Command::operation)
        .contains("account.create", "account.commit");

    assertThat(admin).isNotNull();
    assertThat(admin.getTenantId()).isNull();
    assertThat(admin.getId()).isNotNull();
  }

  @Test
  void getUserRolesForTenantAdmin_ShouldGetProperDefaultRoles_ForSingleDomainMultitenancy() {
    ReflectionTestUtils.setField(createAdminService, "multitenancyWithSingleDomain", true);
    List<UserRole> defaultRoles = this.createAdminService.getDefaultRoles(AdminType.TENANT);

    assertThat(defaultRoles).containsOnly(UserRole.AGENCY_ADMIN, UserRole.TENANT_ADMIN, USER_ADMIN);
    assertThat(defaultRoles).doesNotContain(UserRole.SINGLE_TENANT_ADMIN);
  }

  @Test
  void getUserRolesForTenantAdmin_ShouldGetProperDefaultRoles_ForMultidomainMultitenancy() {
    ReflectionTestUtils.setField(createAdminService, "multitenancyWithSingleDomain", false);
    List<UserRole> defaultRoles = this.createAdminService.getDefaultRoles(AdminType.TENANT);

    assertThat(defaultRoles)
        .containsOnly(UserRole.AGENCY_ADMIN, UserRole.TENANT_ADMIN, USER_ADMIN, TOPIC_ADMIN);
    assertThat(defaultRoles).doesNotContain(UserRole.SINGLE_TENANT_ADMIN);
  }

  @Test
  void createNewAdminAgency_Should_rejectMissingProviderReceipt() {
    org.mockito.Mockito.doAnswer(
            invocation -> {
              String endpoint = invocation.getArgument(0);
              var attempt = UUID.fromString(endpoint.substring(endpoint.lastIndexOf('/') + 1));
              return org.springframework.http.ResponseEntity.ok(
                  new KeycloakTaskCommands.CreationResult(attempt, null, "receipt", "OPEN"));
            })
        .when(boundedIdentityHttp)
        .exchange(
            org.mockito.ArgumentMatchers.contains("/account-creations/"),
            eq(org.springframework.http.HttpMethod.PUT),
            any(org.springframework.http.HttpEntity.class),
            eq(KeycloakTaskCommands.CreationResult.class));
    var input = easyRandom.nextObject(CreateAdminDTO.class);
    input.setUsername(VALID_USERNAME + java.util.UUID.randomUUID().toString().substring(0, 8));
    input.setEmail(VALID_EMAIL_ADDRESS);
    var failure =
        assertThrows(
            IllegalStateException.class, () -> createAdminService.createNewAgencyAdmin(input));
    assertThat(failure).hasMessage("Identity provider returned no creation receipt");
    assertThat(nativeAccounts.commands())
        .noneMatch(command -> command.operation().equals("account.commit"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin WHERE username=?", Integer.class, input.getUsername()))
        .isZero();
  }

  @Test
  void createNewAdminAgency_Should_throwExpectedException_When_emailIsInvalid() {
    // given
    CreateAdminDTO createAgencyAdminDTO = this.easyRandom.nextObject(CreateAdminDTO.class);
    createAgencyAdminDTO.setEmail("invalid");
    TenantContext.setCurrentTenant(1L);

    try {

      // when
      this.createAdminService.createNewAgencyAdmin(createAgencyAdminDTO);
      fail("Exception should be thrown");

      // then
    } catch (CustomValidationHttpStatusException e) {
      assertThat(e.getCustomHttpHeaders()).isNotNull();
      assertThat(e.getCustomHttpHeaders().get("X-Reason").get(0)).isEqualTo(EMAIL_NOT_VALID.name());
    }
  }
}
