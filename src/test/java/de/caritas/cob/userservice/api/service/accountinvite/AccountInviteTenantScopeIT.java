package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.ExistingAgencyClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseProcessor;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cross-tenant ("cross-Träger") isolation of the admin invite API, run against a real database.
 *
 * <p>Rules: the platform admin (tenant 0) sees and does everything; a tenant admin only acts in
 * their own tenant and never invites a platform admin; an agency admin (restricted agency admin)
 * only acts on counsellor invites of the agencies they administer.
 *
 * <p>The caller is a real {@link AuthenticatedUser}, not a mock, so the role helpers the scoping
 * relies on ({@code isPlatformAdmin}, {@code hasRestrictedAgencyPriviliges}) run unchanged. The
 * restricted agency admin is the seeded admin {@value #AGENCY_ADMIN_ID}, who administers agency
 * {@value #OWN_AGENCY_ID} only (see {@code database/UserServiceDatabase.sql}).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  AccountInviteService.class,
  AccountInviteAccessPolicy.class,
  AccountInviteTenantScopeIT.CallerConfig.class
})
class AccountInviteTenantScopeIT {

  private static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;
  private static final String AGENCY_ADMIN_ID = "d42c2e5e-143c-4db1-a90f-7cccf82fbb15";
  private static final long OWN_AGENCY_ID = 1L;

  /** Another Beratungsstelle of the same Träger (tenant 1). */
  private static final long FOREIGN_AGENCY_ID = 2L;

  /** A Beratungsstelle of the other Träger (tenant 2). */
  private static final long FOREIGN_TENANT_AGENCY_ID = 3L;

  @TestConfiguration
  static class CallerConfig {
    @Bean
    AuthenticatedUser authenticatedUser() {
      return new AuthenticatedUser();
    }
  }

  @Autowired private AccountInviteService service;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private AuthenticatedUser caller;

  @MockitoBean private IdentityEmailOwnerLookup identityEmailOwnerLookup;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private TenantIdAllocationClient tenantIdAllocationClient;
  @MockitoBean private AgencyIdAllocationClient agencyIdAllocationClient;
  @MockitoBean private ExistingAgencyClient existingAgencyClient;
  @MockitoBean private IdReservationReleaseProcessor reservationReleaseProcessor;
  @MockitoBean private InviteAcceptUrlBuilder inviteAcceptUrlBuilder;
  @MockitoBean private InviteMailDispatchService inviteMailDispatchService;
  @MockitoBean private InviteEmailDeliveryFailureRecorder deliveryFailureRecorder;
  @MockitoBean private AgencyService agencyService;

  private AccountInvite ownTenantCounsellorInvite;
  private AccountInvite foreignTenantCounsellorInvite;
  private AccountInvite foreignAgencyCounsellorInvite;
  private AccountInvite ownAgencyAgencyAdminInvite;

  @BeforeEach
  void seedInvitesOfTwoTenants() {
    when(identityEmailOwnerLookup.findByEmail(anyString())).thenReturn(Optional.empty());
    givenAgency(OWN_AGENCY_ID, OWN_TENANT);
    givenAgency(FOREIGN_AGENCY_ID, OWN_TENANT);
    givenAgency(FOREIGN_TENANT_AGENCY_ID, FOREIGN_TENANT);
    ownTenantCounsellorInvite =
        persistInvite(AccountInviteTargetRole.COUNSELLOR, OWN_TENANT, OWN_AGENCY_ID, "own@a.org");
    foreignAgencyCounsellorInvite =
        persistInvite(
            AccountInviteTargetRole.COUNSELLOR, OWN_TENANT, FOREIGN_AGENCY_ID, "other@a.org");
    ownAgencyAgencyAdminInvite =
        persistInvite(
            AccountInviteTargetRole.AGENCY_ADMIN, OWN_TENANT, OWN_AGENCY_ID, "admin@a.org");
    foreignTenantCounsellorInvite =
        persistInvite(
            AccountInviteTargetRole.COUNSELLOR,
            FOREIGN_TENANT,
            FOREIGN_TENANT_AGENCY_ID,
            "x@b.org");
  }

  @AfterEach
  void cleanUp() {
    accountInviteRepository.deleteAll();
  }

  // --- Träger admin (tenant admin of tenant 1) ---------------------------------------------

  @Test
  void listInvites_Should_ReturnOnlyOwnTenant_When_TenantAdminListsWithoutTenantFilter() {
    actAsTenantAdmin();

    var page = service.listInvites(null, null, null, null, 0, 50);

    assertThat(page.getContent())
        .extracting(AccountInvite::getTenantId)
        .isNotEmpty()
        .containsOnly(OWN_TENANT);
  }

