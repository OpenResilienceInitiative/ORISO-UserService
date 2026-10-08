package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.IdReservationReleaseTask;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdReservationReleaseTaskRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseProcessor;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseType;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdReservation;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import java.time.LocalDateTime;
import java.util.Optional;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

/** Only the remote ledgers, Keycloak and SMTP are replaced; the release processor is real. */
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
  IdReservationReleaseProcessor.class,
  AccountInviteReservationReleaseIT.CallerConfig.class
})
@WithTenant(AccountInviteReservationReleaseIT.OWN_TENANT)
class AccountInviteReservationReleaseIT {

  static final long OWN_TENANT = 1L;
  private static final long NEW_AGENCY = 500L;
  private static final long FOREIGN_RESERVED_AGENCY = 501L;
  private static final long EXISTING_AGENCY = 1L;
  private static final long NEW_TENANT = 900L;
  private static final String AGENCY_PROOF = "agency-owner-proof-500";
  private static final String TENANT_PROOF = "reservation-token-900";

  @TestConfiguration
  static class CallerConfig {
    @Bean
    AuthenticatedUser authenticatedUser() {
      return new AuthenticatedUser();
    }
  }

  @Autowired private AccountInviteService service;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private IdReservationReleaseTaskRepository releaseTaskRepository;
  @Autowired private AuthenticatedUser caller;
  @Autowired private IdReservationReleaseProcessor releaseProcessor;

  @MockitoBean private ExistingAccountSetupIssuer existingAccountSetupIssuer;

  @MockitoBean private IdentityEmailOwnerLookup identityEmailOwnerLookup;
  @MockitoBean private de.caritas.cob.userservice.api.service.agency.AgencyService agencyService;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private TenantIdAllocationClient tenantIdAllocationClient;
  @MockitoBean private AgencyIdAllocationClient agencyIdAllocationClient;
  @MockitoBean private AgencyFacts agencyFacts;
  @MockitoBean private InviteAcceptUrlBuilder inviteAcceptUrlBuilder;
  @MockitoBean private InviteMailDispatchService inviteMailDispatchService;
  @MockitoBean private InviteEmailDeliveryFailureRecorder deliveryFailureRecorder;

