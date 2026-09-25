package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
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
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseProcessor;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  AccountInviteService.class,
  InviteTargetResolver.class,
  ReservationLedger.class,
  UnitQueue.class,
  InviteDelivery.class,
  AccountInviteTopicPermissionService.class,
  AccountInviteAccessPolicy.class,
  de.caritas.cob.userservice.api.admin.service.admin.AdminScope.class,
  AccountInviteExistingAgencyIT.CallerConfig.class
})
@WithTenant(AccountInviteExistingAgencyIT.OWN_TENANT)
class AccountInviteExistingAgencyIT {

  static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;

  /** Seeded restricted agency admin who administers agency 1 only. */
  private static final String AGENCY_ADMIN_ID = "d42c2e5e-143c-4db1-a90f-7cccf82fbb15";

  /** Agency of tenant 1 with exactly one topic (department 11). */
  private static final long SINGLE_TOPIC_AGENCY = 1L;

  private static final long SINGLE_TOPIC = 11L;

  /** Agency of tenant 1 with two topics (21, 22) that the agency admin does not administer. */
  private static final long TWO_TOPIC_AGENCY = 2L;

  /** Agency of tenant 2. */
  private static final long FOREIGN_TENANT_AGENCY = 3L;

  /** Soft-deleted agency of tenant 1. */
  private static final long DELETED_AGENCY = 4L;

  /** Agency of tenant 1 that has no topic yet. */
  private static final long TOPICLESS_AGENCY = 5L;

  /** An agency ID that does not exist. */
  private static final long MISSING_AGENCY = 999L;

  @TestConfiguration
  static class CallerConfig {
    @Bean
    AuthenticatedUser authenticatedUser() {
      return new AuthenticatedUser();
    }
  }

  private final Map<Long, AgencyDTO> knownAgencies = new HashMap<>();

  @Autowired private AccountInviteService service;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private AuthenticatedUser caller;

  @MockitoBean private IdentityEmailOwnerLookup identityEmailOwnerLookup;
  @MockitoBean private de.caritas.cob.userservice.api.service.agency.AgencyService agencyService;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private TenantIdAllocationClient tenantIdAllocationClient;
  @MockitoBean private AgencyIdAllocationClient agencyIdAllocationClient;
  @MockitoBean private AgencyFacts agencyFacts;
  @MockitoBean private IdReservationReleaseProcessor reservationReleaseProcessor;
  @MockitoBean private InviteAcceptUrlBuilder inviteAcceptUrlBuilder;
  @MockitoBean private InviteMailDispatchService inviteMailDispatchService;
  @MockitoBean private InviteEmailDeliveryFailureRecorder deliveryFailureRecorder;

  @BeforeEach
  void givenAgencies() {
    when(identityEmailOwnerLookup.findByEmail(anyString())).thenReturn(Optional.empty());
    givenAgency(SINGLE_TOPIC_AGENCY, OWN_TENANT, false, List.of(SINGLE_TOPIC));
    givenAgency(TWO_TOPIC_AGENCY, OWN_TENANT, false, List.of(21L, 22L));
    givenAgency(FOREIGN_TENANT_AGENCY, FOREIGN_TENANT, false, List.of(31L));
    givenAgency(DELETED_AGENCY, OWN_TENANT, true, List.of(41L));
    givenAgency(TOPICLESS_AGENCY, OWN_TENANT, false, List.of());
    when(agencyFacts.find(MISSING_AGENCY)).thenReturn(Optional.empty());
  }

  @AfterEach
  void cleanUp() {
    accountInviteRepository.deleteAll();
  }

  @Test
  void createInvite_Should_BindToTheExistingAgencyWithoutReserving_When_TenantAdminUsesExisting() {
    actAsTenantAdmin();

    AccountInvite invite = service.createInvite(existing(null, TWO_TOPIC_AGENCY, 22L));

    assertThat(invite.getAgencyId()).isEqualTo(TWO_TOPIC_AGENCY);
    assertThat(invite.getTenantId()).isEqualTo(OWN_TENANT);
    assertThat(invite.getDepartmentId()).isEqualTo(22L);
    verify(agencyIdAllocationClient, never()).reserve(any(), any());
  }

  @Test
  void createInvite_Should_UseTheOnlyTopicAsDepartment_When_TheAgencyHasExactlyOneTopic() {
    actAsTenantAdmin();

    AccountInvite invite = service.createInvite(existing(OWN_TENANT, SINGLE_TOPIC_AGENCY, null));

    assertThat(invite.getDepartmentId()).isEqualTo(SINGLE_TOPIC);
  }

  @Test
  void createInvite_Should_Succeed_When_AgencyAdminUsesExistingForOwnAgency() {
    actAsAgencyAdmin();

    AccountInvite invite = service.createInvite(existing(null, SINGLE_TOPIC_AGENCY, null));

    assertThat(invite.getAgencyId()).isEqualTo(SINGLE_TOPIC_AGENCY);
    assertThat(invite.getTenantId()).isEqualTo(OWN_TENANT);
    verify(agencyIdAllocationClient, never()).reserve(any(), any());
  }

