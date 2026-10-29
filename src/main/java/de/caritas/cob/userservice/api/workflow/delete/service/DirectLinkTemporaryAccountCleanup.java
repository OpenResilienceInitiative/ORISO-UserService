package de.caritas.cob.userservice.api.workflow.delete.service;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountExpiryPolicy;
import de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountExpiryPolicy.ConversationFacts;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Deletes direct-link temporary accounts when their snapshotted lifetime is over (ADR-029 §4–§5,
 * #1476).
 *
 * <p>Unlike the self-help-group guests, the conversation stays: the counsellor keeps the session
 * and the room, and the former guest shows as "Gast (gelöscht)". The Keycloak user goes first, so
 * nobody can sign in while the rest runs, and Matrix access is suspended right after. Then the
 * guest's messages are redacted unless the retention mode keeps them (one Synapse task, polled on
 * later runs), and the Matrix user is deactivated (erased only when the messages go). The account
 * row stays as the tombstone the session points to. A failed Matrix step leaves the row undeleted
 * so the next run retries; every step is safe to repeat.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectLinkTemporaryAccountCleanup {

  private static final Set<SessionStatus> CLOSED =
      Set.of(SessionStatus.DONE, SessionStatus.IN_ARCHIVE);

  private final @NonNull UserRepository userRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull MatrixCallBindingRepository callBindings;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull IdentityAccountRemover identityAccountRemover;
  private final @NonNull TemporaryAccountExpiryPolicy expiryPolicy;

  /** Ids of live direct-link temporary accounts whose own lifetime is over at {@code now}. */
  @Transactional(readOnly = true)
  public List<String> dueAccountIds(LocalDateTime now) {
    return userRepository.findLiveDirectLinkTemporaryAccountIds().stream()
        .map(userRepository::findById)
        .flatMap(java.util.Optional::stream)
        .filter(user -> isDue(user, now))
        .map(User::getUserId)
        .toList();
  }

  private boolean isDue(User user, LocalDateTime now) {
    var snapshot = user.getTemporaryAccountSnapshot();
    // A started deletion is finished; a half-deleted account is never judged again.
    if (snapshot != null && snapshot.getDeletionStartedAt() != null) {
      return snapshot.getDeletedAt() == null;
    }
    var sessions = sessionRepository.findByUser(user);
    var facts = factsOf(sessions);
    if (!expiryPolicy.isDue(snapshot, user.getCreateDate(), facts, now)) {
      return false;
    }
    if (snapshot.isCall() || facts.closed()) {
      return true;
    }
    // Messages go through Matrix and never touch the session row: ask Matrix for the last one.
    // Unknown counts as recent, because a deletion cannot be undone.
    LocalDateTime lastMessage;
    try {
      lastMessage = lastMessageAt(sessions);
    } catch (MatrixSynapseService.RoomEventUnavailableException unavailable) {
      log.warn("Temporary account inactivity unknown, deletion deferred");
      return false;
    }
    if (lastMessage == null
        || (facts.lastActivityAt() != null && !lastMessage.isAfter(facts.lastActivityAt()))) {
      return true;
    }
    return expiryPolicy.isDue(
        snapshot,
        user.getCreateDate(),
        new ConversationFacts(
            facts.closed(), lastMessage, facts.lastCallEndedAt(), facts.callRunning()),
        now);
  }

  private LocalDateTime lastMessageAt(List<Session> sessions) {
    return sessions.stream()
        .map(Session::getMatrixRoomId)
        .filter(room -> room != null && !room.isBlank())
        .mapToLong(matrixSynapseService::latestMessageTimestamp)
        .filter(millis -> millis > 0)
        .max()
        .stream()
        .mapToObj(DirectLinkTemporaryAccountCleanup::utc)
        .findFirst()
        .orElse(null);
  }

  private static LocalDateTime utc(long epochMillis) {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
  }

  /** Deletes one account in a transaction of its own; failures come back as workflow errors. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<DeletionWorkflowError> delete(String userId, LocalDateTime now) {
    var user = userRepository.findById(userId).orElse(null);
    if (user == null
        || user.getTemporaryAccountSnapshot() == null
        || user.getTemporaryAccountSnapshot().getDeletedAt() != null) {
      return List.of();
    }
    var snapshot = user.getTemporaryAccountSnapshot();
    if (snapshot.getDeletionStartedAt() == null) {
      // The selection may be stale: a call or message since then keeps the account.
      if (!isDue(user, now)) {
        return List.of();
      }
      snapshot.setDeletionStartedAt(now);
    }
    try {
      deleteIdentity(userId);
    } catch (RuntimeException exception) {
      log.error(
          "Temporary account identity deletion failed ({})", exception.getClass().getSimpleName());
      return List.of(error(userId, DeletionTargetType.KEYCLOAK, "Unable to delete keycloak user"));
    }
    var keep = snapshot.keepsMessages();
    var matrixUserId = user.getMatrixUserId();
    if (isNotBlank(matrixUserId)) {
      // Sign-in is gone with Keycloak; Matrix sessions go now, whatever the redaction does.
      if (!matrixSynapseService.setAccountSuspended(matrixUserId, true)) {
        return List.of(error(userId, DeletionTargetType.MATRIX, "Unable to revoke Matrix access"));
      }
      if (!keep) {
        var redaction = redact(snapshot, matrixUserId, roomsOf(user));
        if (redaction != MatrixSynapseService.RedactionStatus.COMPLETE) {
          return redaction == MatrixSynapseService.RedactionStatus.RUNNING
              ? List.of()
              : List.of(
                  error(userId, DeletionTargetType.MATRIX, "Unable to delete guest messages"));
        }
      }
      if (!matrixSynapseService.deactivateUser(matrixUserId, !keep)) {
        return List.of(
            error(userId, DeletionTargetType.MATRIX, "Unable to delete Matrix user account"));
      }
    }
    snapshot.setRedactId(null);
    snapshot.setDeletedAt(now);
    userRepository.save(user);
    return List.of();
  }

  /** Submits the redaction once and polls that task on later runs; a failed task is resubmitted. */
  private MatrixSynapseService.RedactionStatus redact(
      de.caritas.cob.userservice.api.model.TemporaryAccountSnapshot snapshot,
      String matrixUserId,
      List<String> rooms) {
    if (rooms.isEmpty()) {
      return MatrixSynapseService.RedactionStatus.COMPLETE;
    }
    if (snapshot.getRedactId() == null) {
      var submitted = matrixSynapseService.submitRedaction(matrixUserId, rooms);
      if (submitted.isEmpty()) {
        return MatrixSynapseService.RedactionStatus.FAILED;
      }
      snapshot.setRedactId(submitted.get());
    }
    var status = matrixSynapseService.redactionStatus(snapshot.getRedactId());
    if (status == MatrixSynapseService.RedactionStatus.FAILED) {
      snapshot.setRedactId(null);
    }
    return status;
  }

  private List<String> roomsOf(User user) {
    return sessionRepository.findByUser(user).stream()
        .map(Session::getMatrixRoomId)
        .filter(Objects::nonNull)
        .filter(room -> !room.isBlank())
        .toList();
  }

  private void deleteIdentity(String userId) {
    try {
      identityAccountRemover.deleteUser(userId);
    } catch (jakarta.ws.rs.NotFoundException alreadyDeleted) {
      log.info("Temporary account identity already absent; deletion continues");
    } catch (HttpClientErrorException exception) {
      if (!HttpStatus.NOT_FOUND.equals(exception.getStatusCode())) {
        throw exception;
      }
    }
  }

  private ConversationFacts factsOf(List<Session> sessions) {
    var closed =
        !sessions.isEmpty()
            && sessions.stream().allMatch(session -> CLOSED.contains(session.getStatus()));
    var lastActivity =
        sessions.stream()
            .flatMap(
                session ->
                    java.util.stream.Stream.of(session.getUpdateDate(), session.getCreateDate()))
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
    var sessionIds = sessions.stream().map(Session::getId).filter(Objects::nonNull).toList();
    List<MatrixCallBinding> calls =
        sessionIds.isEmpty() ? List.of() : callBindings.findBySessionIdIn(sessionIds);
    var running =
        calls.stream().anyMatch(call -> call.getEndedAt() == null && call.getStartedAt() != null);
    var lastHangUp =
        calls.stream()
            .map(MatrixCallBinding::getEndedAt)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .map(DirectLinkTemporaryAccountCleanup::utc)
            .orElse(null);
    return new ConversationFacts(closed, lastActivity, lastHangUp, running);
  }

  private static DeletionWorkflowError error(
      String identifier, DeletionTargetType target, String reason) {
    return DeletionWorkflowError.builder()
        .deletionSourceType(ASKER)
        .deletionTargetType(target)
        .identifier(identifier)
        .reason(reason)
        .timestamp(nowInUtc())
        .build();
  }
}
