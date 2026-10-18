package de.caritas.cob.userservice.api.admin.service.admin.create;

import static de.caritas.cob.userservice.api.config.auth.UserRole.RESTRICTED_AGENCY_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.UserRole.TOPIC_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.UserRole.USER_ADMIN;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.EMAIL_NOT_VALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.IdentityAccountStatusLookup;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityEmailAddressUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.IdentityLocaleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordChangeRequirement;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
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
import de.caritas.cob.userservice.api.testHelper.ExistingAccountSetupFixtureCleanup;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CreateAdminServiceIT extends AccountInactivityPolicyHttpFixture {

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

  @MockitoBean(
      extraInterfaces = {
        IdentityAccountRemover.class,
        IdentityAccountStatusLookup.class,
        IdentityAuthentication.class,
        IdentityDeactivator.class,
        IdentityDummyEmailUpdater.class,
        IdentityEmailAddressUpdater.class,
        IdentityEmailOwnerLookup.class,
        IdentityLocaleLookup.class,
        IdentityPasswordChangeRequirement.class,
        IdentityPasswordUpdater.class,
        IdentityProfileLookup.class,
        IdentityProfileUpdater.class,
        IdentityRoleLookup.class,
        IdentityRoleUpdater.class,
        IdentitySecondFactor.class,
        IdentityUsernameAvailability.class
      })
  private IdentityClient identityClient;

  @MockitoBean private AuthenticatedUser authenticatedUser;
  @Captor private ArgumentCaptor<UserDTO> userDTOArgumentCaptor;
  private final EasyRandom easyRandom = new EasyRandom();
  private Object originalMultiTenancyEnabled;
  private Object originalIssuerMultiTenancyEnabled;
  private String cleanupIdentityId;

  // AdminScope is a singleton of the cached context; later classes must see its configured value.
  private Object configuredMultitenancy;

  @BeforeEach
  void setUp() {
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

  private CreatedIdentity createdIdentityForSetup(CreateAdminDTO input, boolean multitenancy) {
    cleanupIdentityId = UUID.randomUUID().toString();
    ReflectionTestUtils.setField(accountSetupIssuer, "multitenancyEnabled", multitenancy);
    when(((IdentityProfileLookup) identityClient).findById(cleanupIdentityId))
        .thenReturn(
            Optional.of(
                new IdentityProfile(
                    cleanupIdentityId,
                    new UsernameTranscoder().encodeUsername(input.getUsername()),
                    null,
                    null,
                    input.getEmail())));
    when(((IdentityRoleLookup) identityClient).findAllByUserId(cleanupIdentityId))
        .thenReturn(List.of("restricted-agency-admin"));
    when(((IdentityPasswordChangeRequirement) identityClient)
            .requiresPasswordChange(cleanupIdentityId))
        .thenReturn(true);
    CreatedIdentity result = new CreatedIdentity();
    result.setUserId(cleanupIdentityId);
    return result;
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
    verify((IdentityPasswordChangeRequirement) identityClient)
        .requiresPasswordChange(cleanupIdentityId);
  }

  @Test
  void
      createNewAdminAgency_Should_returnExpectedCreatedAdmin_When_inputDataIsCorrectAndMultitenancyDisabled() {
    // given
    TenantContext.clear();
    ReflectionTestUtils.setField(createAdminService, "multiTenancyEnabled", false);
    CreateAdminDTO createAdminDTO = this.easyRandom.nextObject(CreateAdminDTO.class);
    createAdminDTO.setUsername(VALID_USERNAME);
    createAdminDTO.setEmail(VALID_EMAIL_ADDRESS);
    CreatedIdentity createdIdentity = createdIdentityForSetup(createAdminDTO, false);
    when(identityClient.createUser(any(), anyString(), any())).thenReturn(createdIdentity);

    // when
    Admin admin = this.createAdminService.createNewAgencyAdmin(createAdminDTO);
    assertIssuedSetup(admin);

    // then
    verify(identityClient).createUser(userDTOArgumentCaptor.capture(), anyString(), anyString());
    assertNull(userDTOArgumentCaptor.getValue().getTenantId());

    verify((IdentityPasswordUpdater) identityClient)
        .updateTemporaryPassword(anyString(), anyString());
    verify(identityClient).updateRole(anyString(), eq(RESTRICTED_AGENCY_ADMIN));
    verify(identityClient).updateRole(anyString(), eq(USER_ADMIN));

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
    when(authenticatedUser.getTenantId()).thenReturn(1L);
    CreateAdminDTO createAdminDTO = this.easyRandom.nextObject(CreateAdminDTO.class);
    createAdminDTO.setUsername(VALID_USERNAME);
    createAdminDTO.setEmail(VALID_EMAIL_ADDRESS);
    CreatedIdentity createdIdentity = createdIdentityForSetup(createAdminDTO, true);
    when(identityClient.createUser(any(), anyString(), any())).thenReturn(createdIdentity);

    // when
    Admin admin = this.createAdminService.createNewAgencyAdmin(createAdminDTO);
    assertIssuedSetup(admin);

    // then
    verify(identityClient).createUser(userDTOArgumentCaptor.capture(), anyString(), anyString());
    assertNotNull(userDTOArgumentCaptor.getValue().getTenantId());
    assertEquals(1L, (long) userDTOArgumentCaptor.getValue().getTenantId());

    verify((IdentityPasswordUpdater) identityClient)
        .updateTemporaryPassword(anyString(), anyString());
    verify(identityClient).updateRole(anyString(), eq(RESTRICTED_AGENCY_ADMIN));
    verify(identityClient).updateRole(anyString(), eq(USER_ADMIN));

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
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(true);
    CreateAdminDTO createAdminDTO = this.easyRandom.nextObject(CreateAdminDTO.class);
    createAdminDTO.setTenantId(1);
    createAdminDTO.setUsername(VALID_USERNAME);
    createAdminDTO.setEmail(VALID_EMAIL_ADDRESS);
    CreatedIdentity createdIdentity = createdIdentityForSetup(createAdminDTO, true);
    when(identityClient.createUser(any(), anyString(), any())).thenReturn(createdIdentity);

    // when
    Admin admin = this.createAdminService.createNewAgencyAdmin(createAdminDTO);
    assertIssuedSetup(admin);

    // then
    verify(identityClient).createUser(userDTOArgumentCaptor.capture(), anyString(), anyString());
    assertNotNull(userDTOArgumentCaptor.getValue().getTenantId());
    assertEquals(1L, (long) userDTOArgumentCaptor.getValue().getTenantId());

    verify((IdentityPasswordUpdater) identityClient)
        .updateTemporaryPassword(anyString(), anyString());
    verify(identityClient).updateRole(anyString(), eq(RESTRICTED_AGENCY_ADMIN));
    verify(identityClient).updateRole(anyString(), eq(USER_ADMIN));

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
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(true);
    CreateAdminDTO createAdminDTO = this.easyRandom.nextObject(CreateAdminDTO.class);
    createAdminDTO.setTenantId(1);
    createAdminDTO.setUsername(VALID_USERNAME);
    createAdminDTO.setEmail(VALID_EMAIL_ADDRESS);
    CreatedIdentity createdIdentity = createdIdentityForSetup(createAdminDTO, false);
    when(identityClient.createUser(any(), anyString(), any())).thenReturn(createdIdentity);

    // when
    Admin admin = this.createAdminService.createNewAgencyAdmin(createAdminDTO);
    assertIssuedSetup(admin);

    // then
    verify(identityClient).createUser(userDTOArgumentCaptor.capture(), anyString(), anyString());
    assertNull(userDTOArgumentCaptor.getValue().getTenantId());

    verify((IdentityPasswordUpdater) identityClient)
        .updateTemporaryPassword(anyString(), anyString());
    verify(identityClient).updateRole(anyString(), eq(RESTRICTED_AGENCY_ADMIN));
    verify(identityClient).updateRole(anyString(), eq(USER_ADMIN));

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
  void
      createNewAdminAgency_Should_throwCustomValidationHttpStatusException_When_keycloakIdIsMissing() {
    TenantContext.setCurrentTenant(1L);
    assertThrows(
        CustomValidationHttpStatusException.class,
        () -> {
          // given
          CreatedIdentity keycloakResponse = easyRandom.nextObject(CreatedIdentity.class);
          keycloakResponse.setUserId(null);
          when(identityClient.createUser(any(), anyString(), any())).thenReturn(keycloakResponse);
          CreateAdminDTO createAgencyAdminDTO = this.easyRandom.nextObject(CreateAdminDTO.class);

          // when
          this.createAdminService.createNewAgencyAdmin(createAgencyAdminDTO);
        });
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
