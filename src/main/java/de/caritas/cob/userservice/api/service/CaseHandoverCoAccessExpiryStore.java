package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.Status;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundaries for the co-access expiry sweep.
 *
 * <p>This exists so the sweep can talk to Matrix <em>outside</em> a database transaction. The sweep
 * previously ran as one {@code @Transactional} method over an unbounded result set, performing
 * {@code getRoomMembers}, {@code loginAsUserAccessToken} and {@code removeUserFromRoom} per row: a
 * slow or hanging Synapse held a pooled connection and the row locks for the whole backlog.
 *
 * <p>Splitting it into a separate bean rather than adding methods to {@link CaseHandoverService} is
 * deliberate — a self-invocation would bypass the Spring proxy and silently run with no transaction
 * at all, which is the same trap in a different shape.
 */
@Service
@RequiredArgsConstructor
public class CaseHandoverCoAccessExpiryStore {

  private final @NonNull CaseHandoverRequestRepository caseHandoverRequestRepository;

  /**
   * Everything the Matrix reconciliation needs, as plain values.
   *
   * <p>Values, not entities: the reconciliation runs after this transaction has closed, and reading
   * an association off a detached entity is the kind of failure that passes every mock-based test
   * and then throws in production.
   */
  public record ExpiringCoAccess(
      Long requestId,
      Long sessionId,
      String matrixRoomId,
      String requesterConsultantId,
      String requesterMatrixUserId,
      String ownerConsultantId,
      String ownerMatrixUserId,
      String previousConsultantMatrixUserId) {}

  /** A bounded, oldest-first page of grants whose window has closed. */
  @Transactional(readOnly = true)
  public List<ExpiringCoAccess> findExpiredBatch(LocalDateTime now, int limit) {
    return caseHandoverRequestRepository
        .findExpiredCoAccessBatch(
            Status.GRANTED, AccessType.CO_ACCESS, now, PageRequest.of(0, Math.max(1, limit)))
        .stream()
        .map(CaseHandoverCoAccessExpiryStore::toExpiring)
        .toList();
  }

  /**
   * Persists one expiry in its own short transaction, under a row lock taken here and released at
   * commit — never held across a Matrix call.
   *
   * @return whether this call is the one that expired the row
   */
  @Transactional
  public boolean markExpired(Long requestId, LocalDateTime now, String auditOutcome) {
    return caseHandoverRequestRepository
        .findByIdForUpdate(requestId)
        // Re-checked under the lock: the grant may have been resolved between the batch read and
        // the Matrix round trip, and a second EXPIRED write would overwrite that outcome.
        .filter(request -> request.getStatus() == Status.GRANTED)
        .map(
            request -> {
              request.setStatus(Status.EXPIRED);
              request.setAuditOutcome(auditOutcome);
              request.setResolvedAt(now);
              caseHandoverRequestRepository.save(request);
              return true;
            })
        .orElse(false);
  }

  private static ExpiringCoAccess toExpiring(CaseHandoverRequest request) {
    Session session = request.getSession();
    Consultant requester = request.getRequesterConsultant();
    Consultant owner = session == null ? null : session.getConsultant();
    Consultant previous = request.getPreviousConsultant();
    return new ExpiringCoAccess(
        request.getId(),
        session == null ? null : session.getId(),
        session == null ? null : session.getMatrixRoomId(),
        requester == null ? null : requester.getId(),
        requester == null ? null : requester.getMatrixUserId(),
        owner == null ? null : owner.getId(),
        owner == null ? null : owner.getMatrixUserId(),
        previous == null ? null : previous.getMatrixUserId());
  }
}
