package de.caritas.cob.userservice.api.port.out;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteProvisioningStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInvitePurpose;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.InviteProgress;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * DPA signed write-back semantics (ORISO-Admin#896, epic #725): {@code markDpaSigned} is the only
 * writer of {@code dpa_signed_at} for forwarded signatures, and its idempotency lives in the query
 * itself ({@code dpa_signed_at IS NULL} guard) — which only a real database can prove.
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
class AccountInviteRepositoryIT {

  private static final LocalDateTime SIGNED_AT =
      LocalDateTime.of(2026, 8, 14, 9, 15).truncatedTo(ChronoUnit.SECONDS);

  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private AdminRepository adminRepository;

  @Test
  void existingAccountSetupClaimIsSingleUseAndCompletionErasesVerifier() {
    var invite = persistedSetup("admin-11", LocalDateTime.now().plusDays(1));
    var now = LocalDateTime.now();

    assertEquals(
        1,
        accountInviteRepository.claimExistingAccountSetup(
            invite.getId(),
            AccountInvitePurpose.EXISTING_ACCOUNT_SETUP,
            AccountInviteStatus.EMAIL_SENT,
            List.of(
                AccountInviteProvisioningStatus.PENDING, AccountInviteProvisioningStatus.FAILED),
            AccountInviteProvisioningStatus.IN_PROGRESS,
            now));
    assertEquals(
        0,
        accountInviteRepository.claimExistingAccountSetup(
            invite.getId(),
            AccountInvitePurpose.EXISTING_ACCOUNT_SETUP,
            AccountInviteStatus.EMAIL_SENT,
            List.of(
                AccountInviteProvisioningStatus.PENDING, AccountInviteProvisioningStatus.FAILED),
            AccountInviteProvisioningStatus.IN_PROGRESS,
            now));
    assertEquals(
        0,
        accountInviteRepository.completeExistingAccountSetup(
            invite.getId(),
            AccountInvitePurpose.EXISTING_ACCOUNT_SETUP,
            AccountInviteStatus.EMAIL_SENT,
            AccountInviteProvisioningStatus.IN_PROGRESS,
            AccountInviteStatus.ACCEPTED,
            AccountInviteProvisioningStatus.COMPLETED,
            EmailVerificationStatus.VERIFIED,
            "other-admin",
            now));
    assertEquals(
        1,
        accountInviteRepository.completeExistingAccountSetup(
            invite.getId(),
            AccountInvitePurpose.EXISTING_ACCOUNT_SETUP,
            AccountInviteStatus.EMAIL_SENT,
            AccountInviteProvisioningStatus.IN_PROGRESS,
            AccountInviteStatus.ACCEPTED,
            AccountInviteProvisioningStatus.COMPLETED,
            EmailVerificationStatus.VERIFIED,
            "admin-11",
            now));

    var completed = reload(invite);
    assertEquals(AccountInviteStatus.ACCEPTED, completed.getStatus());
    assertNull(completed.getActiveSetupIdentityKey());
    assertNull(completed.getInitialPasswordVerifier());
  }

  @Test
  void expiryRetainsOnlyPrivateRecoveryBindingAndNeverTouchesAnInFlightPasswordUpdate() {
    var expired = persistedSetup("expired-admin", LocalDateTime.now().minusDays(1));
    var inFlight = persistedSetup("active-admin", LocalDateTime.now().minusDays(1));
    inFlight.setProvisioningStatus(AccountInviteProvisioningStatus.IN_PROGRESS);
    accountInviteRepository.saveAndFlush(inFlight);

    assertEquals(
        1,
        accountInviteRepository.expireElapsedExistingAccountSetup(
            AccountInvitePurpose.EXISTING_ACCOUNT_SETUP,
            List.of(AccountInviteStatus.DRAFT, AccountInviteStatus.EMAIL_SENT),
            AccountInviteStatus.EXPIRED,
            AccountInviteProvisioningStatus.IN_PROGRESS,
            LocalDateTime.now()));

    assertEquals(AccountInviteStatus.EXPIRED, reload(expired).getStatus());
    assertEquals("expired-admin", reload(expired).getActiveSetupIdentityKey());
    assertEquals("salted-verifier", reload(expired).getInitialPasswordVerifier());
    assertEquals(AccountInviteStatus.EMAIL_SENT, reload(inFlight).getStatus());
    assertEquals("salted-verifier", reload(inFlight).getInitialPasswordVerifier());

    assertEquals(
        1,
        accountInviteRepository.revokeWhileStatusIn(
            expired.getId(),
            List.of(AccountInviteStatus.EXPIRED),
            "operator-1",
            LocalDateTime.now()));
    assertEquals(AccountInviteStatus.REVOKED, reload(expired).getStatus());
    assertNull(reload(expired).getActiveSetupIdentityKey());
    assertNull(reload(expired).getInitialPasswordVerifier());
  }

  private AccountInvite persistedSetup(String identityId, LocalDateTime expiresAt) {
    return accountInviteRepository.saveAndFlush(
        AccountInvite.builder()
            .purpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP)
            .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
            .tenantId(42L)
            .recipientEmail(identityId + "@example.org")
            .tokenHash(UUID.randomUUID().toString())
            .provisionedUserId(identityId)
            .setupBoundUsername(identityId)
            .activeSetupIdentityKey(identityId)
            .initialPasswordVerifier("salted-verifier")
            .status(AccountInviteStatus.EMAIL_SENT)
            .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
            .expiresAt(expiresAt)
            .createDate(LocalDateTime.now())
            .build());
  }

  @AfterEach
  void reset() {
    accountInviteRepository.deleteAll();
  }

  @Test
  void markDpaSigned_stampsOnlyTheTenantAdminInvitesOfTheTenant() {
    var target = persistedInvite(42L, AccountInviteTargetRole.TENANT_ADMIN, null);
    var otherTenant = persistedInvite(43L, AccountInviteTargetRole.TENANT_ADMIN, null);
    var counsellor = persistedInvite(42L, AccountInviteTargetRole.COUNSELLOR, null);

    int stamped =
        accountInviteRepository.markDpaSigned(42L, AccountInviteTargetRole.TENANT_ADMIN, SIGNED_AT);

    assertEquals(1, stamped);
    assertEquals(SIGNED_AT, reload(target).getDpaSignedAt());
    assertNull(reload(otherTenant).getDpaSignedAt());
    assertNull(reload(counsellor).getDpaSignedAt());
  }

  @Test
  void markDpaSigned_neverRegressesAnExistingTimestamp() {
    // a repeated signature notice must not move an already-stamped invite — the guard is in the
    // query, not in the caller
    var alreadyStamped =
        persistedInvite(42L, AccountInviteTargetRole.TENANT_ADMIN, SIGNED_AT.minusDays(1));

    int stamped =
        accountInviteRepository.markDpaSigned(42L, AccountInviteTargetRole.TENANT_ADMIN, SIGNED_AT);

    assertEquals(0, stamped);
    assertEquals(SIGNED_AT.minusDays(1), reload(alreadyStamped).getDpaSignedAt());
  }

  @Test
  void reconciliationRequiresTheCurrentTenantAdminAndPreservesExistingStamp() {
    Admin owner = persistedAdmin(42L, AdminType.TENANT);
    Admin foreign = persistedAdmin(43L, AdminType.TENANT);
    Admin wrongRole = persistedAdmin(42L, AdminType.AGENCY);
    var target = reconciliationInvite(42L, owner.getId(), null, AccountInviteStatus.ACCEPTED);
    target.setTwoFactorStatus(TwoFactorGateStatus.ACTIVE);
    target.setTwoFactorActivatedAt(SIGNED_AT.minusDays(1));
    accountInviteRepository.saveAndFlush(target);
    var wrongTenant =
        reconciliationInvite(42L, foreign.getId(), null, AccountInviteStatus.ACCEPTED);
    var agencyAdmin =
        reconciliationInvite(42L, wrongRole.getId(), null, AccountInviteStatus.ACCEPTED);
    var notAccepted =
        reconciliationInvite(42L, owner.getId(), null, AccountInviteStatus.EMAIL_SENT);
    var conflict =
        reconciliationInvite(42L, owner.getId(), foreign.getId(), AccountInviteStatus.ACCEPTED);
    var setup = reconciliationInvite(43L, null, foreign.getId(), AccountInviteStatus.ACCEPTED);
    var stamped = reconciliationInvite(42L, owner.getId(), null, AccountInviteStatus.ACCEPTED);
    stamped.setDpaSignedAt(SIGNED_AT.minusDays(2));
    accountInviteRepository.saveAndFlush(stamped);
    Admin zeroTenantOwner = persistedAdmin(0L, AdminType.TENANT);
    var zeroTenant =
        reconciliationInvite(0L, zeroTenantOwner.getId(), null, AccountInviteStatus.ACCEPTED);
    Admin negativeTenantOwner = persistedAdmin(-1L, AdminType.TENANT);
    var negativeTenant =
        reconciliationInvite(-1L, negativeTenantOwner.getId(), null, AccountInviteStatus.ACCEPTED);
    long upperBound =
        TenantContext.supplyAcrossTenants(accountInviteRepository::findDpaReconciliationUpperBound)
            .orElseThrow();
    assertEquals(negativeTenant.getId(), upperBound);
    var first =
        TenantContext.supplyAcrossTenants(
            () ->
                accountInviteRepository.findDpaReconciliationCandidates(
                    0L, upperBound, PageRequest.of(0, 1)));
    var next =
        TenantContext.supplyAcrossTenants(
            () ->
                accountInviteRepository.findDpaReconciliationCandidates(
                    target.getId(), upperBound, PageRequest.of(0, 10)));

    assertEquals(target.getId(), first.getFirst().getInviteId());
    assertEquals(owner.getId(), first.getFirst().getActorId());
    assertEquals(
        List.of(setup.getId()), next.stream().map(candidate -> candidate.getInviteId()).toList());
    assertEquals(43L, next.getFirst().getTenantId());
    assertEquals(foreign.getId(), next.getFirst().getActorId());
    TenantContext.runIn(
        42L,
        () -> {
          assertEquals(
              0,
              accountInviteRepository.reconcileDpaSigned(
                  target.getId(), 43L, owner.getId(), SIGNED_AT));
          assertEquals(
              0,
              accountInviteRepository.reconcileDpaSigned(
                  target.getId(), 42L, foreign.getId(), SIGNED_AT));
          for (AccountInvite unrelated : List.of(wrongTenant, agencyAdmin, notAccepted, conflict)) {
            assertEquals(
                0,
                accountInviteRepository.reconcileDpaSigned(
                    unrelated.getId(), 42L, unrelated.getAcceptedByUserId(), SIGNED_AT));
          }

          assertEquals(
              1,
              accountInviteRepository.reconcileDpaSigned(
                  target.getId(), 42L, owner.getId(), SIGNED_AT));
          assertEquals(
              0,
              accountInviteRepository.reconcileDpaSigned(
                  target.getId(), 42L, owner.getId(), SIGNED_AT.plusDays(1)));
          assertEquals(
              0,
              accountInviteRepository.reconcileDpaSigned(
                  stamped.getId(), 42L, owner.getId(), SIGNED_AT));
        });
    TenantContext.runIn(
        43L,
        () ->
            assertEquals(
                1,
                accountInviteRepository.reconcileDpaSigned(
                    setup.getId(), 43L, foreign.getId(), SIGNED_AT)));
    for (AccountInvite invalidTenant : List.of(zeroTenant, negativeTenant)) {
      TenantContext.runIn(
          invalidTenant.getTenantId(),
          () ->
              assertEquals(
                  0,
                  accountInviteRepository.reconcileDpaSigned(
                      invalidTenant.getId(),
                      invalidTenant.getTenantId(),
                      invalidTenant.getAcceptedByUserId(),
                      SIGNED_AT)));
      assertNull(reload(invalidTenant).getDpaSignedAt());
    }
    assertEquals(SIGNED_AT, reload(setup).getDpaSignedAt());
    assertEquals(SIGNED_AT.minusDays(2), reload(stamped).getDpaSignedAt());
    var reconciled = reload(target);
    assertEquals(SIGNED_AT, reconciled.getDpaSignedAt());
    var tracker = InviteProgress.of(reconciled, null, null, SIGNED_AT.plusDays(1));
    assertEquals(InviteProgress.Phase.DONE, tracker.phase());
    assertEquals(SIGNED_AT, tracker.completedAt());
  }

  private Admin persistedAdmin(Long tenantId, AdminType role) {
    String id = UUID.randomUUID().toString();
    return adminRepository.saveAndFlush(
        Admin.builder()
            .id(id)
            .tenantId(tenantId)
            .username(id)
            .firstName("Synthetic")
            .lastName("Admin")
            .email(id + "@example.org")
            .type(role)
            .build());
  }

  private AccountInvite reconciliationInvite(
      Long tenantId, String acceptedBy, String provisioned, AccountInviteStatus status) {
    var invite = persistedInvite(tenantId, AccountInviteTargetRole.TENANT_ADMIN, null);
    invite.setStatus(status);
    invite.setAcceptedByUserId(acceptedBy);
    invite.setProvisionedUserId(provisioned);
    return accountInviteRepository.saveAndFlush(invite);
  }

  /**
   * {@code findAllByFilters}' search leg (ORISO-UserService#479): the service passes an
   * already-normalized {@code search} term and, when the raw query is purely numeric, a parsed
   * {@code searchTenantId} — this proves the JPQL clause itself matches recipient email, first
   * name, last name, and exact tenant ID, and that {@code null}/blank leaves the result set
   * untouched.
   */
  @Test
  void findAllByFilters_Should_matchRecipientEmail_When_searchIsSubstring() {
    var target =
        persistedInviteWithEmail(1L, AccountInviteTargetRole.COUNSELLOR, "jane.doe@example.org");
    persistedInviteWithEmail(1L, AccountInviteTargetRole.COUNSELLOR, "other@example.org");

    Page<AccountInvite> result = search("jane.doe");

    assertEquals(List.of(target.getId()), ids(result));
  }

  @Test
  void findAllByFilters_Should_matchFirstOrLastName_CaseInsensitively() {
    var target =
        persistedInviteWithName(
            1L, AccountInviteTargetRole.COUNSELLOR, "a@example.org", "Jane", "Doe");
    persistedInviteWithName(
        1L, AccountInviteTargetRole.COUNSELLOR, "b@example.org", "John", "Smith");

    assertEquals(List.of(target.getId()), ids(search("jane")));
    assertEquals(List.of(target.getId()), ids(search("doe")));
  }

  @Test
  void findAllByFilters_Should_matchTenantIdExactly_When_searchIsNumeric() {
    var target = persistedInviteWithEmail(42L, AccountInviteTargetRole.COUNSELLOR, "a@example.org");
    persistedInviteWithEmail(420L, AccountInviteTargetRole.COUNSELLOR, "b@example.org");

    Page<AccountInvite> result =
        accountInviteRepository.findAllByFilters(
            null, null, null, "42", 42L, PageRequest.of(0, 20));

    assertEquals(List.of(target.getId()), ids(result));
  }

  @Test
  void findAllByFilters_Should_returnAllRows_When_searchIsNull() {
    var first = persistedInviteWithEmail(1L, AccountInviteTargetRole.COUNSELLOR, "a@example.org");
    var second = persistedInviteWithEmail(2L, AccountInviteTargetRole.COUNSELLOR, "b@example.org");

    Page<AccountInvite> result = search(null);

    assertEquals(2, result.getTotalElements());
    assertEquals(Set.of(first.getId(), second.getId()), Set.copyOf(ids(result)));
  }

  private Page<AccountInvite> search(String search) {
    return accountInviteRepository.findAllByFilters(
        null, null, null, search, null, PageRequest.of(0, 20));
  }

  private List<Long> ids(Page<AccountInvite> page) {
    return page.getContent().stream().map(AccountInvite::getId).toList();
  }

  private AccountInvite reload(AccountInvite invite) {
    return accountInviteRepository.findById(invite.getId()).orElseThrow();
  }

  private AccountInvite persistedInvite(
      Long tenantId, AccountInviteTargetRole targetRole, LocalDateTime dpaSignedAt) {
    return accountInviteRepository.save(
        AccountInvite.builder()
            .targetRole(targetRole)
            .tenantId(tenantId)
            .recipientEmail("invitee@example.org")
            .tokenHash(UUID.randomUUID().toString())
            .expiresAt(LocalDateTime.now().plusDays(10))
            .dpaSignedAt(dpaSignedAt)
            .createDate(LocalDateTime.now())
            .build());
  }

  private AccountInvite persistedInviteWithEmail(
      Long tenantId, AccountInviteTargetRole targetRole, String recipientEmail) {
    return persistedInviteWithName(tenantId, targetRole, recipientEmail, null, null);
  }

  private AccountInvite persistedInviteWithName(
      Long tenantId,
      AccountInviteTargetRole targetRole,
      String recipientEmail,
      String firstName,
      String lastName) {
    return accountInviteRepository.save(
        AccountInvite.builder()
            .targetRole(targetRole)
            .tenantId(tenantId)
            .recipientEmail(recipientEmail)
            .firstName(firstName)
            .lastName(lastName)
            .tokenHash(UUID.randomUUID().toString())
            .expiresAt(LocalDateTime.now().plusDays(10))
            .createDate(LocalDateTime.now())
            .build());
  }
}
