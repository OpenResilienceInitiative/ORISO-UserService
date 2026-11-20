package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdReservationReleaseTaskRepository;
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
import java.time.LocalDateTime;
import java.util.List;
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

/**
 * An admin deletes an invitation (ORISO-Admin#1522): it is revoked at once, its link stops working,
 * its address is free again, and it is no longer listed. Rows are removed later by the nightly
 * purge.
 */
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
  AccountInviteDeleteIT.CallerConfig.class
})
@WithTenant(AccountInviteDeleteIT.OWN_TENANT)
class AccountInviteDeleteIT {

  static final long OWN_TENANT = 1L;
  private static final long EXISTING_AGENCY = 1L;
  private static final long NEW_AGENCY = 500L;
  private static final String ADMIN_ID = "tenant-admin-1";

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
    when(agencyIdAllocationClient.getAvailability(EXISTING_AGENCY))
        .thenReturn(IdAllocationStatus.ASSIGNED);
    when(agencyIdAllocationClient.getAvailability(NEW_AGENCY)).thenReturn(IdAllocationStatus.FREE);
    when(agencyIdAllocationClient.reserve(NEW_AGENCY, OWN_TENANT))
        .thenAnswer(
            call -> {
              when(agencyIdAllocationClient.getAvailability(NEW_AGENCY))
                  .thenReturn(IdAllocationStatus.RESERVED);
              return NEW_AGENCY;
            });
    when(agencyIdAllocationClient.release(anyLong())).thenReturn(true);
    when(agencyService.getAgenciesWithoutCaching(List.of(EXISTING_AGENCY)))
        .thenReturn(List.of(new AgencyDTO().id(EXISTING_AGENCY).tenantId(OWN_TENANT)));
    when(agencyFacts.find(EXISTING_AGENCY))
        .thenReturn(
            Optional.of(new AgencyFacts.Agency(EXISTING_AGENCY, OWN_TENANT, false, List.of(11L))));
    Tenants.actAs(
        caller,
        ADMIN_ID,
        OWN_TENANT,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  @AfterEach
  void cleanUp() {
    accountInviteRepository.deleteAll();
    releaseTaskRepository.deleteAll();
  }

  @Test
  void deletingAnOpenInvite_Should_RevokeIt_AndMarkItForThePurge() {
    AccountInvite invite = service.createInvite(counsellor("open@example.org"));

    service.deleteInvite(invite.getId());

    AccountInvite stored = reload(invite);
    assertThat(stored.getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    assertThat(stored.getRevokedByUserId()).isEqualTo(ADMIN_ID);
    assertThat(stored.getActiveRecipientKey()).isNull();
    assertThat(stored.getDeletionRequestedAt()).isNotNull();
    assertThat(stored.getDeletionRequestedByUserId()).isEqualTo(ADMIN_ID);
  }

  @Test
  void aDeletedInvite_Should_NoLongerBeListed() {
    AccountInvite kept = service.createInvite(counsellor("kept@example.org"));
    AccountInvite deleted = service.createInvite(counsellor("deleted@example.org"));

    service.deleteInvite(deleted.getId());

    assertThat(service.listAllInvites(null, OWN_TENANT, null))
        .extracting(AccountInvite::getId)
        .containsExactly(kept.getId());
    assertThat(service.listInvites(null, null, OWN_TENANT, "deleted", 0, 20).getContent())
        .isEmpty();
  }

  @Test
  void theSameAddress_Should_BeInvitableAgain_AfterTheInviteWasDeleted() {
    AccountInvite first = service.createInvite(counsellor("again@example.org"));

    service.deleteInvite(first.getId());
    AccountInvite second = service.createInvite(counsellor("again@example.org"));

    assertThat(reload(second).getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
  }

  /**
   * The ORISO-UserService#1604 shape: an admin creation that failed while preparing the setup mail
   * left a DRAFT that held the address. Deleting it frees the address.
   */
  @Test
  void aStuckSetupDraft_Should_BeDeletable_AndFreeItsAddress() {
    String email = "stuck@example.org";
    AccountInvite stuck =
        accountInviteRepository.save(
            row(AccountInviteStatus.DRAFT, email)
                .purpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP)
                .provisioningFailureReason("SETUP_PREPARATION_FAILED")
                .build());

    service.deleteInvite(stuck.getId());
    AccountInvite fresh = service.createInvite(counsellor(email));

    assertThat(reload(stuck).getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    assertThat(reload(stuck).getActiveRecipientKey()).isNull();
    assertThat(reload(fresh).getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
  }

  @Test
  void deletingAnExpiredInvite_Should_KeepItsStatus_AndHideIt() {
    AccountInvite expired =
        accountInviteRepository.save(
            row(AccountInviteStatus.EXPIRED, "expired@example.org")
                .activeRecipientKey(null)
                .build());

    service.deleteInvite(expired.getId());

    AccountInvite stored = reload(expired);
    assertThat(stored.getStatus()).isEqualTo(AccountInviteStatus.EXPIRED);
    assertThat(stored.getDeletionRequestedAt()).isNotNull();
    assertThat(service.listAllInvites(null, OWN_TENANT, null)).isEmpty();
  }

  @Test
  void deletingTwice_Should_KeepTheFirstMark() {
    AccountInvite invite = service.createInvite(counsellor("twice@example.org"));
    service.deleteInvite(invite.getId());
    LocalDateTime firstMark = reload(invite).getDeletionRequestedAt();

    service.deleteInvite(invite.getId());

    assertThat(reload(invite).getDeletionRequestedAt()).isEqualTo(firstMark);
  }

  @Test
  void deletingAnAcceptedInvite_Should_BeRefused_BecauseItsAccountExists() {
    AccountInvite accepted =
        accountInviteRepository.save(
            row(AccountInviteStatus.ACCEPTED, "accepted@example.org")
                .activeRecipientKey(null)
                .build());

    assertThatThrownBy(() -> service.deleteInvite(accepted.getId()))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            e -> {
              assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.getCustomHttpHeaders().getFirst("X-Reason"))
                  .isEqualTo(HttpStatusExceptionReason.INVITE_ALREADY_ACCEPTED.name());
            });
    assertThat(reload(accepted).getDeletionRequestedAt()).isNull();
  }

  @Test
  void deletingASetupLinkWhileItsAccountIsBeingSetUp_Should_AskToTryAgain() {
    AccountInvite inProgress =
        accountInviteRepository.save(
            row(AccountInviteStatus.EMAIL_SENT, "busy@example.org")
                .purpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP)
                .provisioningStatus(AccountInviteProvisioningStatus.IN_PROGRESS)
                .build());

    assertThatThrownBy(() -> service.deleteInvite(inProgress.getId()))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            e -> {
              assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.getCustomHttpHeaders().getFirst("X-Reason"))
                  .isEqualTo(HttpStatusExceptionReason.INVITE_BUSY.name());
            });
    AccountInvite stored = reload(inProgress);
    assertThat(stored.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(stored.getDeletionRequestedAt()).isNull();
  }

