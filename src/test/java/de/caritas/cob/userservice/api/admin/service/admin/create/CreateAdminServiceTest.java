package de.caritas.cob.userservice.api.admin.service.admin.create;

import static de.caritas.cob.userservice.api.config.auth.UserRole.AGENCY_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.UserRole.SINGLE_TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.UserRole.TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.UserRole.TOPIC_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.UserRole.USER_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.ExistingAccountSetupIssuer;
import java.util.List;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CreateAdminServiceTest {
  @Mock
  private de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService
      inactivityEnrollment;

  @Mock
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCreationLocalCompletion
      localCompletion;

  @Mock
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityAccountProvisioning
      identityProvisioning;

  @InjectMocks private CreateAdminService createAdminService;

  @Mock private IdentityClient identityClient;
  @Mock private IdentityPasswordUpdater identityPasswordUpdater;
  @Mock private IdentityAccountRemover identityAccountRemover;

  @Mock private UserAccountInputValidator userAccountInputValidator;

  @Mock private UserHelper userHelper;

  @Mock private AdminRepository adminRepository;

  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private AdminScope adminScope;
  @Mock private ExistingAccountSetupIssuer accountSetupIssuer;

  private final EasyRandom easyRandom = new EasyRandom();

  private final de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy
      inactivityPolicy =
          new de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy(
              24, 7, java.time.Instant.parse("2026-10-06T00:00:00Z"));

  @BeforeEach
  void inactivityPolicy() {
    de.caritas.cob.userservice.api.testHelper.VerifiedCreationCallerFixture.install();
    org.mockito.Mockito.lenient()
        .when(
            inactivityEnrollment.capture(
                org.mockito.ArgumentMatchers.nullable(Long.class),
                any(
                    de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Group
                        .class)))
        .thenReturn(inactivityPolicy);
  }

  @org.junit.jupiter.api.AfterEach
  void clearCaller() {
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
  }

  @Test
  void failedAtomicFinalStepRecordsCompensationBeforeDeletingItsOwnLocalRows() {
    givenKeycloakCreatesUser();
    var input = givenValidCreateAdminDTO(42);
    doThrow(new IllegalStateException("last local transaction failed"))
        .when(localCompletion)
        .admin(any());
    assertThrows(
        InternalServerErrorException.class, () -> createAdminService.createNewTenantAdmin(input));
    var order =
        org.mockito.Mockito.inOrder(identityProvisioning, adminRepository, inactivityEnrollment);
    order.verify(identityProvisioning).compensateForLocalRollback("kc-user-id");
    order.verify(adminRepository).deleteById("kc-user-id");
    order.verify(inactivityEnrollment).discardUncompletedCreation("kc-user-id", inactivityPolicy);
    verifyNoInteractions(accountSetupIssuer);
  }

  @Test
  void capturesAndPersistsTheAdminInactivityPolicy() {
    givenKeycloakCreatesUser();
    var admin = givenValidCreateAdminDTO(42);

    createAdminService.createNewTenantAdmin(admin);

    verify(inactivityEnrollment)
        .capture(
            42L,
            de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Group.OTHER);
    verify(inactivityEnrollment).enroll("kc-user-id", 42L, inactivityPolicy);
  }

  @Test
  void policyCaptureFailureStopsBeforeIdentityCreation() {
    var admin = givenValidCreateAdminDTO(42);
    var failure =
        new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_GATEWAY);
    when(inactivityEnrollment.capture(
            42L,
            de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Group.OTHER))
        .thenThrow(failure);

    assertThat(
            assertThrows(
                RuntimeException.class, () -> createAdminService.createNewTenantAdmin(admin)))
        .isSameAs(failure);
    verifyNoInteractions(identityClient);
  }

  @Test
  void enrollmentValidationFailureDeletesTheAdminRowAndIdentity() {
    givenKeycloakCreatesUser();
    var admin = givenValidCreateAdminDTO(42);
    var failure =
        new CustomValidationHttpStatusException(HttpStatusExceptionReason.USERNAME_NOT_AVAILABLE);
    doThrow(failure).when(inactivityEnrollment).enroll("kc-user-id", 42L, inactivityPolicy);

    assertThat(
            assertThrows(
                RuntimeException.class, () -> createAdminService.createNewTenantAdmin(admin)))
        .isSameAs(failure);
    verify(adminRepository).deleteById("kc-user-id");
    verify(inactivityEnrollment).discardUncompletedCreation("kc-user-id", inactivityPolicy);
    verify(identityProvisioning).compensateForLocalRollback("kc-user-id");
  }

  @Test
  void compensationContinuesWhenDeletingTheAdminRowFails() {
    givenKeycloakCreatesUser();
    var admin = givenValidCreateAdminDTO(42);
    doThrow(new IllegalStateException("policy write failed"))
        .when(inactivityEnrollment)
        .enroll("kc-user-id", 42L, inactivityPolicy);
    doThrow(new IllegalStateException("database unavailable"))
        .when(adminRepository)
        .deleteById("kc-user-id");

    assertThrows(
        InternalServerErrorException.class, () -> createAdminService.createNewTenantAdmin(admin));

    verify(inactivityEnrollment).discardUncompletedCreation("kc-user-id", inactivityPolicy);
    verify(identityProvisioning).compensateForLocalRollback("kc-user-id");
  }

  @Test
  void directlyCreatedAdminsGetTemporaryPasswordsButInvitedAdminsDoNot() {
    givenKeycloakCreatesUser();
    var admin = easyRandom.nextObject(CreateAdminDTO.class);
    admin.setUsername("valid_username");
    admin.setEmail("valid@email.com");
    admin.setPassword("initial-secret");

    createAdminService.createNewTenantAdmin(admin);

    verify(accountSetupIssuer)
        .issueAfterCreation(AccountInviteTargetRole.TENANT_ADMIN, "kc-user-id", "initial-secret");

    createAdminService.createNewTenantAdminFromInvite(
        admin,
        de.caritas.cob.userservice.api.model.AccountInvite.builder()
            .id(1L)
            .tenantId(admin.getTenantId().longValue())
            .purpose(
                de.caritas.cob.userservice.api.service.accountinvite.AccountInvitePurpose.INVITE)
            .targetRole(
                de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole
                    .TENANT_ADMIN)
            .status(
                de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus.EMAIL_SENT)
            .build());

    admin.setPassword("agency-secret");
    createAdminService.createNewAgencyAdmin(admin);

    verify(accountSetupIssuer)
        .issueAfterCreation(AccountInviteTargetRole.AGENCY_ADMIN, "kc-user-id", "agency-secret");

    admin.setTenantId(42);
    createAdminService.createNewAgencyAdminInTenant(
        admin,
        de.caritas.cob.userservice.api.model.AccountInvite.builder()
            .id(1L)
            .tenantId(admin.getTenantId().longValue())
            .purpose(
                de.caritas.cob.userservice.api.service.accountinvite.AccountInvitePurpose.INVITE)
            .targetRole(
                de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole
                    .AGENCY_ADMIN)
            .status(
                de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus.EMAIL_SENT)
            .build());
    var commands =
        org.mockito.ArgumentCaptor.forClass(
            de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands
                .AccountCreation.class);
    verify(identityProvisioning, org.mockito.Mockito.times(4))
        .create(any(), commands.capture(), any());
    assertThat(commands.getAllValues())
        .extracting(command -> command.passwordTemporary())
        .containsExactly(true, false, true, false);
    assertThat(commands.getAllValues())
        .extracting(command -> command.password())
        .containsExactly("initial-secret", "initial-secret", "agency-secret", "agency-secret");
    verifyNoInteractions(identityPasswordUpdater);
  }

  @Test
  void setupMailFailureAfterPersistedAdminDoesNotRollBackOnlyKeycloak() {
    givenKeycloakCreatesUser();
    var admin = givenValidCreateAdminDTO(42);
    admin.setPassword("initial-secret");
    doThrow(new IllegalStateException("setup delivery failed"))
        .when(accountSetupIssuer)
        .issueAfterCreation(AccountInviteTargetRole.TENANT_ADMIN, "kc-user-id", "initial-secret");

    assertThat(
            org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> createAdminService.createNewTenantAdmin(admin)))
        .hasMessage("setup delivery failed");

    verify(adminRepository).saveAndFlush(any(Admin.class));
    verify(identityProvisioning, never()).compensateForLocalRollback(anyString());
  }

  @Test
  void getDefaultRoles_Should_NotAssignLegacySingleTenantAdmin_ForSingleDomainTenantAdmin() {
    ReflectionTestUtils.setField(createAdminService, "multitenancyWithSingleDomain", true);

    List<UserRole> defaultRoles = createAdminService.getDefaultRoles(Admin.AdminType.TENANT);

    assertThat(defaultRoles).containsOnly(USER_ADMIN, AGENCY_ADMIN, TENANT_ADMIN);
    assertThat(defaultRoles).doesNotContain(SINGLE_TENANT_ADMIN);
  }

  @Test
  void getDefaultRoles_Should_NotAssignLegacySingleTenantAdmin_ForMultidomainTenantAdmin() {
    ReflectionTestUtils.setField(createAdminService, "multitenancyWithSingleDomain", false);

    List<UserRole> defaultRoles = createAdminService.getDefaultRoles(Admin.AdminType.TENANT);

    assertThat(defaultRoles).containsOnly(USER_ADMIN, AGENCY_ADMIN, TENANT_ADMIN, TOPIC_ADMIN);
    assertThat(defaultRoles).doesNotContain(SINGLE_TENANT_ADMIN);
  }

  @Test
  void createNewAgencyAdmin_ShouldRollbackUser_WhenRoleAssignmentFails() throws Exception {
    when(identityProvisioning.create(any(), any(), any()))
        .thenThrow(new IllegalStateException("atomic creation failed"));
    assertThrows(
        IllegalStateException.class,
        () -> createAdminService.createNewAgencyAdmin(givenValidCreateAdminDTO(9)));
    verifyNoInteractions(adminRepository);
    verify(identityProvisioning, never()).compensateForLocalRollback(anyString());
  }

  @Test
  void createNewAgencyAdmin_ShouldThrowRoleNotFoundReason_AndRollbackUser_WhenRealmRoleIsMissing()
      throws Exception {
    when(identityProvisioning.create(any(), any(), any()))
        .thenThrow(
            new CustomValidationHttpStatusException(
                HttpStatusExceptionReason.ROLE_NOT_FOUND,
                org.springframework.http.HttpStatus.BAD_REQUEST));
    var exception =
        assertThrows(
            CustomValidationHttpStatusException.class,
            () -> createAdminService.createNewAgencyAdmin(givenValidCreateAdminDTO(9)));
    assertThat(exception.getCustomHttpHeaders().getFirst("X-Reason"))
        .isEqualTo(HttpStatusExceptionReason.ROLE_NOT_FOUND.name());
    verifyNoInteractions(adminRepository);
    verify(identityProvisioning, never()).compensateForLocalRollback(anyString());
  }

  @Test
  void createNewAgencyAdmin_Should_RejectForeignTenantId_WhenCallerIsTenantScoped() {
    // given a tenant admin of tenant 9 trying to create an agency admin for tenant 7
    ReflectionTestUtils.setField(createAdminService, "multiTenancyEnabled", true);
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(true);
    doThrow(new ForbiddenException("out of reach"))
        .when(adminScope)
        .assertMay(AdminScope.Target.tenant(7L));

    CreateAdminDTO createAdminDTO = givenValidCreateAdminDTO(7);

    // when, then
    assertThrows(
        ForbiddenException.class, () -> createAdminService.createNewAgencyAdmin(createAdminDTO));

    verifyNoInteractions(identityClient);
    verifyNoInteractions(adminRepository);
  }

  @Test
  void createNewAgencyAdmin_Should_CheckTheNamedTenantFirst_And_KeepIt_When_ScopeAllows() {
    // given
    ReflectionTestUtils.setField(createAdminService, "multiTenancyEnabled", true);
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(true);
    givenKeycloakCreatesUser();

    CreateAdminDTO createAdminDTO = givenValidCreateAdminDTO(9);

    // when
    Admin admin = createAdminService.createNewAgencyAdmin(createAdminDTO);

    // then
    assertThat(admin.getTenantId()).isEqualTo(9L);
    var order = inOrder(adminScope, identityProvisioning);
    order
        .verify(adminScope, org.mockito.Mockito.atLeastOnce())
        .assertMay(AdminScope.Target.tenant(9L));
    order.verify(identityProvisioning).create(any(), any(), any());
    verify(identityProvisioning, never()).compensateForLocalRollback(anyString());
  }

  private CreateAdminDTO givenValidCreateAdminDTO(Integer tenantId) {
    CreateAdminDTO createAdminDTO = easyRandom.nextObject(CreateAdminDTO.class);
    createAdminDTO.setUsername("valid_username");
    createAdminDTO.setEmail("valid@email.com");
    createAdminDTO.setTenantId(tenantId);
    return createAdminDTO;
  }

  private void givenKeycloakCreatesUser() {
    CreatedIdentity keycloakResponse = new CreatedIdentity();
    keycloakResponse.setUserId("kc-user-id");
    when(identityProvisioning.create(any(), any(), any()))
        .thenReturn(
            new de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands
                .CreationResult(java.util.UUID.randomUUID(), "kc-user-id", "owned-proof", "OPEN"));
    when(adminRepository.saveAndFlush(any(Admin.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }
}
