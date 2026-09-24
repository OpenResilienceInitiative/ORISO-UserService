package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.ExistingAgencyClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.ExistingAgencyClient.ExistingAgency;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseProcessor;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  AccountInviteService.class,
  AccountInviteTopicPermissionService.class,
  AccountInviteAccessPolicy.class,
  AccountInviteRoleRuleIT.CallerConfig.class
})
class AccountInviteRoleRuleIT {

  private static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;

  /** Seeded restricted agency admin who administers agency 1 (tenant 1) only. */
  private static final String AGENCY_ADMIN_ID = "d42c2e5e-143c-4db1-a90f-7cccf82fbb15";

  private static final long OWN_AGENCY = 1L;
  private static final long OTHER_OWN_TENANT_AGENCY = 2L;
  private static final long FOREIGN_AGENCY = 3L;

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
  @MockitoBean private AgencyTopicPermissionLookup agencyTopicPermissionLookup;
  @MockitoBean private IdReservationReleaseProcessor reservationReleaseProcessor;
  @MockitoBean private InviteAcceptUrlBuilder inviteAcceptUrlBuilder;
  @MockitoBean private InviteMailDispatchService inviteMailDispatchService;
  @MockitoBean private InviteEmailDeliveryFailureRecorder deliveryFailureRecorder;
  @MockitoBean private AgencyService agencyService;

  @BeforeEach
  void givenTenantsAndAgencies() {
    when(identityEmailOwnerLookup.findByEmail(anyString())).thenReturn(Optional.empty());
    when(tenantService.getRestrictedTenantData(OWN_TENANT))
        .thenReturn(new RestrictedTenantDTO().id(OWN_TENANT));
    when(tenantService.getRestrictedTenantData(FOREIGN_TENANT))
        .thenReturn(new RestrictedTenantDTO().id(FOREIGN_TENANT));
    givenAgency(OWN_AGENCY, OWN_TENANT, 11L);
    givenAgency(OTHER_OWN_TENANT_AGENCY, OWN_TENANT, 21L);
    givenAgency(FOREIGN_AGENCY, FOREIGN_TENANT, 31L);
  }

  @AfterEach
  void cleanUp() {
    accountInviteRepository.deleteAll();
  }

  // --- platform admin: everything ---------------------------------------------------------------

  @Test
  void platformAdmin_May_InviteEveryRoleIntoAnyTenant() {
    actAsPlatformAdmin();

    assertThat(service.createInvite(tenantAdmin(FOREIGN_TENANT)).getId()).isNotNull();
    assertThat(service.createInvite(agencyAdmin(FOREIGN_AGENCY, null)).getId()).isNotNull();
    assertThat(service.createInvite(counsellor(FOREIGN_AGENCY)).getId()).isNotNull();
  }

  // --- Träger admin: TENANT_ADMIN, AGENCY_ADMIN, COUNSELLOR in the own Träger -------------------

  @Test
  void tenantAdmin_May_InviteTenantAdminsAgencyAdminsAndCounsellorsIntoTheOwnTenant() {
    actAsTenantAdmin();

    assertThat(service.createInvite(tenantAdmin(OWN_TENANT)).getTenantId()).isEqualTo(OWN_TENANT);
    assertThat(service.createInvite(agencyAdmin(OTHER_OWN_TENANT_AGENCY, null)).getTenantId())
        .isEqualTo(OWN_TENANT);
    assertThat(service.createInvite(counsellor(OWN_AGENCY)).getTenantId()).isEqualTo(OWN_TENANT);
  }

