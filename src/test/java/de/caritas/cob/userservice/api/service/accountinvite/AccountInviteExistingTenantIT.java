package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.ExistingAgencyClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.ExistingAgencyClient.ExistingAgency;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseProcessor;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

/** Invites with {@code tenantIdAllocationMode = EXISTING}; only remote services are mocked. */
@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  AccountInviteService.class,
  AccountInviteAccessPolicy.class,
  de.caritas.cob.userservice.api.admin.service.admin.AdminScope.class,
  AccountInviteExistingTenantIT.CallerConfig.class
})
@WithTenant(AccountInviteExistingTenantIT.OWN_TENANT)
class AccountInviteExistingTenantIT {

  static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;
  private static final long MISSING_TENANT = 404L;

  /** Seeded restricted agency admin who administers agency 1 (tenant 1) only. */
  private static final String AGENCY_ADMIN_ID = "d42c2e5e-143c-4db1-a90f-7cccf82fbb15";

  private static final long OWN_AGENCY = 1L;
  private static final long OWN_AGENCY_TOPIC = 11L;
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
  @MockitoBean private IdReservationReleaseProcessor reservationReleaseProcessor;
  @MockitoBean private InviteAcceptUrlBuilder inviteAcceptUrlBuilder;
  @MockitoBean private InviteMailDispatchService inviteMailDispatchService;
  @MockitoBean private InviteEmailDeliveryFailureRecorder deliveryFailureRecorder;
  @MockitoBean private AgencyService agencyService;

  private final Map<Long, AgencyDTO> knownAgencies = new HashMap<>();

