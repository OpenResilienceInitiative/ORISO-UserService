package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Resolves invite recipients from live source-room membership, never from event-supplied IDs. */
@Service
@RequiredArgsConstructor
public class MatrixCallInviteNotificationService {
  private final MatrixSynapseService matrix;
  private final MatrixCallConversationResolver conversations;
  private final UserRepository users;
  private final ConsultantRepository consultants;
  private final EventNotificationService notifications;
  private final MatrixCallBindingService callBindings;
  private final MatrixCallLifecycleService lifecycle;

  public boolean handleMediaRoom(String room, Map<String, Object> roomData) {
    return lifecycle.handleRoom(room, roomData);
  }

  public void handleMediaRoomLeft(String room) {
    lifecycle.loseObservation(room);
  }

  public void reconcileMediaRooms() {
    // Each room is locked in its own transaction. Only call after a successful sync has
    // applied current membership renewals, never on a transport failure.
    for (String room : callBindings.unobservedMediaRooms(System.currentTimeMillis())) {
      lifecycle.restoreObservation(room);
    }
    for (String room : callBindings.expiredMediaRooms(System.currentTimeMillis())) {
      lifecycle.handleRoom(room, Map.of());
    }
  }

  public boolean handle(String sourceRoom, Map<String, Object> event) {
    if (sourceRoom == null
        || sourceRoom.isBlank()
        || sourceRoom.length() > 255
        || !(event.get("content") instanceof Map<?, ?> content)
        || !(event.get("sender") instanceof String sender)
        || sender.isBlank()
        || sender.length() > 255
        || !(content.get("call_id") instanceof String callId)
        || callId.isBlank()
        || callId.length() > 191
        || !(content.get("call_room_id") instanceof String mediaRoom)
        || mediaRoom.isBlank()
        || mediaRoom.length() > 255) {
      return false;
    }
    Long timestamp = integer(event.get("origin_server_ts"));
    Long lifetime = integer(content.get("lifetime"));
    long now = System.currentTimeMillis();
    if (timestamp == null
        || lifetime == null
        || timestamp < 0
        || lifetime <= 0
        || lifetime > Long.MAX_VALUE - timestamp
        || timestamp > now) return false;
    boolean expired = now - timestamp >= lifetime;
    var session = conversations.resolve(sourceRoom).orElse(null);
    if (session == null) return false;
    var members = matrix.getRoomMembers(sourceRoom).orElse(null);
    if (members == null || !members.contains(sender)) return false;
    var actor = identity(sender, session.getTenantId()).orElse(null);
    if (actor == null) return false;
    var binding = matrix.getCallRoomBinding(mediaRoom, sender).orElse(null);
    if (binding == null
        || !callId.equals(binding.get("call_id"))
        || !sourceRoom.equals(binding.get("source_room_id"))) return false;
    if (!callBindings.register(
        de.caritas.cob.userservice.api.model.MatrixCallBinding.builder()
            .sourceRoomId(sourceRoom)
            .callId(callId)
            .mediaRoomId(mediaRoom)
            .callerMatrixId(sender)
            .sessionId(session.getSessionId())
            .chatId(session.getChatId())
            .tenantId(session.getTenantId())
            .invitedAt(timestamp)
            .inviteExpiresAt(timestamp + lifetime)
            .video(Boolean.TRUE.equals(content.get("is_video")))
            .invitedMatrixIds(
                expired
                    ? java.util.Set.of()
                    : members.stream()
                        .filter(member -> !member.equals(sender))
                        .filter(member -> identity(member, session.getTenantId()).isPresent())
                        .collect(java.util.stream.Collectors.toSet()))
            .build())) return false;
    // Only a validated, persistently bound room may grant the listener membership.
    // Observer availability must not suppress a valid invitation. Failed observation is retried
    // from the persisted binding; expiry is gated on actually receiving media-room state.
    matrix.ensureAdminInRoom(mediaRoom, sender);
    // A late sync can discover a call that is still running. Recover its identity and media
    // observation without ringing again or inventing a historical invitee list from today's
    // members.
    if (expired) return true;
    callBindings.invitedMembers(sourceRoom, callId).stream()
        .filter(members::contains)
        .distinct()
        .filter(member -> !member.equals(sender))
        .map(member -> identity(member, session.getTenantId()))
        .flatMap(Optional::stream)
        .distinct()
        .filter(recipient -> !recipient.id().equals(actor.id()))
        .forEach(
            recipient ->
                notifications.createCallInvitationNotification(
                    session,
                    recipient.id(),
                    recipient.consultant(),
                    actor.id(),
                    callId,
                    mediaRoom,
                    Boolean.TRUE.equals(content.get("is_video"))));
    return true;
  }

  private Optional<Identity> identity(String matrixId, Long tenantId) {
    var user =
        users
            .findByMatrixUserIdAndDeleteDateIsNull(matrixId)
            .filter(value -> Objects.equals(value.getTenantId(), tenantId))
            .filter(value -> value.getUserId() != null)
            .map(value -> new Identity(value.getUserId(), false));
    return user.or(
        () ->
            consultants
                .findByMatrixUserIdAndDeleteDateIsNull(matrixId)
                .filter(value -> Objects.equals(value.getTenantId(), tenantId))
                .filter(value -> value.getId() != null)
                .map(value -> new Identity(value.getId(), true)));
  }

  private record Identity(String id, boolean consultant) {}

  private static Long integer(Object value) {
    if (value instanceof Integer number) return number.longValue();
    if (value instanceof Long number) return number;
    return null;
  }
}