  @BeforeEach
  void upstreams() {
    when(identityEmailOwnerLookup.findByEmail(anyString())).thenReturn(Optional.empty());
    // The new agency 500 is free until an admin invite reserves it; afterwards it is RESERVED.
    when(agencyIdAllocationClient.getAvailability(NEW_AGENCY)).thenReturn(IdAllocationStatus.FREE);
    when(agencyIdAllocationClient.reserveWithProof(NEW_AGENCY, OWN_TENANT))
        .thenAnswer(
            call -> {
              when(agencyIdAllocationClient.getAvailability(NEW_AGENCY))
                  .thenReturn(IdAllocationStatus.RESERVED);
              return new AgencyIdAllocationClient.AgencyReservation(NEW_AGENCY, AGENCY_PROOF);
            });
    when(agencyIdAllocationClient.release(anyLong(), anyString()))
        .thenAnswer(
            call ->
                (long) call.getArgument(0) == NEW_AGENCY
                    && AGENCY_PROOF.equals(call.getArgument(1)));
    when(agencyIdAllocationClient.getAvailability(EXISTING_AGENCY))
        .thenReturn(IdAllocationStatus.ASSIGNED);
    when(agencyService.getAgenciesWithoutCaching(java.util.List.of(EXISTING_AGENCY)))
        .thenReturn(java.util.List.of(new AgencyDTO().id(EXISTING_AGENCY).tenantId(OWN_TENANT)));
    when(agencyFacts.find(EXISTING_AGENCY))
        .thenReturn(
            Optional.of(
                new AgencyFacts.Agency(
                    EXISTING_AGENCY, OWN_TENANT, false, java.util.List.of(11L))));
    when(tenantService.getRestrictedTenantData(NEW_TENANT))
        .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "", null, null, null));
    when(tenantIdAllocationClient.getAvailability(NEW_TENANT))
        .thenReturn(IdAllocationStatus.RESERVED);
    when(tenantIdAllocationClient.reserve(NEW_TENANT))
        .thenReturn(new TenantIdReservation(NEW_TENANT, TENANT_PROOF));
    when(tenantIdAllocationClient.release(anyLong(), anyString()))
        .thenAnswer(
            call ->
                (long) call.getArgument(0) == NEW_TENANT
                    && TENANT_PROOF.equals(call.getArgument(1)));
    actAsTenantAdmin();
  }

  @AfterEach
  void cleanUp() {
    accountInviteRepository.deleteAll();
    releaseTaskRepository.deleteAll();
  }

  // --- revoke -----------------------------------------------------------------------------------

  @Test
  void revokingTheOnlyAdminInviteOfANewAgency_Should_ReleaseItsNumber() {
    AccountInvite admin = service.createInvite(agencyAdmin(NEW_AGENCY));
    assertThat(reload(admin).getAgencyReservationToken()).isEqualTo(AGENCY_PROOF);

    service.revokeInvite(admin.getId());

    verify(agencyIdAllocationClient, times(1)).release(NEW_AGENCY, AGENCY_PROOF);
    assertThat(releaseTaskRepository.count()).isZero();
  }

  @Test
  void revokingOneOfTwoAdminsOfTheSameNewAgency_Should_KeepTheNumber_UntilTheLastIsRevoked() {
    AccountInvite first = service.createInvite(agencyAdmin(NEW_AGENCY));
    AccountInvite second = service.createInvite(agencyAdmin(NEW_AGENCY));

    service.revokeInvite(first.getId());
    verify(agencyIdAllocationClient, never())
        .release(anyLong(), org.mockito.ArgumentMatchers.nullable(String.class));

    service.revokeInvite(second.getId());
    verify(agencyIdAllocationClient, times(1)).release(NEW_AGENCY, AGENCY_PROOF);
  }

  @Test
  void revokingTheAdmin_Should_KeepTheNumber_WhileCounsellorsWaitForTheAgency() {
    AccountInvite admin = service.createInvite(agencyAdmin(NEW_AGENCY));
    AccountInvite waiting = service.createInvite(counsellor(NEW_AGENCY));
    assertThat(reload(waiting).getAgencyReservationToken()).isEqualTo(AGENCY_PROOF);
    assertThat(reload(admin).getAgencyReservationToken()).isEqualTo(AGENCY_PROOF);

    service.revokeInvite(admin.getId());
    assertThat(reload(admin).getAgencyReservationToken()).isEqualTo(AGENCY_PROOF);
    verify(agencyIdAllocationClient, never())
        .release(anyLong(), org.mockito.ArgumentMatchers.nullable(String.class));

    // The last invite that still needed the number is gone.
    service.revokeInvite(waiting.getId());
    verify(agencyIdAllocationClient, times(1)).release(NEW_AGENCY, AGENCY_PROOF);
  }

  @Test
  void revokingAnInviteIntoAnExistingAgency_Should_ReleaseNothing() {
    AccountInvite invite =
        service.createInvite(
            command(
                AccountInviteTargetRole.COUNSELLOR,
                null,
                null,
                EXISTING_AGENCY,
                IdAllocationMode.EXISTING));

    service.revokeInvite(invite.getId());

    verify(agencyIdAllocationClient, never())
        .release(anyLong(), org.mockito.ArgumentMatchers.nullable(String.class));
    verify(tenantIdAllocationClient, never())
        .release(anyLong(), org.mockito.ArgumentMatchers.nullable(String.class));
  }

  @Test
  void revokingAWaitingInvite_Should_NotReleaseANumberNoneOfOurInvitesReserved() {
    // A waiting row on 501 without any admin invite of ours: someone else holds 501.
    when(agencyIdAllocationClient.getAvailability(FOREIGN_RESERVED_AGENCY))
        .thenReturn(IdAllocationStatus.RESERVED);
    AccountInvite row =
        accountInviteRepository.save(
            AccountInvite.builder()
                .targetRole(AccountInviteTargetRole.COUNSELLOR)
                .tenantId(OWN_TENANT)
                .recipientEmail("waiting@example.org")
                .agencyId(FOREIGN_RESERVED_AGENCY)
                .agencyIdAllocationMode(IdAllocationMode.MANUAL)
                .waitingForUnit(InviteUnitType.AGENCY)
                .status(AccountInviteStatus.WAITING_FOR_UNIT)
                .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
                .emailVerificationStatus(EmailVerificationStatus.PENDING)
                .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build());

    service.revokeInvite(row.getId());

    verify(agencyIdAllocationClient, never())
        .release(anyLong(), org.mockito.ArgumentMatchers.nullable(String.class));
  }

  @Test
  void waitingInviteMustNotAdoptOwnerProofFromAnotherTenant() {
    when(agencyIdAllocationClient.getAvailability(FOREIGN_RESERVED_AGENCY))
        .thenReturn(IdAllocationStatus.RESERVED);
    accountInviteRepository.saveAndFlush(
        AccountInvite.builder()
            .targetRole(AccountInviteTargetRole.AGENCY_ADMIN)
            .tenantId(99L)
            .recipientEmail("foreign-owner@example.org")
            .agencyId(FOREIGN_RESERVED_AGENCY)
            .agencyIdAllocationMode(IdAllocationMode.MANUAL)
            .agencyReservationToken("foreign-owner-proof")
            .status(AccountInviteStatus.REVOKED)
            .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
            .emailVerificationStatus(EmailVerificationStatus.PENDING)
            .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
            .createDate(LocalDateTime.now())
            .updateDate(LocalDateTime.now())
            .build());
    var waiting =
        accountInviteRepository.saveAndFlush(
            AccountInvite.builder()
                .targetRole(AccountInviteTargetRole.COUNSELLOR)
                .tenantId(OWN_TENANT)
                .recipientEmail("local-waiting@example.org")
                .agencyId(FOREIGN_RESERVED_AGENCY)
                .agencyIdAllocationMode(IdAllocationMode.MANUAL)
                .waitingForUnit(InviteUnitType.AGENCY)
                .status(AccountInviteStatus.WAITING_FOR_UNIT)
                .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
                .emailVerificationStatus(EmailVerificationStatus.PENDING)
                .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build());

    service.revokeInvite(waiting.getId());

    verify(agencyIdAllocationClient, never())
        .release(anyLong(), org.mockito.ArgumentMatchers.nullable(String.class));
    assertThat(releaseTaskRepository.findAll())
        .singleElement()
        .satisfies(task -> assertThat(task.getReservationToken()).isNull());
  }

  @Test
  void revokingTheOnlyAdminInviteOfANewTraeger_Should_ReleaseItsNumber() {
    actAsPlatformAdmin();
    AccountInvite first = service.createInvite(newTenantAdmin());
    AccountInvite second = service.createInvite(newTenantAdmin());

    service.revokeInvite(first.getId());
    verify(tenantIdAllocationClient, never())
        .release(anyLong(), org.mockito.ArgumentMatchers.nullable(String.class));

    service.revokeInvite(second.getId());
    verify(tenantIdAllocationClient, times(1)).release(NEW_TENANT, TENANT_PROOF);
  }

  // --- expiry -----------------------------------------------------------------------------------

  @Test
  void anExpiredAdminInvite_Should_ReleaseItsNumber_Once() {
    AccountInvite admin = service.createInvite(agencyAdmin(NEW_AGENCY));
    elapse(admin);

    service.expireElapsedInvites();
    service.expireElapsedInvites();

    assertThat(reload(admin).getStatus()).isEqualTo(AccountInviteStatus.EXPIRED);
    verify(agencyIdAllocationClient, times(1)).release(NEW_AGENCY, AGENCY_PROOF);
  }

  @Test
  void anExpiredAdminInvite_Should_KeepTheNumber_WhileAnotherAdminInviteIsPending() {
    AccountInvite first = service.createInvite(agencyAdmin(NEW_AGENCY));
    AccountInvite second = service.createInvite(agencyAdmin(NEW_AGENCY));
    elapse(first);

    service.expireElapsedInvites();

    assertThat(reload(first).getStatus()).isEqualTo(AccountInviteStatus.EXPIRED);
    assertThat(reload(second).getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    verify(agencyIdAllocationClient, never())
        .release(anyLong(), org.mockito.ArgumentMatchers.nullable(String.class));
  }

  @Test
  void aStillValidInvite_Should_NotBeTouchedByTheExpirySweep() {
    AccountInvite admin = service.createInvite(agencyAdmin(NEW_AGENCY));

    service.expireElapsedInvites();

    assertThat(reload(admin).getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    verify(agencyIdAllocationClient, never())
        .release(anyLong(), org.mockito.ArgumentMatchers.nullable(String.class));
  }

  @Test
  void legacyReleaseTaskWithoutOwnerProof_ShouldStayPending() {
    var task =
        releaseTaskRepository.saveAndFlush(
            IdReservationReleaseTask.builder()
                .allocationType(IdReservationReleaseType.AGENCY)
                .reservedId(FOREIGN_RESERVED_AGENCY)
                .tenantContextId(OWN_TENANT)
                .createDate(LocalDateTime.now())
                .build());

    assertThat(releaseProcessor.process(task.getId())).isFalse();
    var pending = releaseTaskRepository.findById(task.getId()).orElseThrow();
    assertThat(pending.getReservationToken()).isNull();
    assertThat(pending.getAttemptCount()).isEqualTo(1);
    verify(agencyIdAllocationClient, never()).release(anyLong());
    verify(agencyIdAllocationClient, never())
        .release(anyLong(), org.mockito.ArgumentMatchers.nullable(String.class));
  }

  // --- helpers ----------------------------------------------------------------------------------

  private void elapse(AccountInvite invite) {
    AccountInvite stored = reload(invite);
    stored.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    accountInviteRepository.save(stored);
  }

  private AccountInvite reload(AccountInvite invite) {
    return accountInviteRepository.findById(invite.getId()).orElseThrow();
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

  private void actAsPlatformAdmin() {
    Tenants.actAs(
        caller,
        "platform-admin",
        0L,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  private static CreateAccountInviteCommand agencyAdmin(Long agencyId) {
    return command(
        AccountInviteTargetRole.AGENCY_ADMIN, null, null, agencyId, IdAllocationMode.MANUAL);
  }

  private static CreateAccountInviteCommand counsellor(Long agencyId) {
    return command(
        AccountInviteTargetRole.COUNSELLOR, null, null, agencyId, IdAllocationMode.MANUAL);
  }

  private static CreateAccountInviteCommand newTenantAdmin() {
    return command(
        AccountInviteTargetRole.TENANT_ADMIN, NEW_TENANT, IdAllocationMode.MANUAL, null, null);
  }

  private static CreateAccountInviteCommand command(
      AccountInviteTargetRole role,
      Long tenantId,
      IdAllocationMode tenantMode,
      Long agencyId,
      IdAllocationMode agencyMode) {
    return new CreateAccountInviteCommand(
        role,
        tenantId,
        UUID.randomUUID() + "@example.org",
        "Ada",
        "Lovelace",
        agencyId,
        null,
        null,
        tenantMode,
        agencyMode,
        null);
  }
}
