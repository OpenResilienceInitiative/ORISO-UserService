package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.EventNotification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface EventNotificationRepository extends JpaRepository<EventNotification, Long> {

  List<EventNotification> findByRecipientUserIdOrderByCreateDateDescIdDesc(
      String recipientUserId, Pageable pageable);

  long countByRecipientUserIdAndReadDateIsNull(String recipientUserId);

  Optional<EventNotification> findByIdAndRecipientUserId(Long id, String recipientUserId);

  List<EventNotification> findByRecipientUserIdAndReadDateIsNull(String recipientUserId);

  boolean existsByRecipientUserIdAndDeduplicationKey(
      String recipientUserId, String deduplicationKey);

  /**
   * Removes a recipient's whole notification feed. Used by the user-facing "clear feed" action and
   * by account deletion, which must not leave notification rows behind (KDG epic #1010, task 2b).
   */
  @Transactional
  void deleteByRecipientUserId(String recipientUserId);
}
