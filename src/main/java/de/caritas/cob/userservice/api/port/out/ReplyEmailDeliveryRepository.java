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
  void deleteByRecipientUserId(String recipientUserId);

  List<ReplyEmailDelivery> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
      ReplyEmailDelivery.Status status, LocalDateTime now);

  List<ReplyEmailDelivery> findByStatusAndAttemptedAtBefore(
      ReplyEmailDelivery.Status status, LocalDateTime cutoff);

  long countByStatus(ReplyEmailDelivery.Status status);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from ReplyEmailDelivery d where d.id = :id")
  Optional<ReplyEmailDelivery> findByIdForUpdate(@Param("id") long id);
}
