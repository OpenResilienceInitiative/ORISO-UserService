package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface GroupChatJoinRequestRepository extends CrudRepository<GroupChatJoinRequest, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT request FROM GroupChatJoinRequest request WHERE request.id = :id")
  Optional<GroupChatJoinRequest> findByIdForUpdate(@Param("id") Long id);

  Optional<GroupChatJoinRequest> findFirstBySeriesIdAndConsultantIdAndStatusOrderByIdDesc(
      Long seriesId, String consultantId, Status status);

  Optional<GroupChatJoinRequest> findFirstBySeriesIdAndConsultantIdOrderByIdDesc(
      Long seriesId, String consultantId);

  List<GroupChatJoinRequest> findBySeriesIdInAndStatusOrderByRequestedAtAscIdAsc(
      Collection<Long> seriesIds, Status status);

  @Query(
      "SELECT request FROM GroupChatJoinRequest request"
          + " WHERE request.status = :status AND request.admissionRequestedAt IS NOT NULL"
          + " AND (request.admissionLastAttemptAt IS NULL"
          + " OR request.admissionLastAttemptAt < :retryBefore)"
          + " ORDER BY CASE WHEN request.admissionLastAttemptAt IS NULL THEN 0 ELSE 1 END ASC,"
          + " request.admissionLastAttemptAt ASC, request.admissionRequestedAt ASC, request.id ASC")
  List<GroupChatJoinRequest> findAdmissionsReady(
      @Param("status") Status status,
      @Param("retryBefore") java.time.LocalDateTime retryBefore,
      Pageable pageable);
}
