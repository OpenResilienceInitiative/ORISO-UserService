package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

  List<ReplyEmailDelivery>
      findTop100ByStatusAndTenantIdAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAscIdAsc(
          ReplyEmailDelivery.Status status, Long tenantId, LocalDateTime now);

  /** Tenants with due rows, the one waiting longest first. */
  @Query(
      "select d.tenantId from ReplyEmailDelivery d where d.status = :status"
          + " and d.nextAttemptAt <= :now group by d.tenantId order by min(d.nextAttemptAt)")
  List<Long> findDueTenantIds(
      @Param("status") ReplyEmailDelivery.Status status, @Param("now") LocalDateTime now);

  List<ReplyEmailDelivery> findByStatusAndAttemptedAtBefore(
      ReplyEmailDelivery.Status status, LocalDateTime cutoff);

  long countByStatus(ReplyEmailDelivery.Status status);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from ReplyEmailDelivery d where d.id = :id")
  Optional<ReplyEmailDelivery> findByIdForUpdate(@Param("id") long id);
}
