package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseHandoverRequestRepository extends JpaRepository<CaseHandoverRequest, Long> {

  List<CaseHandoverRequest> findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
      Long sessionId, String requesterConsultantId);

  List<CaseHandoverRequest> findBySessionIdAndStatusOrderByCreatedAtDesc(
      Long sessionId, CaseHandoverRequest.Status status);

  Optional<CaseHandoverRequest> findByIdAndSessionId(Long id, Long sessionId);

  /** The recipient's inbox: offers made to me, newest first. */
  List<CaseHandoverRequest> findByTargetConsultantIdAndStatusOrderByCreatedAtDesc(
      String targetConsultantId, CaseHandoverRequest.Status status);

  /**
   * The offering counsellor's outbox. A PUSH row records the offering counsellor as the PREVIOUS
   * consultant (the requester is the target, because acceptance makes them the owner), so "offers I
   * made" is a query on previous_consultant_id restricted to PUSH.
   */
  List<CaseHandoverRequest> findByDirectionAndPreviousConsultantIdAndStatusOrderByCreatedAtDesc(
      CaseHandoverRequest.Direction direction,
      String previousConsultantId,
      CaseHandoverRequest.Status status);

  /** Open offers on a case — the guard against a second offer while one is still pending. */
  List<CaseHandoverRequest> findBySessionIdAndDirectionAndStatus(
      Long sessionId, CaseHandoverRequest.Direction direction, CaseHandoverRequest.Status status);

  /** Sweep input for the expiry scheduler. */
  List<CaseHandoverRequest> findByStatusAndOfferExpiresAtBefore(
      CaseHandoverRequest.Status status, LocalDateTime deadline);

  List<CaseHandoverRequest> findBySessionId(Long sessionId);

  List<CaseHandoverRequest> findByRequesterConsultantId(String requesterConsultantId);

  List<CaseHandoverRequest> findByPreviousConsultantId(String previousConsultantId);

  /**
   * Bounded sweep input, oldest window first. Deliberately unlocked and deliberately paged: the
   * sweep reconciles Matrix between reading a row and writing its outcome, so a lock taken here
   * would be held across external round trips for the whole backlog. The row lock is taken instead
   * by {@link #findByIdForUpdate(Long)} in the short write transaction that follows.
   */
  @Query(
      "select request from CaseHandoverRequest request"
          + " where request.status = :status"
          + " and request.accessType = :accessType"
          + " and request.expiresAt <= :expiresAt"
          // Keyset cursor on (expiresAt, id). Page-zero paging cannot work here: a row whose
          // Matrix removal fails stays GRANTED, so it comes back at the head of the next page and
          // starves every later expired grant. The cursor steps past it instead, and the next
          // scheduled run retries it from the start.
          + " and (:afterExpiresAt is null"
          + "   or request.expiresAt > :afterExpiresAt"
          + "   or (request.expiresAt = :afterExpiresAt and request.id > :afterId))"
          + " order by request.expiresAt asc, request.id asc")
  // Index: changeset 0092 provides idx_case_handover_co_access_expiry on
  // (status, access_type, expires_at). InnoDB appends the primary key to every secondary index, so
  // its entries are physically (status, access_type, expires_at, id) - which is exactly this
  // predicate and this ordering. No further index is needed for the sweep.
  List<CaseHandoverRequest> findExpiredCoAccessBatch(
      @Param("status") CaseHandoverRequest.Status status,
      @Param("accessType") CaseHandoverRequest.AccessType accessType,
      @Param("expiresAt") LocalDateTime expiresAt,
      @Param("afterExpiresAt") LocalDateTime afterExpiresAt,
      @Param("afterId") Long afterId,
      Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select request from CaseHandoverRequest request where request.id = :id")
  Optional<CaseHandoverRequest> findByIdForUpdate(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<CaseHandoverRequest> findBySessionIdAndStatusAndAccessType(
      Long sessionId, CaseHandoverRequest.Status status, CaseHandoverRequest.AccessType accessType);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from CaseHandoverRequest request where request.session.id = :sessionId")
  int deleteAllBySessionId(@Param("sessionId") Long sessionId);
}
