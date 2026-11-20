package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseHandoverRequestRepository extends JpaRepository<CaseHandoverRequest, Long> {

  @Query(
      """
      select request from CaseHandoverRequest request
      where request.session.id = :sessionId
        and request.requesterConsultant.id = :requesterConsultantId
      order by request.createdAt desc, request.id desc
      """)
  List<CaseHandoverRequest> findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
      @Param("sessionId") Long sessionId,
      @Param("requesterConsultantId") String requesterConsultantId);

  interface CandidateStatusRequest {
    Long getSessionId();

    CaseHandoverRequest.Status getStatus();

    CaseHandoverRequest.AccessType getAccessType();

    String getReasonCode();

    LocalDateTime getExpiresAt();
  }

  // At most one request per authorized page ID. Project scalars to avoid hydrating request
  // relations.
  @Query(
      """
      select request.session.id as sessionId, request.status as status,
        request.accessType as accessType, request.reasonCode as reasonCode,
        request.expiresAt as expiresAt
      from CaseHandoverRequest request
      where request.session.id in :sessionIds
        and request.requesterConsultant.id = :requesterConsultantId
        and not exists (
          select newer.id from CaseHandoverRequest newer
          where newer.session.id = request.session.id
            and newer.requesterConsultant.id = :requesterConsultantId
            and (newer.createdAt > request.createdAt
              or (newer.createdAt = request.createdAt and newer.id > request.id))
        )
      """)
  List<CandidateStatusRequest> findLatestCandidateStatusRequests(
      @Param("sessionIds") Collection<Long> sessionIds,
      @Param("requesterConsultantId") String requesterConsultantId);

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

  /**
   * Lock-free lookup for status reads. Those run in read-only transactions, where MariaDB rejects
   * {@code SELECT ... FOR UPDATE} (1792), so they must not use the locked consent-decision lookup.
   */
  @Query(
      "select request from CaseHandoverRequest request where request.id = :id and request.session.id = :sessionId")
  Optional<CaseHandoverRequest> findWithoutLockByIdAndSessionId(
      @Param("id") Long id, @Param("sessionId") Long sessionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select request from CaseHandoverRequest request where request.id = :id")
  Optional<CaseHandoverRequest> findByIdForUpdate(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select request from CaseHandoverRequest request where request.id = :id and request.session.id = :sessionId")
  Optional<CaseHandoverRequest> findByIdAndSessionIdForUpdate(
      @Param("id") Long id, @Param("sessionId") Long sessionId);

  Optional<CaseHandoverRequest> findByInitiatorConsultantIdAndOperationId(
      String initiatorConsultantId, UUID operationId);

  List<CaseHandoverRequest> findBySessionId(Long sessionId);

  List<CaseHandoverRequest> findByRequesterConsultantId(String requesterConsultantId);

  /** Rows without an access type are legacy rows; the caller resolves them by reason code. */
  @Query(
      "select request from CaseHandoverRequest request join fetch request.session "
          + "where request.requesterConsultant.id = :consultantId "
          + "and request.status in :statuses "
          + "and (request.accessType is null or request.accessType = :accessType) "
          + "and (request.expiresAt is null or request.expiresAt > :now)")
  List<CaseHandoverRequest> findLiveCoAccessCandidates(
      @Param("consultantId") String consultantId,
      @Param("statuses") List<CaseHandoverRequest.Status> statuses,
      @Param("accessType") CaseHandoverRequest.AccessType accessType,
      @Param("now") LocalDateTime now);

  List<CaseHandoverRequest> findByPreviousConsultantId(String previousConsultantId);

  List<CaseHandoverRequest> findByInitiatorConsultantId(String initiatorConsultantId);

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
