package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountInviteRepository extends JpaRepository<AccountInvite, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AccountInvite> findByTokenHash(String tokenHash);

  /**
   * Re-reads one invite row under the same PESSIMISTIC_WRITE lock as {@link
   * #findByTokenHash(String)} — the read half of a targeted-field update (#1008 review).
   *
   * <p>The public onboarding flows deliberately drop out of their transaction for every remote call
   * (Agency/Topic/Keycloak/DPA), so the invite they carry across such a call is a DETACHED
   * snapshot. Saving that snapshot afterwards would merge every column as it looked BEFORE the call
   * — and because {@link AccountInvite} carries neither {@code @Version} nor
   * {@code @DynamicUpdate}, a concurrent update of an unrelated field would be silently overwritten
   * (lost update). Writes therefore re-read the row here, inside a short transaction, and set only
   * the fields they own.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT i FROM AccountInvite i WHERE i.id = :id")
  Optional<AccountInvite> findByIdForUpdate(@Param("id") Long id);

  /**
   * Lock-free role probe for the shared public onboarding routes (#1008 review): the route decides
   * from the invite's target role which onboarding flow answers, and that flow then loads the very
   * same row under {@link #findByTokenHash}'s pessimistic lock. Reading the whole entity through
   * the locking finder just to read one enum would request a write lock on the row TWICE per
   * request, so the dispatch probe projects the role alone and takes no lock at all.
   */
  @Query("SELECT i.targetRole FROM AccountInvite i WHERE i.tokenHash = :tokenHash")
  Optional<AccountInviteTargetRole> findTargetRoleByTokenHash(@Param("tokenHash") String tokenHash);

  /**
   * Atomic single-use claim of an invite (hardening for ORISO-Admin#569): flips {@code EMAIL_SENT
   * -> ACCEPTED} as one guarded UPDATE, so of two concurrent accepts exactly one sees an affected
   * row — independent of whether the database honored the pessimistic lock hint on the token
   * lookup. Also stamps the email gate: an accept via the mailed link IS the verification.
   *
   * @return 1 when this call claimed the invite, 0 when another transaction already changed the
   *     status away from {@code EMAIL_SENT}
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE AccountInvite i"
          + " SET i.status ="
          + " de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus.ACCEPTED,"
          + " i.acceptedAt = :now,"
          + " i.acceptedByUserId = :acceptedByUserId,"
          + " i.emailVerificationStatus ="
          + " de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus.VERIFIED,"
          + " i.updateDate = :now"
          + " WHERE i.id = :id AND i.status ="
          + " de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus.EMAIL_SENT")
  int claimForAcceptance(
      @Param("id") Long id,
      @Param("acceptedByUserId") String acceptedByUserId,
      @Param("now") LocalDateTime now);

  boolean existsByTenantIdAndTargetRoleAndStatusIn(
      Long tenantId, AccountInviteTargetRole targetRole, Collection<AccountInviteStatus> statuses);

  /**
   * P3 duplicate-address guard: counts the invites that still hold {@code recipientEmail}, i.e. the
   * ones a recipient could still redeem. The identity probe cannot see these — a Keycloak user is
   * only created when an invite is accepted, so a DRAFT or EMAIL_SENT invite is invisible there.
   *
   * <p>Deliberately not a derived query:
   *
   * <ul>
   *   <li>{@code LOWER(...)} is explicit because {@code recipient_email} is persisted trimmed but
   *       case-preserving, and the two databases involved disagree on the default: MariaDB's {@code
   *       utf8mb4_*_ci} collation compares case-insensitively, H2 (tests) does not. The guard must
   *       not depend on which one it runs against.
   *   <li>The expiry clause reflects that expiry is materialized lazily — {@code EXPIRED} is only
   *       stamped when someone opens the link (see {@code AccountInviteService#acceptInvite}), so a
   *       never-opened invite stays {@code EMAIL_SENT} forever. Without the date check a lapsed
   *       invite would permanently block its address, leaving the admin no way to re-invite.
   * </ul>
   *
   * <p>The caller passes the non-terminal statuses; terminal ones ({@code ACCEPTED}, {@code
   * EXPIRED}, {@code REVOKED}, {@code SUPERSEDED}) must never block a fresh invite.
   *
   * <p><b>Why this stays advisory instead of becoming a unique constraint.</b> The rule above is
   * not expressible as one. MariaDB has no partial indexes, so it would have to become a persisted
   * generated column plus a unique index — and that column cannot contain {@code NOW()}, so it
   * could not carry the expiry clause. The resulting constraint would be strictly harsher than the
   * rule it is meant to enforce and would reject the legitimate re-invite after a lapsed invite,
   * with no way for an admin to recover as long as expiry stays lazily materialized. On top of
   * that, rows violating it already exist in the wild (two Pre-Dev addresses each hold two
   * unaccepted invites), so the changeset would need a destructive data migration before it could
   * even apply, and Liquibase does not run in the test profile — the migration would ship with no
   * test coverage at all. A constraint becomes worth revisiting once invite expiry is swept
   * eagerly; until then the service-side guard is the enforcement point.
   */
  @Query(
      "SELECT COUNT(i) FROM AccountInvite i"
          + " WHERE LOWER(i.recipientEmail) = :recipientEmail"
          + " AND i.status IN :statuses"
          + " AND (i.expiresAt IS NULL OR i.expiresAt > :now)")
  long countNonTerminalInvitesForRecipientEmail(
      @Param("recipientEmail") String recipientEmail,
      @Param("statuses") Collection<AccountInviteStatus> statuses,
      @Param("now") LocalDateTime now);

  @Query(
      "SELECT i FROM AccountInvite i"
          + " WHERE (:tenantId IS NULL OR i.tenantId = :tenantId)"
          + " AND (:targetRole IS NULL OR i.targetRole = :targetRole)"
          + " AND (:status IS NULL OR i.status = :status)"
          + " ORDER BY i.createDate DESC")
  Page<AccountInvite> findAllByFilters(
      @Param("tenantId") Long tenantId,
      @Param("targetRole") AccountInviteTargetRole targetRole,
      @Param("status") AccountInviteStatus status,
      Pageable pageable);

  List<AccountInvite> findAllByAcceptedByUserIdAndTwoFactorStatus(
      String acceptedByUserId, TwoFactorGateStatus twoFactorStatus);

  /**
   * Newest onboarding invite of the tenant that forwarded the DPA from the pre-account wizard — the
   * recipient anchor for its DPA_SIGNED_NOTICE (ORISO-UserService#1005).
   */
  Optional<AccountInvite>
      findFirstByTenantIdAndTargetRoleAndDpaForwardedAtIsNotNullOrderByDpaForwardedAtDesc(
          Long tenantId, AccountInviteTargetRole targetRole);

  /**
   * Writes the tenant's DPA signature timestamp back to its invites so the Admin invite progress
   * board can prove the final "Vertrag unterschrieben" phase (ORISO-Admin#896, epic #725).
   * Idempotent by construction: only rows whose {@code dpa_signed_at} is still null are touched, so
   * repeated signature notices can never regress or overwrite an existing timestamp.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE AccountInvite i SET i.dpaSignedAt = :signedAt WHERE i.tenantId = :tenantId"
          + " AND i.targetRole = :targetRole AND i.dpaSignedAt IS NULL")
  int markDpaSigned(
      @Param("tenantId") Long tenantId,
      @Param("targetRole") AccountInviteTargetRole targetRole,
      @Param("signedAt") LocalDateTime signedAt);
}
