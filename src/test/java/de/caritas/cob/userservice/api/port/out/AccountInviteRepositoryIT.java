package de.caritas.cob.userservice.api.port.out;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
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
