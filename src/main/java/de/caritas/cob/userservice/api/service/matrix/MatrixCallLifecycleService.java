package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.MatrixCallDevice;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A media room has no Session of its own; its persisted binding supplies the trusted context. */
@Service
@RequiredArgsConstructor
public class MatrixCallLifecycleService {
  private final MatrixCallBindingRepository bindings;
  private final MatrixSynapseService matrix;
  private final MatrixCallConversationResolver conversations;
  private final UserRepository users;
  private final ConsultantRepository consultants;
  private final EventNotificationService notifications;

  @org.springframework.beans.factory.annotation.Value("${matrix.calls.observation-retry-ms:30000}")
  private long observationRetryMillis = 30000;

  @Transactional
  public void loseObservation(String room) {
    bindings
        .lockByMediaRoomId(room)
        .filter(binding -> binding.getEndedAt() == null)
        .ifPresent(binding -> binding.loseMediaObservation());
  }

  @Transactional
  public void restoreObservation(String room) {
    var binding = bindings.lockByMediaRoomId(room).orElse(null);
    long now = System.currentTimeMillis();
    if (binding == null
        || binding.getEndedAt() != null
        || binding.getMediaObservedAt() != null
        || (binding.getNextObservationAttemptAt() != null
            && binding.getNextObservationAttemptAt() > now)) return;
    binding.deferObservationAttempt(now + Math.max(1, observationRetryMillis));
    var conversation = conversations.resolve(binding.getSourceRoomId()).orElse(null);
    if (conversation == null
        || !Objects.equals(conversation.getTenantId(), binding.getTenantId())
        || !Objects.equals(conversation.getSessionId(), binding.getSessionId())
        || !Objects.equals(conversation.getChatId(), binding.getChatId())) return;
    var members = matrix.getRoomMembers(binding.getSourceRoomId()).orElse(null);
    if (members == null) return;
    for (String member : members) {
      if (identity(member, binding.getTenantId()).isEmpty()) continue;
      var state = matrix.getCallRoomBinding(room, member).orElse(null);
      if (state == null
          || !binding.getCallId().equals(state.get("call_id"))
          || !binding.getSourceRoomId().equals(state.get("source_room_id"))) continue;
      if (matrix.ensureAdminInRoom(room, member)) return;
    }
    // Joining alone is not observation. Only the subsequent sync state may enable expiry.
  }

  @Transactional
  public boolean handleRoom(String room, Map<String, Object> roomData) {
    var binding = bindings.lockByMediaRoomId(room).orElse(null);
    if (binding == null) return false;
    if (binding.getEndedAt() != null) return true;
    var session = conversations.resolve(binding.getSourceRoomId()).orElse(null);
    if (session == null
        || !Objects.equals(session.getSessionId(), binding.getSessionId())
        || !Objects.equals(session.getChatId(), binding.getChatId())
        || !Objects.equals(session.getTenantId(), binding.getTenantId())) return true;
    var members = matrix.getRoomMembers(binding.getSourceRoomId()).orElse(null);
    if (members == null) return true;
    long now = System.currentTimeMillis();
    if (roomData.get("state") instanceof Map<?, ?>
        || roomData.get("timeline") instanceof Map<?, ?>) {
      binding.recordMediaObservation(now);
    }
    for (var event : memberships(roomData)) {
      if (event.eventTimestamp() > now
          || !members.contains(event.sender())
          || identity(event.sender(), binding.getTenantId()).isEmpty()) continue;
      var previous = binding.getDevices().get(event.stateKey());
      if (previous != null
          && (!previous.getSenderMatrixId().equals(event.sender())
              || previous.getEventId().equals(event.eventId())
              || previous.getEventTimestamp() > event.eventTimestamp()
              || (previous.getEventTimestamp() == event.eventTimestamp()
                  && !previous.getEventId().equals(event.replacesEventId())))) continue;
      boolean attended = previous != null && previous.isAttended();
      if (!event.departed() && event.expiresAt() > now) {
        attended = true;
        binding.recordAttendance(event.eventTimestamp());
      }
      binding
          .getDevices()
          .put(
              event.stateKey(),
              new MatrixCallDevice(
                  event.sender(),
                  event.eventTimestamp(),
                  event.expiresAt(),
                  attended,
                  event.eventId()));
    }
    // Apply the entire room batch before evaluating the last departure.
    if (binding.getMediaObservedAt() != null
        && (binding.getStartedAt() != null || binding.getInviteExpiresAt() <= now)
        && binding.getDevices().values().stream()
            .noneMatch(device -> device.getExpiresAt() > now)) {
      long lastPresenceEnd =
          binding.getStartedAt() == null
              ? binding.getInviteExpiresAt()
              : binding.getDevices().values().stream()
                  .mapToLong(MatrixCallDevice::getExpiresAt)
                  .max()
                  .orElse(now);
      binding.finish(lastPresenceEnd);
      binding.getDevices().values().stream()
          .filter(MatrixCallDevice::isAttended)
          .map(MatrixCallDevice::getSenderMatrixId)
          .distinct()
          .filter(members::contains)
          .map(sender -> identity(sender, binding.getTenantId()))
          .flatMap(Optional::stream)
          .distinct()
          .forEach(
              recipient ->
                  notifications.createCallEndedNotification(
                      session,
                      recipient.id(),
                      recipient.consultant(),
                      binding.getCallId(),
                      binding.getMediaRoomId(),
                      binding.getStartedAt(),
                      binding.getEndedAt()));
      var attendees =
          binding.getDevices().values().stream()
              .filter(MatrixCallDevice::isAttended)
              .map(MatrixCallDevice::getSenderMatrixId)
              .collect(java.util.stream.Collectors.toSet());
      binding.getInvitedMatrixIds().stream()
          .filter(members::contains)
          .filter(member -> !attendees.contains(member))
          .filter(member -> !member.equals(binding.getCallerMatrixId()))
          .map(member -> identity(member, binding.getTenantId()))
          .flatMap(Optional::stream)
          .distinct()
          .forEach(
              recipient ->
                  notifications.createCallMissedNotification(
                      session,
                      recipient.id(),
                      recipient.consultant(),
                      binding.getCallId(),
                      binding.getMediaRoomId(),
                      binding.isVideo()));
    }
    return true;
  }

  private List<MatrixRtcMembershipEvent> memberships(Map<String, Object> room) {
    var result = new ArrayList<MatrixRtcMembershipEvent>();
    for (String section : List.of("state", "timeline")) {
      if (!(room.get(section) instanceof Map<?, ?> data)
          || !(data.get("events") instanceof List<?> events)) continue;
      for (Object raw : events) {
        if (!(raw instanceof Map<?, ?> value)) continue;
        var event = new java.util.HashMap<String, Object>();
        value.forEach(
            (key, item) -> {
              if (key instanceof String name) event.put(name, item);
            });
        MatrixRtcMembershipEvent.parse(event).ifPresent(result::add);
      }
    }
    return result;
  }

  private Optional<Recipient> identity(String matrixId, Long tenant) {
    return users
        .findByMatrixUserIdAndDeleteDateIsNull(matrixId)
        .filter(user -> Objects.equals(user.getTenantId(), tenant) && user.getUserId() != null)
        .map(user -> new Recipient(user.getUserId(), false))
        .or(
            () ->
                consultants
                    .findByMatrixUserIdAndDeleteDateIsNull(matrixId)
                    .filter(
                        user -> Objects.equals(user.getTenantId(), tenant) && user.getId() != null)
                    .map(user -> new Recipient(user.getId(), true)));
  }

  private record Recipient(String id, boolean consultant) {}
}