  @BeforeEach
  void givenTenantsAndAgencies() {
    when(identityEmailOwnerLookup.findByEmail(anyString())).thenReturn(Optional.empty());
    givenTenant(OWN_TENANT);
    givenTenant(FOREIGN_TENANT);
    when(tenantService.getRestrictedTenantData(MISSING_TENANT))
        .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "", null, null, null));
    givenAgency(OWN_AGENCY, OWN_TENANT, List.of(OWN_AGENCY_TOPIC));
    givenAgency(FOREIGN_AGENCY, FOREIGN_TENANT, List.of(31L));
  }

  @AfterEach
  void cleanUp() {
    accountInviteRepository.deleteAll();
  }

  // --- Träger-admin invites into an existing Träger ---------------------------------------------

  @Test
  void createInvite_Should_BindTheTenantAdminToTheExistingTenantWithoutReserving() {
    actAsPlatformAdmin();

    AccountInvite invite = service.createInvite(tenantAdmin(FOREIGN_TENANT, "pa-existing"));

    assertThat(invite.getTenantId()).isEqualTo(FOREIGN_TENANT);
    assertThat(invite.getTenantIdReservationToken()).isNull();
    assertThat(invite.getTenantIdAllocationMode()).isEqualTo(IdAllocationMode.EXISTING);
    verify(tenantIdAllocationClient, never()).reserve(any());
  }

  @Test
  void createInvite_Should_AllowASecondTenantAdmin_When_TheExistingTenantAlreadyHasOne() {
    actAsPlatformAdmin();
    service.createInvite(tenantAdmin(OWN_TENANT, "first"));

    AccountInvite second = service.createInvite(tenantAdmin(OWN_TENANT, "second"));

    assertThat(second.getTenantId()).isEqualTo(OWN_TENANT);
    assertThat(accountInviteRepository.count()).isEqualTo(2);
  }

  @Test
  void createInvite_Should_StampTheOwnTenant_When_TenantAdminNamesNoTenant() {
    actAsTenantAdmin();

    AccountInvite invite = service.createInvite(tenantAdmin(null, "ta-own"));

    assertThat(invite.getTenantId()).isEqualTo(OWN_TENANT);
    assertThat(invite.getTenantIdAllocationMode()).isEqualTo(IdAllocationMode.EXISTING);
    verify(tenantIdAllocationClient, never()).reserve(any());
  }

  @Test
  void createInvite_Should_Refuse403_When_TenantAdminTargetsAForeignTenant() {
    actAsTenantAdmin();

    assertThatThrownBy(() -> service.createInvite(tenantAdmin(FOREIGN_TENANT, "ta-foreign")))
        .isInstanceOf(ForbiddenException.class);
    assertThat(accountInviteRepository.count()).isZero();
  }

  @Test
  void createInvite_Should_Refuse404_When_TheTenantDoesNotExist() {
    actAsPlatformAdmin();

    assertThatThrownBy(() -> service.createInvite(tenantAdmin(MISSING_TENANT, "missing")))
        .isInstanceOf(NotFoundException.class);
    assertThat(accountInviteRepository.count()).isZero();
  }

  @Test
  void createInvite_Should_Refuse400_When_PlatformAdminNamesNoTenant() {
    actAsPlatformAdmin();

    assertThatThrownBy(() -> service.createInvite(tenantAdmin(null, "no-tenant")))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void createInvite_Should_Refuse400_When_TheTenantIsThePlatformTenant() {
    actAsPlatformAdmin();

    assertThatThrownBy(() -> service.createInvite(tenantAdmin(0L, "platform")))
        .isInstanceOf(BadRequestException.class);
  }

  // --- counsellor / agency-admin invites whose Träger is given ---------------------------------

  @Test
  void createInvite_Should_AcceptACounsellorIntoAnExistingAgencyOfTheExistingTenant() {
    actAsTenantAdmin();

    AccountInvite invite =
        service.createInvite(
            invite(
                AccountInviteTargetRole.COUNSELLOR,
                OWN_TENANT,
                OWN_AGENCY,
                IdAllocationMode.EXISTING,
                "counsellor"));

    assertThat(invite.getTenantId()).isEqualTo(OWN_TENANT);
    assertThat(invite.getAgencyId()).isEqualTo(OWN_AGENCY);
    assertThat(invite.getDepartmentId()).isEqualTo(OWN_AGENCY_TOPIC);
    assertThat(invite.getTenantIdAllocationMode()).isEqualTo(IdAllocationMode.EXISTING);
    assertThat(invite.getAgencyIdAllocationMode()).isEqualTo(IdAllocationMode.EXISTING);
  }

  @Test
  void createInvite_Should_ReserveOnlyTheNewAgency_When_AnAgencyAdminIsInvitedIntoTheTenant() {
    actAsPlatformAdmin();
    when(agencyIdAllocationClient.reserve(null, FOREIGN_TENANT)).thenReturn(700L);
    when(agencyIdAllocationClient.getAvailability(700L)).thenReturn(IdAllocationStatus.RESERVED);

    AccountInvite invite =
        service.createInvite(
            invite(
                AccountInviteTargetRole.AGENCY_ADMIN,
                FOREIGN_TENANT,
                null,
                IdAllocationMode.AUTO,
                "agency-admin-new-agency"));

    assertThat(invite.getTenantId()).isEqualTo(FOREIGN_TENANT);
    assertThat(invite.getAgencyId()).isEqualTo(700L);
    assertThat(invite.getAgencyIdAllocationMode()).isEqualTo(IdAllocationMode.AUTO);
    verify(tenantIdAllocationClient, never()).reserve(any());
  }

  @Test
  void createInvite_Should_Refuse400_When_AnExistingAgencyBelongsToAnotherTenant() {
    actAsPlatformAdmin();

    assertThatThrownBy(
            () ->
                service.createInvite(
                    invite(
                        AccountInviteTargetRole.COUNSELLOR,
                        OWN_TENANT,
                        FOREIGN_AGENCY,
                        IdAllocationMode.EXISTING,
                        "mismatch")))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void createInvite_Should_Succeed_When_AgencyAdminInvitesACounsellorIntoItsOwnTenantAndAgency() {
    actAsAgencyAdmin();

    AccountInvite invite =
        service.createInvite(
            invite(
                AccountInviteTargetRole.COUNSELLOR,
                OWN_TENANT,
                OWN_AGENCY,
                IdAllocationMode.EXISTING,
                "aa-counsellor"));

    assertThat(invite.getTenantId()).isEqualTo(OWN_TENANT);
  }

  @Test
  void createInvite_Should_Refuse403_When_AgencyAdminNamesAForeignTenant() {
    actAsAgencyAdmin();

    assertThatThrownBy(
            () ->
                service.createInvite(
                    invite(
                        AccountInviteTargetRole.COUNSELLOR,
                        FOREIGN_TENANT,
                        OWN_AGENCY,
                        IdAllocationMode.EXISTING,
                        "aa-foreign")))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void createInvite_Should_Refuse400_When_AnExistingAgencyIsPairedWithANewTenant() {
    actAsPlatformAdmin();

    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.COUNSELLOR,
            900L,
            "existing-agency-new-tenant@example.org",
            "Ada",
            "Lovelace",
            OWN_AGENCY,
            null,
            null,
            IdAllocationMode.MANUAL,
            IdAllocationMode.EXISTING);

    assertThatThrownBy(() -> service.createInvite(command)).isInstanceOf(BadRequestException.class);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private void actAsTenantAdmin() {
    Tenants.actAs(
        caller,
        "tenant-admin-1",
        OWN_TENANT,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  private void actAsAgencyAdmin() {
    Tenants.actAs(
        caller, AGENCY_ADMIN_ID, OWN_TENANT, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
  }

  private void actAsPlatformAdmin() {
    Tenants.actAs(
        caller,
        "platform-admin",
        0L,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  private void givenTenant(long tenantId) {
    when(tenantService.getRestrictedTenantData(tenantId))
        .thenReturn(new RestrictedTenantDTO().id(tenantId));
  }

  private void givenAgency(long agencyId, long tenantId, List<Long> topicIds) {
    var agency = new AgencyDTO().id(agencyId).tenantId(tenantId).topicIds(topicIds);
    when(agencyService.getAgencyWithoutCaching(agencyId)).thenReturn(agency);
    knownAgencies.put(agencyId, agency);
    when(agencyService.getAgenciesWithoutCaching(anyList()))
        .thenAnswer(
            call ->
                ((List<?>) call.getArgument(0))
                    .stream().map(knownAgencies::get).filter(Objects::nonNull).toList());
    when(existingAgencyClient.find(agencyId))
        .thenReturn(Optional.of(new ExistingAgency(agencyId, tenantId, false, topicIds)));
  }

  private static CreateAccountInviteCommand tenantAdmin(Long tenantId, String name) {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.TENANT_ADMIN,
        tenantId,
        name + "@example.org",
        "Ada",
        "Lovelace",
        null,
        null,
        null,
        IdAllocationMode.EXISTING,
        null);
  }

  private static CreateAccountInviteCommand invite(
      AccountInviteTargetRole role,
      Long tenantId,
      Long agencyId,
      IdAllocationMode agencyMode,
      String name) {
    return new CreateAccountInviteCommand(
        role,
        tenantId,
        name + "@example.org",
        "Ada",
        "Lovelace",
        agencyId,
        null,
        null,
        IdAllocationMode.EXISTING,
        agencyMode);
  }
}