  @Test
  void listInvites_Should_Refuse_When_TenantAdminAsksForAnotherTenant() {
    actAsTenantAdmin();

    assertThatThrownBy(() -> service.listInvites(null, null, FOREIGN_TENANT, null, 0, 50))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void listInvites_Should_NotLeakForeignTenant_When_TenantAdminSearchesForItsNumber() {
    actAsTenantAdmin();

    // The query box also matches a numeric term against the tenant ID (#479).
    var page = service.listInvites(null, null, null, String.valueOf(FOREIGN_TENANT), 0, 50);

    // Nothing of tenant 1 matches "2"; before the fix the tenant-2 invite came back.
    assertThat(page.getContent())
        .extracting(AccountInvite::getTenantId)
        .doesNotContain(FOREIGN_TENANT);
  }

  @Test
  void createInvite_Should_Refuse_When_TenantAdminInvitesIntoAnotherTenant() {
    actAsTenantAdmin();

    assertThatThrownBy(
            () ->
                service.createInvite(
                    invite(AccountInviteTargetRole.COUNSELLOR, FOREIGN_TENANT, FOREIGN_AGENCY_ID)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void createInvite_Should_Refuse_When_TenantAdminNamesAnAgencyOfAnotherTenant() {
    actAsTenantAdmin();

    assertThatThrownBy(
            () ->
                service.createInvite(
                    invite(
                        AccountInviteTargetRole.COUNSELLOR, OWN_TENANT, FOREIGN_TENANT_AGENCY_ID)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void createInvite_Should_Refuse_When_TenantAdminInvitesAPlatformAdmin() {
    actAsTenantAdmin();

    assertThatThrownBy(
            () -> service.createInvite(invite(AccountInviteTargetRole.PLATFORM_ADMIN, 0L, null)))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () ->
                service.createInvite(
                    invite(AccountInviteTargetRole.PLATFORM_ADMIN, OWN_TENANT, null)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void createInvite_Should_Refuse_When_TenantAdminLetsTheServerAllocateANewTenant() {
    actAsTenantAdmin();

    // AUTO allocation reserves a brand-new tenant ID — onboarding a new Träger is the platform's.
    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.TENANT_ADMIN,
            null,
            "new-traeger@c.org",
            null,
            null,
            null,
            null,
            null,
            IdAllocationMode.AUTO,
            null);

    assertThatThrownBy(() -> service.createInvite(command)).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void createInvite_Should_StampOwnTenant_When_TenantAdminOmitsTheTenant() {
    actAsTenantAdmin();

    AccountInvite created =
        service.createInvite(invite(AccountInviteTargetRole.COUNSELLOR, null, OWN_AGENCY_ID));

    assertThat(created.getTenantId()).isEqualTo(OWN_TENANT);
  }

  @Test
  void createInvite_Should_Succeed_When_TenantAdminInvitesIntoOwnTenant() {
    actAsTenantAdmin();

    AccountInvite created =
        // ORISO-Admin#1026 slice 3: an agency-admin invite always names its agency.
        service.createInvite(
            invite(AccountInviteTargetRole.AGENCY_ADMIN, OWN_TENANT, OWN_AGENCY_ID));

    assertThat(created.getId()).isNotNull();
    assertThat(created.getTenantId()).isEqualTo(OWN_TENANT);
  }

  @Test
  void revokeInvite_Should_Refuse_When_TenantAdminTouchesAnotherTenantsInvite() {
    actAsTenantAdmin();
    Long foreignId = foreignTenantCounsellorInvite.getId();

    assertThatThrownBy(() -> service.revokeInvite(foreignId))
        .isInstanceOf(ForbiddenException.class);
    assertThat(accountInviteRepository.findById(foreignId).orElseThrow().getStatus())
        .isEqualTo(AccountInviteStatus.EMAIL_SENT);
  }

  @Test
  void waiveTwoFactor_Should_Refuse_When_TenantAdminTouchesAnotherTenantsInvite() {
    actAsTenantAdmin();
    Long foreignId = foreignTenantCounsellorInvite.getId();

    assertThatThrownBy(
            () ->
                service.waiveTwoFactor(
                    foreignId, new AccountInviteService.WaiveTwoFactorCommand("because")))
        .isInstanceOf(ForbiddenException.class);
  }

  // --- Beratungsstellen admin (restricted agency admin of agency 1) ---------------------------

  @Test
  void listInvites_Should_ReturnOnlyOwnAgencyCounsellors_When_AgencyAdminLists() {
    actAsAgencyAdmin();

    var page = service.listInvites(null, null, null, null, 0, 50);

    assertThat(page.getContent())
        .extracting(AccountInvite::getId)
        .containsExactly(ownTenantCounsellorInvite.getId());
  }

  @Test
  void createInvite_Should_Refuse_When_AgencyAdminInvitesIntoAForeignAgency() {
    actAsAgencyAdmin();

    assertThatThrownBy(
            () ->
                service.createInvite(
                    invite(AccountInviteTargetRole.COUNSELLOR, OWN_TENANT, FOREIGN_AGENCY_ID)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void createInvite_Should_Refuse_When_AgencyAdminInvitesAHigherRole() {
    actAsAgencyAdmin();

    assertThatThrownBy(
            () ->
                service.createInvite(
                    invite(AccountInviteTargetRole.AGENCY_ADMIN, OWN_TENANT, OWN_AGENCY_ID)))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () ->
                service.createInvite(
                    invite(AccountInviteTargetRole.TENANT_ADMIN, OWN_TENANT, OWN_AGENCY_ID)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void createInvite_Should_Refuse_When_AgencyAdminNamesNoAgency() {
    actAsAgencyAdmin();

    assertThatThrownBy(
            () ->
                service.createInvite(invite(AccountInviteTargetRole.COUNSELLOR, OWN_TENANT, null)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void createInvite_Should_Succeed_When_AgencyAdminInvitesACounsellorIntoOwnAgency() {
    actAsAgencyAdmin();

    AccountInvite created =
        service.createInvite(invite(AccountInviteTargetRole.COUNSELLOR, null, OWN_AGENCY_ID));

    assertThat(created.getAgencyId()).isEqualTo(OWN_AGENCY_ID);
    assertThat(created.getTenantId()).isEqualTo(OWN_TENANT);
  }

  @Test
  void revokeInvite_Should_Refuse_When_AgencyAdminTouchesAnInviteOutsideItsScope() {
    actAsAgencyAdmin();

    assertThatThrownBy(() -> service.revokeInvite(foreignAgencyCounsellorInvite.getId()))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.revokeInvite(ownAgencyAgencyAdminInvite.getId()))
        .isInstanceOf(ForbiddenException.class);
  }

  // --- Platform admin (tenant 0) ---------------------------------------------------------------

  @Test
  void listInvites_Should_ReturnEveryTenant_When_PlatformAdminLists() {
    actAsPlatformAdmin();

    var page = service.listInvites(null, null, null, null, 0, 50);

    assertThat(page.getContent())
        .extracting(AccountInvite::getTenantId)
        .contains(OWN_TENANT, FOREIGN_TENANT);
  }

  @Test
  void createAndRevoke_Should_Succeed_When_PlatformAdminActsInAnyTenant() {
    actAsPlatformAdmin();

    AccountInvite created =
        service.createInvite(
            invite(AccountInviteTargetRole.COUNSELLOR, FOREIGN_TENANT, FOREIGN_TENANT_AGENCY_ID));
    AccountInvite revoked = service.revokeInvite(foreignTenantCounsellorInvite.getId());

    assertThat(created.getTenantId()).isEqualTo(FOREIGN_TENANT);
    assertThat(revoked.getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private void actAsTenantAdmin() {
    actAs(
        "tenant-admin-1",
        OWN_TENANT,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  private void actAsAgencyAdmin() {
    actAs(AGENCY_ADMIN_ID, OWN_TENANT, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
  }

  private void actAsPlatformAdmin() {
    actAs("platform-admin", 0L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);
  }

  private void actAs(String userId, Long tenantId, UserRole... roles) {
    caller.setUserId(userId);
    caller.setUsername(userId);
    caller.setTenantId(tenantId);
    caller.setRoles(
        java.util.Arrays.stream(roles)
            .map(UserRole::getValue)
            .collect(java.util.stream.Collectors.toSet()));
    caller.setGrantedAuthorities(Set.of());
  }

  private void givenAgency(long agencyId, long tenantId) {
    when(agencyService.getAgencyWithoutCaching(agencyId))
        .thenReturn(new AgencyDTO().id(agencyId).tenantId(tenantId));
  }

  private static CreateAccountInviteCommand invite(
      AccountInviteTargetRole role, Long tenantId, Long agencyId) {
    return new CreateAccountInviteCommand(
        role,
        tenantId,
        "new-" + role.name().toLowerCase() + "-" + tenantId + "-" + agencyId + "@example.org",
        "Ada",
        "Lovelace",
        agencyId,
        null,
        null);
  }

  private AccountInvite persistInvite(
      AccountInviteTargetRole role, Long tenantId, Long agencyId, String email) {
    LocalDateTime now = LocalDateTime.now();
    return accountInviteRepository.save(
        AccountInvite.builder()
            .targetRole(role)
            .tenantId(tenantId)
            .agencyId(agencyId)
            .recipientEmail(email)
            .status(AccountInviteStatus.EMAIL_SENT)
            .emailVerificationStatus(EmailVerificationStatus.PENDING)
            .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
            .expiresAt(now.plusDays(30))
            .createDate(now)
            .updateDate(now)
            .build());
  }
}
