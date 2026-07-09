package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountInviteRepository extends JpaRepository<AccountInvite, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AccountInvite> findByTokenHash(String tokenHash);

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
}
