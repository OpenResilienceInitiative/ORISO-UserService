package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class MatrixCallStateService {
  private final MatrixCallBindingRepository bindings;
  private final MatrixCallConversationResolver conversations;
  private final UserRepository users;
  private final ConsultantRepository consultants;
  private final MatrixSynapseService matrix;
  private final MatrixCallGuestService callGuests;
  private final MatrixCallObservationService observations;
  private final PlatformTransactionManager transactionManager;

  /**
   * Not {@code @Transactional} (#1630): the Matrix membership lookup and the observation's liveness
   * check run with no database transaction open; only the binding read below uses a short read-only
   * one.
   */
  public Optional<CallState> read(AuthenticatedUser actor, String sourceRoomId, String callId) {
    if (actor.getUserId() == null
        || actor.getTenantId() == null
        || sourceRoomId == null
        || sourceRoomId.isBlank()
        || callId == null
        || callId.isBlank()) {
      return Optional.empty();
    }
    var conversation = conversations.resolve(sourceRoomId).orElse(null);
    if (conversation == null || !actor.getTenantId().equals(conversation.getTenantId())) {
      return Optional.empty();
    }
    Optional<String> matrixId =
        actor.isConsultant()
            ? consultants
                .findByIdAndDeleteDateIsNull(actor.getUserId())
                .filter(user -> actor.getTenantId().equals(user.getTenantId()))
                .map(user -> user.getMatrixUserId())
            : users
                .findByUserIdAndDeleteDateIsNull(actor.getUserId())
                .filter(user -> actor.getTenantId().equals(user.getTenantId()))
                .map(user -> user.getMatrixUserId());
    if (matrixId.isEmpty() || matrixId.get().isBlank()) return Optional.empty();
    var members = matrix.getCallRoomMembers(sourceRoomId).orElse(java.util.List.of());
    boolean conversationMember = members.contains(matrixId.get());
    var bound = bindings.findBySourceRoomIdAndCallId(sourceRoomId, callId).orElse(null);
    if (bound == null || supersededFor(actor, bound, matrixId.get())) {
      // #2166/#2237: encrypted group or session call without a readable invite. Observation only,
      // read by conversation members; never consulted for guests or any access decision.
      return conversationMember
          ? observations.readState(conversation, callId, members)
          : Optional.empty();
    }
    var readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);
    return readOnly.execute(
        status ->
            readBound(conversation, sourceRoomId, callId, matrixId.get(), conversationMember));
  }

  /**
   * FE#2209 round 5: the assigned counsellor does not see an ended binding without authority (for
   * example an asker's plaintext invite of the counsellor's call id that the server closed) as the
   * state of their call; they read the call as if no binding existed.
   */
  private boolean supersededFor(
      AuthenticatedUser actor,
      de.caritas.cob.userservice.api.model.MatrixCallBinding binding,
      String matrixId) {
    return actor.isConsultant()
        && binding.getSessionId() != null
        && binding.getEndedAt() != null
        && !callGuests.grantsGuestAuthority(binding)
        && callGuests.isAssignedCounsellor(binding, matrixId);
  }

  private Optional<CallState> readBound(
      MatrixCallConversation conversation,
      String sourceRoomId,
      String callId,
      String matrixId,
      boolean conversationMember) {
    return bindings
        .findBySourceRoomIdAndCallId(sourceRoomId, callId)
        .filter(
            binding ->
                Objects.equals(binding.getTenantId(), conversation.getTenantId())
                    && Objects.equals(binding.getSessionId(), conversation.getSessionId())
                    && Objects.equals(binding.getChatId(), conversation.getChatId()))
        // US#1477: a call guest reads this one call while the seat is held, nothing else.
        .filter(binding -> conversationMember || callGuests.isActiveGuest(binding, matrixId))
        .map(
            binding -> {
              Long started = binding.getStartedAt();
              Long ended = binding.getEndedAt();
              String state =
                  ended != null
                      ? (started == null ? "missed" : "ended")
                      : (started == null ? "invited" : "running");
              long duration =
                  started == null
                      ? 0
                      : Math.max(0, (ended == null ? System.currentTimeMillis() : ended) - started)
                          / 1000;
              return new CallState(
                  binding.getSourceRoomId(),
                  binding.getCallId(),
                  binding.getMediaRoomId(),
                  binding.isVideo() ? "video" : "audio",
                  state,
                  binding.getInvitedAt(),
                  started,
                  ended,
                  duration,
                  binding.getDevices().values().stream()
                      .filter(device -> device.isAttended())
                      .map(device -> device.getSenderMatrixId())
                      .distinct()
                      .sorted()
                      .toList());
            });
  }

  public record CallState(
      String sourceRoomId,
      String callId,
      String callRoomId,
      String callType,
      String state,
      long invitedAt,
      Long startedAt,
      Long endedAt,
      long durationSeconds,
      java.util.List<String> participantMatrixIds) {}
}
