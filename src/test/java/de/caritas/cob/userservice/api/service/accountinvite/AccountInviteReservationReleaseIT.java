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
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdReservationReleaseTaskRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.ExistingAgencyClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseProcessor;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdReservation;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.time.LocalDateTime;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

/** Only the remote ledgers, Keycloak and SMTP are replaced; the release processor is real. */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  AccountInviteService.class,
  AccountInviteAccessPolicy.class,
  IdReservationReleaseProcessor.class,
  AccountInviteReservationReleaseIT.CallerConfig.class
})
class AccountInviteReservationReleaseIT {

  private static final long OWN_TENANT = 1L;
  private static final long NEW_AGENCY = 500L;
  private static final long FOREIGN_RESERVED_AGENCY = 501L;
  private static final long EXISTING_AGENCY = 1L;
  private static final long NEW_TENANT = 900L;

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

  @MockitoBean private IdentityEmailOwnerLookup identityEmailOwnerLookup;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private TenantIdAllocationClient tenantIdAllocationClient;
  @MockitoBean private AgencyIdAllocationClient agencyIdAllocationClient;
  @MockitoBean private ExistingAgencyClient existingAgencyClient;
  @MockitoBean private InviteAcceptUrlBuilder inviteAcceptUrlBuilder;
  @MockitoBean private InviteMailDispatchService inviteMailDispatchService;
  @MockitoBean private InviteEmailDeliveryFailureRecorder deliveryFailureRecorder;
  @MockitoBean private AgencyService agencyService;

