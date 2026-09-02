package de.caritas.cob.userservice.api.port.out;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
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
}
