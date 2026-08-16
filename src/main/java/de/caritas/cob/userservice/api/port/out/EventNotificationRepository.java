package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.EventNotification;
import java.time.LocalDateTime;
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

  /**
   * Deletes notifications the recipient has already read and that are older than the cutoff (KDG
   * epic #1010, task 2a). A read notification has served its purpose; keeping it preserves a
   * second-precision record of when the recipient looked at what.
   *
   * @param cutoff read timestamps strictly before this instant are purged
   * @return number of deleted rows
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from EventNotification e where e.readDate is not null and e.readDate < :cutoff")
  int deleteReadBefore(@Param("cutoff") LocalDateTime cutoff);

  /**
   * Deletes notifications older than the cutoff regardless of read state (KDG epic #1010, task 2a),
   * so an unread feed cannot grow without bound.
   *
   * @param cutoff creation timestamps strictly before this instant are purged
   * @return number of deleted rows
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from EventNotification e where e.createDate < :cutoff")
  int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