  @BeforeEach
  void upstreams() {
    when(identityEmailOwnerLookup.findByEmail(anyString())).thenReturn(Optional.empty());
    // The new agency 500 is free until an admin invite reserves it; afterwards it is RESERVED.
    when(agencyIdAllocationClient.getAvailability(NEW_AGENCY)).thenReturn(IdAllocationStatus.FREE);
    when(agencyIdAllocationClient.reserve(NEW_AGENCY, OWN_TENANT))
        .thenAnswer(
            call -> {
              when(agencyIdAllocationClient.getAvailability(NEW_AGENCY))
                  .thenReturn(IdAllocationStatus.RESERVED);
              return NEW_AGENCY;
            });
    when(agencyIdAllocationClient.release(anyLong())).thenReturn(true);
    when(agencyIdAllocationClient.getAvailability(EXISTING_AGENCY))
        .thenReturn(IdAllocationStatus.ASSIGNED);
    when(agencyService.getAgencyWithoutCaching(EXISTING_AGENCY))
        .thenReturn(new AgencyDTO().id(EXISTING_AGENCY).tenantId(OWN_TENANT));
    when(existingAgencyClient.find(EXISTING_AGENCY))
        .thenReturn(
            Optional.of(
                new ExistingAgencyClient.ExistingAgency(
                    EXISTING_AGENCY, OWN_TENANT, false, java.util.List.of(11L))));
    when(tenantService.getRestrictedTenantData(NEW_TENANT))
        .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "", null, null, null));
    when(tenantIdAllocationClient.getAvailability(NEW_TENANT))
        .thenReturn(IdAllocationStatus.RESERVED);
    when(tenantIdAllocationClient.reserve(NEW_TENANT))
        .thenReturn(new TenantIdReservation(NEW_TENANT, "reservation-token-900"));
    when(tenantIdAllocationClient.release(anyLong())).thenReturn(true);
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
    AccountInvite admin = service.createInvite(agencyAdmin(NEW_AGENCY, null));

    service.revokeInvite(admin.getId());

    verify(agencyIdAllocationClient, times(1)).release(NEW_AGENCY);
    assertThat(releaseTaskRepository.count()).isZero();
  }

  @Test
  void revokingOneOfTwoAdminsOfTheSameNewAgency_Should_KeepTheNumber_UntilTheLastIsRevoked() {
    AccountInvite first = service.createInvite(agencyAdmin(NEW_AGENCY, null));
    AccountInvite second = service.createInvite(agencyAdmin(NEW_AGENCY, null));

    service.revokeInvite(first.getId());
    verify(agencyIdAllocationClient, never()).release(anyLong());

    service.revokeInvite(second.getId());
    verify(agencyIdAllocationClient, times(1)).release(NEW_AGENCY);
  }

  @Test
  void revokingTheAdmin_Should_KeepTheNumber_WhileCounsellorsWaitForTheAgency() {
    AccountInvite admin = service.createInvite(agencyAdmin(NEW_AGENCY, null));
    AccountInvite waiting = service.createInvite(counsellor(NEW_AGENCY, null));

    service.revokeInvite(admin.getId());
    verify(agencyIdAllocationClient, never()).release(anyLong());

    // The last invite that still needed the number is gone.
    service.revokeInvite(waiting.getId());
    verify(agencyIdAllocationClient, times(1)).release(NEW_AGENCY);
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
                IdAllocationMode.EXISTING,
                null));

    service.revokeInvite(invite.getId());

    verify(agencyIdAllocationClient, never()).release(anyLong());
    verify(tenantIdAllocationClient, never()).release(anyLong());
  }

  @Test
  void revokingAQueuedCsvRow_Should_NotReleaseANumberNoneOfOurInvitesReserved() {
    // A CSV counsellor row for 501 whose admin row never came: someone else holds 501.
    when(agencyIdAllocationClient.getAvailability(FOREIGN_RESERVED_AGENCY))
        .thenReturn(IdAllocationStatus.RESERVED);
    AccountInvite row = service.createInvite(counsellor(FOREIGN_RESERVED_AGENCY, "batch-1"));

    service.revokeInvite(row.getId());

    verify(agencyIdAllocationClient, never()).release(anyLong());
  }

  @Test
  void revokingTheOnlyAdminInviteOfANewTraeger_Should_ReleaseItsNumber() {
    actAsPlatformAdmin();
    AccountInvite first = service.createInvite(newTenantAdmin());
    AccountInvite second = service.createInvite(newTenantAdmin());

    service.revokeInvite(first.getId());
    verify(tenantIdAllocationClient, never()).release(anyLong());

    service.revokeInvite(second.getId());
    verify(tenantIdAllocationClient, times(1)).release(NEW_TENANT);
  }

  // --- expiry -----------------------------------------------------------------------------------

  @Test
  void anExpiredAdminInvite_Should_ReleaseItsNumber_Once() {
    AccountInvite admin = service.createInvite(agencyAdmin(NEW_AGENCY, null));
    elapse(admin);

    service.expireElapsedInvites();
    service.expireElapsedInvites();

    assertThat(reload(admin).getStatus()).isEqualTo(AccountInviteStatus.EXPIRED);
    verify(agencyIdAllocationClient, times(1)).release(NEW_AGENCY);
  }

  @Test
  void anExpiredAdminInvite_Should_KeepTheNumber_WhileAnotherAdminInviteIsPending() {
    AccountInvite first = service.createInvite(agencyAdmin(NEW_AGENCY, null));
    AccountInvite second = service.createInvite(agencyAdmin(NEW_AGENCY, null));
    elapse(first);

    service.expireElapsedInvites();

    assertThat(reload(first).getStatus()).isEqualTo(AccountInviteStatus.EXPIRED);
    assertThat(reload(second).getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    verify(agencyIdAllocationClient, never()).release(anyLong());
  }

  @Test
  void aStillValidInvite_Should_NotBeTouchedByTheExpirySweep() {
    AccountInvite admin = service.createInvite(agencyAdmin(NEW_AGENCY, null));

    service.expireElapsedInvites();

    assertThat(reload(admin).getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    verify(agencyIdAllocationClient, never()).release(anyLong());
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
    actAs(
        "tenant-admin-1",
        OWN_TENANT,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
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

  private static CreateAccountInviteCommand agencyAdmin(Long agencyId, String batch) {
    return command(
        AccountInviteTargetRole.AGENCY_ADMIN, null, null, agencyId, IdAllocationMode.MANUAL, batch);
  }

  private static CreateAccountInviteCommand counsellor(Long agencyId, String batch) {
    return command(
        AccountInviteTargetRole.COUNSELLOR, null, null, agencyId, IdAllocationMode.MANUAL, batch);
  }

  private static CreateAccountInviteCommand newTenantAdmin() {
    return command(
        AccountInviteTargetRole.TENANT_ADMIN,
        NEW_TENANT,
        IdAllocationMode.MANUAL,
        null,
        null,
        null);
  }

  private static CreateAccountInviteCommand command(
      AccountInviteTargetRole role,
      Long tenantId,
      IdAllocationMode tenantMode,
      Long agencyId,
      IdAllocationMode agencyMode,
      String batch) {
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
        null,
        batch);
  }
}
