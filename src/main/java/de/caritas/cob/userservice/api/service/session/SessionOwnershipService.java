package de.caritas.cob.userservice.api.service.session;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The single transactional boundary for changing the consultant who owns a session. */
@Service
@RequiredArgsConstructor
public class SessionOwnershipService {

  public record OwnershipChange(Long sessionId, @Nullable String ownerId, long revision) {}

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull EntityManager entityManager;

  @Transactional
  public OwnershipChange updateOwnerAndStatus(
      Session expectedSession, @Nullable Consultant newOwner, SessionStatus newStatus) {
    return updateOwner(expectedSession, newOwner, newStatus, expectedSession.getUpdateDate());
  }

  @Transactional
  public OwnershipChange updateOwner(
      Session expectedSession,
      @Nullable Consultant newOwner,
      SessionStatus newStatus,
      @Nullable LocalDateTime updateDate) {
    String expectedOwnerId = ownerId(expectedSession.getConsultant());
    long expectedRevision = expectedSession.getOwnershipRevision();
    long expectedRowVersion = expectedSession.getRowVersion();
    if (entityManager.contains(expectedSession)) {
      entityManager.detach(expectedSession);
    }
    Session current = lock(expectedSession.getId());
    requireCurrentOwnership(current, expectedOwnerId, expectedRevision, expectedRowVersion);
    apply(current, newOwner, newStatus, updateDate);
    synchronizeExpectedSession(expectedSession, current);
    return token(current);
  }

  @Transactional
  public boolean compensateOwnerChange(
      Long sessionId,
      OwnershipChange assignment,
      @Nullable Consultant restoredOwner,
      SessionStatus restoredStatus,
      @Nullable LocalDateTime updateDate) {
    Session current = lock(sessionId);
    if (!Objects.equals(ownerId(current.getConsultant()), assignment.ownerId())
        || current.getOwnershipRevision() != assignment.revision()) {
      return false;
    }
    apply(current, restoredOwner, restoredStatus, updateDate);
    return true;
  }

  @Transactional
  public void clearOwnerFromSessions(Consultant expectedOwner, List<SessionStatus> statuses) {
    sessionRepository.findIdsByConsultantAndStatusInOrderById(expectedOwner, statuses).stream()
        .sorted(Comparator.naturalOrder())
        .forEach(
            sessionId -> {
              Session current = lock(sessionId);
              if (Objects.equals(ownerId(current.getConsultant()), expectedOwner.getId())
                  && statuses.contains(current.getStatus())) {
                apply(current, null, current.getStatus(), current.getUpdateDate());
              }
            });
  }

  private Session lock(Long sessionId) {
    Session current =
        sessionRepository
            .findByIdForUpdate(sessionId)
            .orElseThrow(() -> new NotFoundException("Session not found: " + sessionId));
    entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
    return current;
  }

  private void requireCurrentOwnership(
      Session current, String expectedOwnerId, long expectedRevision, long expectedRowVersion) {
    if (!Objects.equals(ownerId(current.getConsultant()), expectedOwnerId)
        || current.getOwnershipRevision() != expectedRevision
        || current.getRowVersion() != expectedRowVersion) {
      throw new ConflictException(
          "Session ownership changed while the operation was in progress: " + current.getId());
    }
  }

  private void apply(
      Session current,
      @Nullable Consultant newOwner,
      SessionStatus newStatus,
      @Nullable LocalDateTime updateDate) {
    if (!Objects.equals(ownerId(current.getConsultant()), ownerId(newOwner))) {
      current.setConsultant(newOwner);
      current.setOwnershipRevision(Math.incrementExact(current.getOwnershipRevision()));
    }
    current.setStatus(newStatus);
    current.setUpdateDate(updateDate);
    sessionRepository.save(current);
    entityManager.flush();
  }

  private static void synchronizeExpectedSession(Session expected, Session current) {
    expected.setConsultant(current.getConsultant());
    expected.setOwnershipRevision(current.getOwnershipRevision());
    expected.setStatus(current.getStatus());
    expected.setUpdateDate(current.getUpdateDate());
    expected.setRowVersion(current.getRowVersion());
  }

  private static OwnershipChange token(Session session) {
    return new OwnershipChange(
        session.getId(), ownerId(session.getConsultant()), session.getOwnershipRevision());
  }

  private static String ownerId(@Nullable Consultant consultant) {
    return consultant == null ? null : consultant.getId();
  }
}
