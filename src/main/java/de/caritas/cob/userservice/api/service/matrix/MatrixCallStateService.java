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
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatrixCallStateService {
  private final MatrixCallBindingRepository bindings;
  private final MatrixCallConversationResolver conversations;
  private final UserRepository users;
  private final ConsultantRepository consultants;
  private final MatrixSynapseService matrix;

  @Transactional(readOnly = true)
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
    if (matrixId.isEmpty()
        || matrixId.get().isBlank()
        || !matrix
            .getRoomMembers(sourceRoomId)
            .map(members -> members.contains(matrixId.get()))
            .orElse(false)) {
      return Optional.empty();
    }
    return bindings
        .findBySourceRoomIdAndCallId(sourceRoomId, callId)
        .filter(
            binding ->
                Objects.equals(binding.getTenantId(), conversation.getTenantId())
                    && Objects.equals(binding.getSessionId(), conversation.getSessionId())
                    && Objects.equals(binding.getChatId(), conversation.getChatId()))
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
