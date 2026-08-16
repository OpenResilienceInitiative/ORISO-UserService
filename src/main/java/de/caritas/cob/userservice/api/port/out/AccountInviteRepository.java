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
}