  @Test
  void createInvite_Should_TakeTheAgencysTenant_When_PlatformAdminNamesNoTenant() {
    actAsPlatformAdmin();

    AccountInvite invite = service.createInvite(existing(null, FOREIGN_TENANT_AGENCY, null));

    assertThat(invite.getTenantId()).isEqualTo(FOREIGN_TENANT);
    assertThat(invite.getAgencyId()).isEqualTo(FOREIGN_TENANT_AGENCY);
    assertThat(invite.getDepartmentId()).isEqualTo(31L);
  }

  @Test
  void createInvite_Should_Refuse403_When_TenantAdminUsesExistingForAForeignTenantsAgency() {
    actAsTenantAdmin();

    assertThatThrownBy(() -> service.createInvite(existing(null, FOREIGN_TENANT_AGENCY, null)))
        .isInstanceOf(ForbiddenException.class);
    assertThat(accountInviteRepository.count()).isZero();
  }

  @Test
  void createInvite_Should_Refuse403_When_AgencyAdminUsesExistingForAnAgencyItDoesNotAdminister() {
    actAsAgencyAdmin();

    assertThatThrownBy(() -> service.createInvite(existing(null, TWO_TOPIC_AGENCY, 21L)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void createInvite_Should_Refuse404_When_TheExistingAgencyIsDeleted() {
    actAsTenantAdmin();

    assertThatThrownBy(() -> service.createInvite(existing(null, DELETED_AGENCY, null)))
        .isInstanceOf(NotFoundException.class);
    assertThat(accountInviteRepository.count()).isZero();
  }

  @Test
  void createInvite_Should_Refuse404_When_PlatformAdminNamesAnAgencyThatDoesNotExist() {
    actAsPlatformAdmin();

    assertThatThrownBy(() -> service.createInvite(existing(OWN_TENANT, MISSING_AGENCY, null)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void createInvite_Should_Refuse400_When_PlatformAdminNamesAnotherTenantThanTheAgencys() {
    actAsPlatformAdmin();

    assertThatThrownBy(
            () -> service.createInvite(existing(OWN_TENANT, FOREIGN_TENANT_AGENCY, null)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void createInvite_Should_Refuse400_When_ExistingNamesNoAgency() {
    actAsPlatformAdmin();

    assertThatThrownBy(() -> service.createInvite(existing(OWN_TENANT, null, null)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void createInvite_Should_Refuse400_When_TheDepartmentIsNotATopicOfTheAgency() {
    actAsTenantAdmin();

    assertThatThrownBy(() -> service.createInvite(existing(null, TWO_TOPIC_AGENCY, 99L)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void createInvite_Should_Refuse400_When_TheAgencyHasNoTopicButADepartmentIsNamed() {
    actAsTenantAdmin();

    // 31 is a topic of another Träger's agency; an agency without topics must not take it.
    assertThatThrownBy(() -> service.createInvite(existing(null, TOPICLESS_AGENCY, 31L)))
        .isInstanceOf(BadRequestException.class);
    assertThat(accountInviteRepository.count()).isZero();
  }

  @Test
  void createInvite_Should_StillReserve_When_TheAdminSendsManual() {
    actAsPlatformAdmin();
    when(agencyIdAllocationClient.reserve(500L, OWN_TENANT)).thenReturn(500L);
    when(agencyIdAllocationClient.getAvailability(anyLong()))
        .thenReturn(IdAllocationStatus.RESERVED);

    AccountInvite invite =
        service.createInvite(
            new CreateAccountInviteCommand(
                // A counsellor into a new agency waits; only the unit's admin invite reserves.
                AccountInviteTargetRole.AGENCY_ADMIN,
                OWN_TENANT,
                "manual@example.org",
                "Ada",
                "Lovelace",
                500L,
                null,
                null,
                null,
                IdAllocationMode.MANUAL));

    assertThat(invite.getAgencyId()).isEqualTo(500L);
    verify(agencyIdAllocationClient).reserve(500L, OWN_TENANT);
    verify(agencyFacts, never()).find(anyLong());
  }

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

  private void givenAgency(long agencyId, long tenantId, boolean deleted, List<Long> topicIds) {
    knownAgencies.put(agencyId, new AgencyDTO().id(agencyId).tenantId(tenantId));
    when(agencyService.getAgenciesWithoutCaching(anyList()))
        .thenAnswer(
            call ->
                ((List<?>) call.getArgument(0))
                    .stream().map(knownAgencies::get).filter(Objects::nonNull).toList());
    when(agencyFacts.find(agencyId))
        .thenReturn(
            Optional.of(
                new AgencyFacts.Agency(
                    agencyId, tenantId, deleted, topicIds, TopicPermission.CREATE)));
  }

  private static CreateAccountInviteCommand existing(
      Long tenantId, Long agencyId, Long departmentId) {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.COUNSELLOR,
        tenantId,
        "existing-" + tenantId + "-" + agencyId + "-" + departmentId + "@example.org",
        "Ada",
        "Lovelace",
        agencyId,
        departmentId,
        null,
        null,
        IdAllocationMode.EXISTING);
  }
}
