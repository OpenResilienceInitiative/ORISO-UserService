package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

  @Lock(LockModeType.PESSIMISTIC_WRITE)
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
