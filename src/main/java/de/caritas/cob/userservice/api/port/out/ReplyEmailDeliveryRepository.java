package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplyEmailDeliveryRepository extends JpaRepository<ReplyEmailDelivery, Long> {
  void deleteByRecipientUserId(String recipientUserId);

  List<ReplyEmailDelivery> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
      ReplyEmailDelivery.Status status, LocalDateTime now);

  List<ReplyEmailDelivery> findByStatusAndAttemptedAtBefore(
      ReplyEmailDelivery.Status status, LocalDateTime cutoff);
}