  @Test
  void deletingTheOnlyAdminInviteOfANewAgency_Should_ReleaseItsNumber() {
    AccountInvite admin =
        service.createInvite(
            command(
                AccountInviteTargetRole.AGENCY_ADMIN,
                "admin@example.org",
                NEW_AGENCY,
                IdAllocationMode.MANUAL));

    service.deleteInvite(admin.getId());

    verify(agencyIdAllocationClient, times(1)).release(NEW_AGENCY);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private AccountInvite reload(AccountInvite invite) {
    return accountInviteRepository.findById(invite.getId()).orElseThrow();
  }

  private static AccountInvite.AccountInviteBuilder row(AccountInviteStatus status, String email) {
    return AccountInvite.builder()
        .targetRole(AccountInviteTargetRole.COUNSELLOR)
        .tenantId(OWN_TENANT)
        .recipientEmail(email)
        .activeRecipientKey(email)
        .agencyId(EXISTING_AGENCY)
        .agencyIdAllocationMode(IdAllocationMode.EXISTING)
        .status(status)
        .expiresAt(LocalDateTime.now().plusDays(30))
        .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
        .emailVerificationStatus(EmailVerificationStatus.PENDING)
        .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
        .createDate(LocalDateTime.now())
        .updateDate(LocalDateTime.now());
  }

  private static CreateAccountInviteCommand counsellor(String email) {
    return command(
        AccountInviteTargetRole.COUNSELLOR, email, EXISTING_AGENCY, IdAllocationMode.EXISTING);
  }

  private static CreateAccountInviteCommand command(
      AccountInviteTargetRole role, String email, Long agencyId, IdAllocationMode agencyMode) {
    return new CreateAccountInviteCommand(
        role,
        null,
        email == null ? UUID.randomUUID() + "@example.org" : email,
        "Ada",
        "Lovelace",
        agencyId,
        null,
        null,
        null,
        agencyMode,
        null);
  }
}
