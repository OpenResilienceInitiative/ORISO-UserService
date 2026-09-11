package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * P3 duplicate-address guard, exercised against a real database rather than a mocked repository.
 *
 * <p>The identity probe ({@link IdentityEmailOwnerLookup}) is stubbed empty throughout: these tests
 * are exactly about the case it cannot answer, because a Keycloak identity is created only when an
 * invite is accepted. Everything an unaccepted invite contributes — the {@code LOWER()} comparison,
 * the status filter and the lazily-materialized expiry — is decided by SQL, so it is only really
 * proven by running that SQL.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AccountInviteService.class)
class AccountInviteRecipientEmailGuardIT {

  private static final String ADDRESS = "held@example.org";

  @Autowired private AccountInviteService service;
  @Autowired private AccountInviteRepository accountInviteRepository;

  @MockitoBean private AuthenticatedUser authenticatedUser;
  @MockitoBean private IdentityEmailOwnerLookup identityEmailOwnerLookup;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private TenantIdAllocationClient tenantIdAllocationClient;
  @MockitoBean private AgencyIdAllocationClient agencyIdAllocationClient;
  @MockitoBean private InviteAcceptUrlBuilder inviteAcceptUrlBuilder;

  @MockitoBean
  private de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService
      inviteMailDispatchService;

  @MockitoBean private InviteEmailDeliveryFailureRecorder deliveryFailureRecorder;

  @BeforeEach
  void noIdentityOwnsTheAddress() {
    when(identityEmailOwnerLookup.findByEmail(ADDRESS)).thenReturn(Optional.empty());
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(authenticatedUser.getUsername()).thenReturn("admin@example.org");
  }

  @AfterEach
  void cleanUp() {
    accountInviteRepository.deleteAll();
  }

  @Test
  void createInvite_Should_Refuse_When_ADraftInviteAlreadyHoldsTheAddress() {
    persistInvite(ADDRESS, AccountInviteStatus.DRAFT, LocalDateTime.now().plusDays(30));

    assertThatThrownBy(() -> service.createInvite(counsellorInviteFor(ADDRESS)))
        .isInstanceOf(CustomValidationHttpStatusException.class)
        .satisfies(
            thrown -> {
              var ex = (CustomValidationHttpStatusException) thrown;
              assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(ex.getCustomHttpHeaders().getFirst("X-Reason"))
                  .isEqualTo("EMAIL_NOT_AVAILABLE");
            });

    assertThat(accountInviteRepository.count()).isOne();
  }

  @Test
  void createInvite_Should_Refuse_When_AnEmailSentInviteHoldsTheAddressInAnotherCase() {
    // The column is persisted case-preserving, so the stored row and the request can disagree on
    // case while meaning the same mailbox.
    persistInvite(
        "Held@Example.ORG", AccountInviteStatus.EMAIL_SENT, LocalDateTime.now().plusDays(30));

    assertThatThrownBy(() -> service.createInvite(counsellorInviteFor("HELD@EXAMPLE.ORG")))
        .isInstanceOf(CustomValidationHttpStatusException.class);

    assertThat(accountInviteRepository.count()).isOne();
  }

  @Test
  void createInvite_Should_Succeed_When_OnlyTerminalInvitesExistForTheAddress() {
    LocalDateTime future = LocalDateTime.now().plusDays(30);
    persistInvite(ADDRESS, AccountInviteStatus.ACCEPTED, future);
    persistInvite(ADDRESS, AccountInviteStatus.REVOKED, future);
    persistInvite(ADDRESS, AccountInviteStatus.EXPIRED, future);
    persistInvite(ADDRESS, AccountInviteStatus.SUPERSEDED, future);

    AccountInvite created = service.createInvite(counsellorInviteFor(ADDRESS));

    // A withdrawn, lapsed or replaced invite must leave the mailbox free — otherwise a single
    // mistyped invite would permanently lock the person out of ever being invited again.
    assertThat(created.getId()).isNotNull();
    assertThat(created.getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
  }

  @Test
  void createInvite_Should_Succeed_When_TheOnlyOutstandingInviteHasLapsed() {
    // Expiry is materialized lazily: the status only flips to EXPIRED when somebody opens the
    // link. An invite that was never opened stays EMAIL_SENT forever, so the guard has to read
    // the date rather than trust the status.
    persistInvite(ADDRESS, AccountInviteStatus.EMAIL_SENT, LocalDateTime.now().minusDays(1));

    AccountInvite created = service.createInvite(counsellorInviteFor(ADDRESS));

    assertThat(created.getId()).isNotNull();
  }

  private CreateAccountInviteCommand counsellorInviteFor(String recipientEmail) {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.COUNSELLOR,
        7L,
        recipientEmail,
        "Ada",
        "Lovelace",
        null,
        null,
        null);
  }

  private void persistInvite(
      String recipientEmail, AccountInviteStatus status, LocalDateTime expiresAt) {
    LocalDateTime now = LocalDateTime.now();
    accountInviteRepository.save(
        AccountInvite.builder()
            .targetRole(AccountInviteTargetRole.COUNSELLOR)
            .recipientEmail(recipientEmail)
            .status(status)
            .emailVerificationStatus(EmailVerificationStatus.PENDING)
            .twoFactorStatus(TwoFactorGateStatus.NOT_REQUIRED)
            .expiresAt(expiresAt)
            .createDate(now)
            .updateDate(now)
            .build());
  }
}
