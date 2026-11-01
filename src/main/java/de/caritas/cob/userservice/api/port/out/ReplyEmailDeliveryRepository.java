package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReplyEmailDeliveryRepository extends JpaRepository<ReplyEmailDelivery, Long> {
  void deleteByRecipientKindAndRecipientUserId(
      ReplyEmailDelivery.RecipientKind recipientKind, String recipientUserId);

  void deleteBySourceMatrixUserId(String sourceMatrixUserId);

  boolean existsByRecipientKindAndRecipientUserIdAndEventKey(
      ReplyEmailDelivery.RecipientKind recipientKind, String recipientUserId, String eventKey);

  /** Ids of one tenant's due rows, oldest first; ids only, so a batch loads no entities. */
  @Query(
      "select d.id from ReplyEmailDelivery d where d.status = :status and d.tenantId = :tenantId"
          + " and d.nextAttemptAt <= :now order by d.nextAttemptAt asc, d.createdAt asc, d.id asc")
  List<Long> findDueIds(
      @Param("status") ReplyEmailDelivery.Status status,
      @Param("tenantId") long tenantId,
      @Param("now") LocalDateTime now,
      Limit limit);

  /** Tenants with due rows, in a stable order so the writer can rotate through them. */
  @Query(
      "select distinct d.tenantId from ReplyEmailDelivery d where d.status = :status"
          + " and d.nextAttemptAt <= :now order by d.tenantId")
  List<Long> findDueTenantIds(
      @Param("status") ReplyEmailDelivery.Status status, @Param("now") LocalDateTime now);

  List<ReplyEmailDelivery> findByStatusAndAttemptedAtBefore(
      ReplyEmailDelivery.Status status, LocalDateTime cutoff);

  long countByStatus(ReplyEmailDelivery.Status status);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from ReplyEmailDelivery d where d.id = :id")
  Optional<ReplyEmailDelivery> findByIdForUpdate(@Param("id") long id);
}