  @Test
  void tenantAdmin_MayNot_InviteAnAgencyAdminIntoAForeignTenantsAgency() {
    actAsTenantAdmin();

    assertThatThrownBy(() -> service.createInvite(agencyAdmin(FOREIGN_AGENCY, null)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void tenantAdmin_MayNot_InviteAPlatformAdmin() {
    actAsTenantAdmin();

    assertThatThrownBy(
            () ->
                service.createInvite(
                    command(AccountInviteTargetRole.PLATFORM_ADMIN, OWN_TENANT, null, null, null)))
        .isInstanceOf(ForbiddenException.class);
  }

  // --- agency admin: COUNSELLOR into the own agencies -------------------------------------------

  @Test
  void agencyAdmin_May_InviteACounsellorIntoItsOwnAgency() {
    actAsAgencyAdmin();

    assertThat(service.createInvite(counsellor(OWN_AGENCY)).getAgencyId()).isEqualTo(OWN_AGENCY);
  }

  @Test
  void agencyAdmin_MayNot_InviteAnotherAgencyAdmin() {
    actAsAgencyAdmin();

    assertThatThrownBy(() -> service.createInvite(agencyAdmin(OWN_AGENCY, null)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void agencyAdmin_MayNot_InviteATenantAdmin() {
    actAsAgencyAdmin();

    assertThatThrownBy(() -> service.createInvite(tenantAdmin(OWN_TENANT)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void agencyAdmin_MayNot_InviteACounsellorIntoAnAgencyItDoesNotAdminister() {
    actAsAgencyAdmin();

    assertThatThrownBy(() -> service.createInvite(counsellor(OTHER_OWN_TENANT_AGENCY)))
        .isInstanceOf(ForbiddenException.class);
  }

  // --- the "also counsellor" flag ---

  @Test
  void agencyAdminInvite_Should_DefaultToAlsoCounsellor() {
    actAsTenantAdmin();

    AccountInvite invite = service.createInvite(agencyAdmin(OWN_AGENCY, null));

    assertThat(invite.getAlsoCounsellor()).isTrue();
  }

  @Test
  void agencyAdminInvite_Should_KeepAlsoCounsellorOff_When_TheInviterSwitchesItOff() {
    actAsTenantAdmin();

    AccountInvite invite = service.createInvite(agencyAdmin(OWN_AGENCY, false));

    assertThat(invite.getAlsoCounsellor()).isFalse();
  }

  @Test
  void agencyAdminInvite_Should_RequireTheSecondFactorLikeTheOtherAdminRoles() {
    actAsTenantAdmin();

    AccountInvite invite = service.createInvite(agencyAdmin(OWN_AGENCY, null));

    assertThat(invite.getTwoFactorStatus()).isEqualTo(TwoFactorGateStatus.PENDING_SETUP);
  }

  @Test
  void alsoCounsellor_Should_BeRefused400_ForAnyOtherRole() {
    actAsTenantAdmin();

    assertThatThrownBy(
            () ->
                service.createInvite(
                    command(
                        AccountInviteTargetRole.COUNSELLOR,
                        OWN_TENANT,
                        OWN_AGENCY,
                        IdAllocationMode.EXISTING,
                        true)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void agencyAdminInvite_Should_BeRefused400_When_ItNamesNoAgency() {
    actAsTenantAdmin();

    assertThatThrownBy(
            () ->
                service.createInvite(
                    command(AccountInviteTargetRole.AGENCY_ADMIN, OWN_TENANT, null, null, null)))
        .isInstanceOf(BadRequestException.class);
  }

  // --- helpers ----------------------------------------------------------------------------------

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

  private void givenAgency(long agencyId, long tenantId, long topicId) {
    when(agencyService.getAgencyWithoutCaching(agencyId))
        .thenReturn(new AgencyDTO().id(agencyId).tenantId(tenantId).topicIds(List.of(topicId)));
    when(existingAgencyClient.find(agencyId))
        .thenReturn(Optional.of(new ExistingAgency(agencyId, tenantId, false, List.of(topicId))));
  }

  private static CreateAccountInviteCommand tenantAdmin(Long tenantId) {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.TENANT_ADMIN,
        tenantId,
        UUID.randomUUID() + "@example.org",
        "Ada",
        "Lovelace",
        null,
        null,
        null,
        IdAllocationMode.EXISTING,
        null);
  }

  private static CreateAccountInviteCommand agencyAdmin(Long agencyId, Boolean alsoCounsellor) {
    return command(
        AccountInviteTargetRole.AGENCY_ADMIN,
        null,
        agencyId,
        IdAllocationMode.EXISTING,
        alsoCounsellor);
  }

  private static CreateAccountInviteCommand counsellor(Long agencyId) {
    return command(
        AccountInviteTargetRole.COUNSELLOR, null, agencyId, IdAllocationMode.EXISTING, null);
  }

  private static CreateAccountInviteCommand command(
      AccountInviteTargetRole role,
      Long tenantId,
      Long agencyId,
      IdAllocationMode agencyMode,
      Boolean alsoCounsellor) {
    return new CreateAccountInviteCommand(
        role,
        tenantId,
        UUID.randomUUID() + "@example.org",
        "Ada",
        "Lovelace",
        agencyId,
        null,
        null,
        null,
        agencyMode,
        alsoCounsellor);
  }
}
