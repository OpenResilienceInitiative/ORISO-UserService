package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseHandoverRequestRepository extends JpaRepository<CaseHandoverRequest, Long> {

  List<CaseHandoverRequest> findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
      Long sessionId, String requesterConsultantId);

  @Query(
      "SELECT request FROM CaseHandoverRequest request"
          + " WHERE request.session.id = :sessionId"
          + " AND request.requesterConsultant.id = :requesterConsultantId"
          + " AND (:excludedRequestId IS NULL OR request.id <> :excludedRequestId)"
          + " AND request.status IN :statuses"
          + " AND (request.expiresAt IS NULL OR request.expiresAt > :now)"
          + " ORDER BY request.createdAt DESC")
  List<CaseHandoverRequest> findActiveGrantExcluding(
      @Param("sessionId") Long sessionId,
      @Param("requesterConsultantId") String requesterConsultantId,
      @Param("excludedRequestId") Long excludedRequestId,
      @Param("statuses") List<CaseHandoverRequest.Status> statuses,
      @Param("now") LocalDateTime now,
      Pageable pageable);

  List<CaseHandoverRequest> findBySessionIdAndStatusOrderByCreatedAtDesc(
      Long sessionId, CaseHandoverRequest.Status status);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select request from CaseHandoverRequest request where request.id = :id and request.session.id = :sessionId")
  Optional<CaseHandoverRequest> findByIdAndSessionId(
      @Param("id") Long id, @Param("sessionId") Long sessionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select request from CaseHandoverRequest request where request.id = :id")
  Optional<CaseHandoverRequest> findByIdForUpdate(@Param("id") Long id);

  List<CaseHandoverRequest> findBySessionId(Long sessionId);

  List<CaseHandoverRequest> findByRequesterConsultantId(String requesterConsultantId);

  List<CaseHandoverRequest> findByPreviousConsultantId(String previousConsultantId);

  @EntityGraph(
      attributePaths = {
        "session",
        "session.consultant",
        "requesterConsultant",
        "previousConsultant"
      })
  List<CaseHandoverRequest> findByStatusAndAccessTypeAndExpiresAtLessThanEqual(
      CaseHandoverRequest.Status status,
      CaseHandoverRequest.AccessType accessType,
      LocalDateTime expiresAt);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<CaseHandoverRequest> findBySessionIdAndStatusAndAccessType(
      Long sessionId, CaseHandoverRequest.Status status, CaseHandoverRequest.AccessType accessType);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from CaseHandoverRequest request where request.session.id = :sessionId")
  int deleteAllBySessionId(@Param("sessionId") Long sessionId);
}
