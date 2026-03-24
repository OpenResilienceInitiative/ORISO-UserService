package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.EventNotification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventNotificationRepository extends JpaRepository<EventNotification, Long> {

  List<EventNotification> findByRecipientUserIdOrderByCreateDateDesc(String recipientUserId, Pageable pageable);

  long countByRecipientUserIdAndReadDateIsNull(String recipientUserId);

  Optional<EventNotification> findByIdAndRecipientUserId(Long id, String recipientUserId);

  List<EventNotification> findByRecipientUserIdAndReadDateIsNull(String recipientUserId);

  void deleteByRecipientUserId(String recipientUserId);
}

