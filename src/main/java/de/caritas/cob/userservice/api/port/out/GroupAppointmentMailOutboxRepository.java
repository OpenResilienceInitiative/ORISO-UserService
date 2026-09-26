package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface GroupAppointmentMailOutboxRepository
    extends CrudRepository<GroupAppointmentMailOutbox, Long> {
  boolean
      existsBySeriesIdAndOccurrenceIndexAndOccurrenceRevisionAndEventTypeAndRecipientRoleAndRecipientId(
          Long seriesId,
          int occurrenceIndex,
          long occurrenceRevision,
          GroupAppointmentMailOutbox.EventType eventType,
          GroupAppointmentMailOutbox.RecipientRole recipientRole,
          String recipientId);

  boolean
      existsBySeriesIdAndOccurrenceIndexAndOccurrenceRevisionAndRecipientRoleAndRecipientIdAndEventTypeIn(
          Long seriesId,
          int occurrenceIndex,
          long occurrenceRevision,
          GroupAppointmentMailOutbox.RecipientRole recipientRole,
          String recipientId,
          List<GroupAppointmentMailOutbox.EventType> eventTypes);

  List<GroupAppointmentMailOutbox> findTop100ByStatusAndDueAtUtcLessThanEqualOrderByDueAtUtcAsc(
      GroupAppointmentMailOutbox.Status status, LocalDateTime dueAtUtc);

  @Modifying
  @Query(
      "update GroupAppointmentMailOutbox mail set mail.status = :claimed, "
          + "mail.claimedAt = :claimedAt where mail.id = :id and mail.status = :pending")
  int claim(
      @Param("id") Long id,
      @Param("pending") GroupAppointmentMailOutbox.Status pending,
      @Param("claimed") GroupAppointmentMailOutbox.Status claimed,
      @Param("claimedAt") LocalDateTime claimedAt);
}
