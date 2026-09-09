package de.caritas.cob.userservice.api.service.matrixrtc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.CallAttendanceInterval;
import de.caritas.cob.userservice.api.model.CallLifecycleProjection;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.CallAttendanceIntervalRepository;
import de.caritas.cob.userservice.api.port.out.CallLifecycleProjectionRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.notification.CallLifecycleEmailNotificationService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects Matrix call metadata into a durable lifecycle and attendance history, then emits the
 * privacy-safe {@code call.*} activity events configured by the frontend.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallLifecycleProjectionService {

  public static final Set<String> SUPPORTED_EVENT_TYPES =
      Set.of(
          "m.call.invite",
          "m.call.answer",
          "m.call.hangup",
          "m.call.member",
          "org.matrix.msc3401.call.member",
          "org.oriso.call.invited",
          "org.oriso.call.started",
          "org.oriso.call.ended",
          "org.oriso.call.missed");

  private final @NonNull CallLifecycleProjectionRepository lifecycleRepository;
  private final @NonNull CallAttendanceIntervalRepository attendanceRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ChatRepository chatRepository;
  private final @NonNull GroupChatParticipantRepository groupChatParticipantRepository;
  private final @NonNull UserChatRepository userChatRepository;
  private final @NonNull UserRepository userRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull CallLifecycleEmailNotificationService emailNotificationService;
  private final @NonNull ObjectMapper objectMapper;

  public boolean supports(String eventType) {
    return SUPPORTED_EVENT_TYPES.contains(eventType);
  }

  @Transactional
  public boolean project(String matrixRoomId, Map<String, Object> event) {
    String eventType = stringValue(event.get("type"));
    if (matrixRoomId == null || matrixRoomId.isBlank() || !supports(eventType)) {
      return false;
    }
    if (isMembershipEvent(eventType)) {
      return projectMembership(matrixRoomId, event);
    }

    Map<String, Object> content = contentOf(event);
    String callId = firstPresent(content, "call_id", "m.call_id", "callId");
    if (callId == null) {
      return false;
    }
    Optional<CallLifecycleProjection> existing = findLifecycle(matrixRoomId, callId);
    var context =
        resolveConversation(matrixRoomId, content)
            .or(
                () ->
                    existing.flatMap(
                        lifecycle -> resolveConversation(lifecycle.getMatrixRoomId(), Map.of())));
    if (context.isEmpty()) {
      return false;
    }
    LocalDateTime eventTime = eventTime(event);
    String actor = resolveDomainUserId(stringValue(event.get("sender")));
    CallLifecycleProjection lifecycle =
        existing.orElseGet(
            () -> newLifecycle(matrixRoomId, callId, content, context.get(), actor, eventTime));

    switch (normaliseLifecycleEvent(eventType)) {
      case INVITED -> {
        lifecycle.setInvitedAt(earlier(lifecycle.getInvitedAt(), eventTime));
        lifecycle.setStatus(CallLifecycleProjection.Status.INVITED.name());
        saveAndNotify(lifecycle, "call.invited", context.get(), eventTime, actor, false);
      }
      case STARTED -> {
        lifecycle.setStartedAt(earlier(lifecycle.getStartedAt(), eventTime));
        lifecycle.setStatus(CallLifecycleProjection.Status.STARTED.name());
        saveAndNotify(lifecycle, "call.started", context.get(), eventTime, actor, false);
      }
      case ENDED -> endCall(lifecycle, context.get(), eventTime, actor, false);
      case MISSED -> {
        lifecycle.setEndedAt(lifecycle.getEndedAt() == null ? eventTime : lifecycle.getEndedAt());
        lifecycle.setStatus(CallLifecycleProjection.Status.ENDED.name());
        saveAndNotify(lifecycle, "call.missed", context.get(), eventTime, actor, true);
      }
    }
    return true;
  }

  private boolean projectMembership(String matrixRoomId, Map<String, Object> event) {
    String matrixUserId = firstPresent(event, "state_key", "sender");
    if (matrixUserId == null) {
      return false;
    }
    Map<String, Object> content = contentOf(event);
    List<Map<String, Object>> memberships = membershipsOf(content);
    Set<String> activeKeys = new LinkedHashSet<>();
    LocalDateTime eventTime = eventTime(event);
    String domainUserId = resolveDomainUserId(matrixUserId);
    boolean handled = false;

    for (Map<String, Object> membership : memberships) {
      String callId = firstPresent(membership, "call_id", "m.call_id", "callId");
      if (callId == null || isExpired(membership, eventTime)) {
        continue;
      }
      Optional<CallLifecycleProjection> existing = findLifecycle(matrixRoomId, callId);
      var context =
          resolveConversation(matrixRoomId, membership)
              .or(
                  () ->
                      existing.flatMap(
                          lifecycle -> resolveConversation(lifecycle.getMatrixRoomId(), Map.of())));
      if (context.isEmpty()) {
        continue;
      }
      String deviceId =
          Optional.ofNullable(firstPresent(membership, "device_id", "m.device_id", "deviceId"))
              .orElse("unknown");
      activeKeys.add(callId + "\u0000" + deviceId);
      CallLifecycleProjection lifecycle =
          existing.orElseGet(
              () ->
                  newLifecycle(
                      matrixRoomId, callId, membership, context.get(), domainUserId, eventTime));
      if (lifecycle.getStartedAt() == null) {
        lifecycle.setStartedAt(eventTime);
        lifecycle.setStatus(CallLifecycleProjection.Status.STARTED.name());
        saveAndNotify(lifecycle, "call.started", context.get(), eventTime, domainUserId, false);
      } else {
        lifecycle.setUpdateDate(eventTime);
        lifecycleRepository.save(lifecycle);
      }
      if (attendanceRepository
          .findByCallLifecycleAndMatrixUserIdAndDeviceIdAndLeftAtIsNull(
              lifecycle, matrixUserId, deviceId)
          .isEmpty()) {
        attendanceRepository.save(
            CallAttendanceInterval.builder()
                .callLifecycle(lifecycle)
                .matrixUserId(matrixUserId)
                .domainUserId(domainUserId)
                .deviceId(deviceId)
                .joinedAt(eventTime)
                .build());
      }
      handled = true;
    }

    for (CallLifecycleProjection lifecycle : lifecyclesForRoom(matrixRoomId)) {
      List<CallAttendanceInterval> openForUser =
          attendanceRepository.findByCallLifecycleAndLeftAtIsNull(lifecycle).stream()
              .filter(interval -> matrixUserId.equals(interval.getMatrixUserId()))
              .toList();
      boolean closedAny = false;
      for (CallAttendanceInterval interval : openForUser) {
        if (!activeKeys.contains(lifecycle.getCallId() + "\u0000" + interval.getDeviceId())) {
          interval.setLeftAt(eventTime);
          attendanceRepository.save(interval);
          handled = true;
          closedAny = true;
        }
      }
      if (closedAny
          && lifecycle.getEndedAt() == null
          && lifecycle.getStartedAt() != null
          && attendanceRepository.findByCallLifecycleAndLeftAtIsNull(lifecycle).isEmpty()) {
        resolveConversation(lifecycle.getMatrixRoomId(), Map.of())
            .ifPresent(context -> endCall(lifecycle, context, eventTime, domainUserId, true));
      }
    }
    return handled;
  }

  private void endCall(
      CallLifecycleProjection lifecycle,
      ConversationContext context,
      LocalDateTime eventTime,
      String actor,
      boolean inferredFromMembership) {
    if (lifecycle.getEndedAt() == null) {
      lifecycle.setEndedAt(eventTime);
    }
    lifecycle.setStatus(CallLifecycleProjection.Status.ENDED.name());
    attendanceRepository
        .findByCallLifecycleAndLeftAtIsNull(lifecycle)
        .forEach(
            interval -> {
              interval.setLeftAt(eventTime);
              attendanceRepository.save(interval);
            });
    saveAndNotify(lifecycle, "call.ended", context, eventTime, actor, false);

    Set<String> attendees = participantIds(lifecycle);
    context.recipientIds().stream()
        .filter(recipient -> !attendees.contains(recipient))
        .forEach(
            recipient ->
                createNotification(
                    lifecycle,
                    "call.missed",
                    context,
                    eventTime,
                    actor,
                    List.of(recipient),
                    inferredFromMembership));
  }

  private void saveAndNotify(
      CallLifecycleProjection lifecycle,
      String eventType,
      ConversationContext context,
      LocalDateTime eventTime,
      String actor,
      boolean onlyActor) {
    lifecycle.setUpdateDate(eventTime);
    lifecycleRepository.save(lifecycle);
    Collection<String> recipients =
        onlyActor ? (actor == null ? List.of() : List.of(actor)) : context.recipientIds();
    if ("call.invited".equals(eventType) && actor != null) {
      recipients = recipients.stream().filter(recipient -> !actor.equals(recipient)).toList();
    }
    createNotification(lifecycle, eventType, context, eventTime, actor, recipients, false);
  }

  private void createNotification(
      CallLifecycleProjection lifecycle,
      String eventType,
      ConversationContext context,
      LocalDateTime eventTime,
      String actor,
      Collection<String> recipients,
      boolean inferred) {
    String params = serializeParams(lifecycle, eventTime, actor, inferred);
    String deduplicationKey =
        "call:"
            + eventType
            + ":"
            + UUID.nameUUIDFromBytes(
                (lifecycle.getMatrixRoomId() + "\u0000" + lifecycle.getCallId())
                    .getBytes(StandardCharsets.UTF_8));
    recipients.stream()
        .filter(recipient -> recipient != null && !recipient.isBlank())
        .distinct()
        .forEach(
            recipient -> {
              boolean created =
                  eventNotificationService.createEventOnce(
                      deduplicationKey,
                      recipient,
                      eventType,
                      EventNotificationService.CATEGORY_SYSTEM,
                      titleFor(eventType, lifecycle.getCallType()),
                      textFor(eventType, lifecycle.getCallType()),
                      params,
                      null,
                      context.sessionId(),
                      context.tenantId());
              if (created && "call.invited".equals(eventType)) {
                emailNotificationService.sendInvitation(recipient, context.tenantId());
              } else if (created && "call.missed".equals(eventType)) {
                emailNotificationService.sendMissed(recipient, context.tenantId());
              }
            });
  }

  private String serializeParams(
      CallLifecycleProjection lifecycle, LocalDateTime eventTime, String actor, boolean inferred) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("callId", lifecycle.getCallId());
    params.put("roomRef", lifecycle.getMatrixRoomId());
    params.put("callRoomId", lifecycle.getCallRoomId());
    params.put("callType", lifecycle.getCallType());
    params.put("isVideo", "video".equals(lifecycle.getCallType()));
    putIfPresent(
        params,
        "invitedAt",
        lifecycle.getInvitedAt() == null ? null : lifecycle.getInvitedAt().toString());
    putIfPresent(
        params,
        "startedAt",
        lifecycle.getStartedAt() == null ? null : lifecycle.getStartedAt().toString());
    putIfPresent(
        params,
        "endedAt",
        lifecycle.getEndedAt() == null ? null : lifecycle.getEndedAt().toString());
    if (lifecycle.getStartedAt() != null && lifecycle.getEndedAt() != null) {
      params.put(
          "durationSeconds",
          Math.max(
              0, ChronoUnit.SECONDS.between(lifecycle.getStartedAt(), lifecycle.getEndedAt())));
    }
    if (actor != null) {
      params.put("actorUserId", actor);
    }
    List<String> participants = new ArrayList<>(participantIds(lifecycle));
    params.put("participants", participants);
    params.put("participantCount", participants.size());
    if (inferred) {
      params.put("inferredFromMembership", true);
    }
    params.put("eventAt", eventTime.toString());
    try {
      return objectMapper.writeValueAsString(params);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not serialize call lifecycle parameters", exception);
    }
  }

  private Set<String> participantIds(CallLifecycleProjection lifecycle) {
    var participants = new LinkedHashSet<String>();
    attendanceRepository.findByCallLifecycle(lifecycle).stream()
        .map(CallAttendanceInterval::getDomainUserId)
        .filter(value -> value != null && !value.isBlank())
        .forEach(participants::add);
    return participants;
  }

  private CallLifecycleProjection newLifecycle(
      String matrixRoomId,
      String callId,
      Map<String, Object> content,
      ConversationContext context,
      String actor,
      LocalDateTime eventTime) {
    return CallLifecycleProjection.builder()
        .matrixRoomId(matrixRoomId)
        .callRoomId(
            Optional.ofNullable(firstPresent(content, "call_room_id", "callRoomId"))
                .orElse(matrixRoomId))
        .callId(callId)
        .callType(callType(content))
        .status(CallLifecycleProjection.Status.INVITED.name())
        .actorUserId(actor)
        .sourceSessionId(context.sessionId())
        .tenantId(context.tenantId())
        .createDate(eventTime)
        .updateDate(eventTime)
        .build();
  }

  private Optional<CallLifecycleProjection> findLifecycle(String roomId, String callId) {
    return lifecycleRepository
        .findByMatrixRoomIdAndCallId(roomId, callId)
        .or(() -> lifecycleRepository.findByCallRoomIdAndCallId(roomId, callId));
  }

  private List<CallLifecycleProjection> lifecyclesForRoom(String roomId) {
    Map<Long, CallLifecycleProjection> unique = new LinkedHashMap<>();
    lifecycleRepository
        .findByMatrixRoomId(roomId)
        .forEach(value -> unique.put(value.getId(), value));
    lifecycleRepository.findByCallRoomId(roomId).forEach(value -> unique.put(value.getId(), value));
    return List.copyOf(unique.values());
  }

  private Optional<ConversationContext> resolveConversation(
      String matrixRoomId, Map<String, Object> content) {
    String callRoomId = firstPresent(content, "call_room_id", "callRoomId");
    for (String candidate : List.of(matrixRoomId, callRoomId == null ? matrixRoomId : callRoomId)) {
      Optional<Session> session = sessionRepository.findByMatrixRoomId(candidate);
      if (session.isPresent()) {
        return Optional.of(contextFor(session.get()));
      }
      Optional<Chat> chat = chatRepository.findByMatrixRoomId(candidate);
      if (chat.isPresent()) {
        return Optional.of(contextFor(chat.get()));
      }
    }
    return Optional.empty();
  }

  private ConversationContext contextFor(Session session) {
    var recipients = new LinkedHashSet<String>();
    if (session.getUser() != null) {
      addPresent(recipients, session.getUser().getUserId());
    }
    if (session.getConsultant() != null) {
      addPresent(recipients, session.getConsultant().getId());
    }
    if (session.getId() != null) {
      groupChatParticipantRepository.findByChatId(session.getId()).stream()
          .map(participant -> participant.getConsultantId())
          .forEach(value -> addPresent(recipients, value));
    }
    return new ConversationContext(session.getId(), session.getTenantId(), List.copyOf(recipients));
  }

  private ConversationContext contextFor(Chat chat) {
    var recipients = new LinkedHashSet<String>();
    if (chat.getChatOwner() != null) {
      addPresent(recipients, chat.getChatOwner().getId());
    }
    groupChatParticipantRepository.findBySeriesId(chat.getId()).stream()
        .map(participant -> participant.getConsultantId())
        .forEach(value -> addPresent(recipients, value));
    userChatRepository.findByChat(chat).stream()
        .filter(relation -> relation.getUser() != null)
        .map(relation -> relation.getUser().getUserId())
        .forEach(value -> addPresent(recipients, value));
    Long tenantId = chat.getChatOwner() == null ? null : chat.getChatOwner().getTenantId();
    return new ConversationContext(chat.getId(), tenantId, List.copyOf(recipients));
  }

  private String resolveDomainUserId(String matrixUserId) {
    if (matrixUserId == null || matrixUserId.isBlank()) {
      return null;
    }
    return userRepository
        .findByMatrixUserIdAndDeleteDateIsNull(matrixUserId)
        .map(user -> user.getUserId())
        .or(
            () ->
                consultantRepository
                    .findByMatrixUserIdAndDeleteDateIsNull(matrixUserId)
                    .map(consultant -> consultant.getId()))
        .orElse(null);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> membershipsOf(Map<String, Object> content) {
    Object raw = content.get("memberships");
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .filter(Map.class::isInstance)
        .map(item -> (Map<String, Object>) item)
        .toList();
  }

  private boolean isExpired(Map<String, Object> membership, LocalDateTime eventTime) {
    Object expires = membership.get("expires");
    if (!(expires instanceof Number value)) {
      expires = membership.get("m.expires");
    }
    return expires instanceof Number value
        && Instant.ofEpochMilli(value.longValue()).isBefore(eventTime.toInstant(ZoneOffset.UTC));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> contentOf(Map<String, Object> event) {
    Object content = event.get("content");
    return content instanceof Map<?, ?> ? (Map<String, Object>) content : Map.of();
  }

  private LocalDateTime eventTime(Map<String, Object> event) {
    Object timestamp = event.get("origin_server_ts");
    if (timestamp instanceof Number value) {
      return LocalDateTime.ofInstant(Instant.ofEpochMilli(value.longValue()), ZoneOffset.UTC);
    }
    return LocalDateTime.now(ZoneOffset.UTC);
  }

  private String callType(Map<String, Object> content) {
    Object video = content.get("is_video");
    if (video instanceof Boolean value) {
      return value ? "video" : "audio";
    }
    String raw = firstPresent(content, "call_type", "callType", "type");
    return raw != null && raw.toLowerCase().contains("video") ? "video" : "audio";
  }

  private LifecycleEvent normaliseLifecycleEvent(String eventType) {
    if (eventType.endsWith("invite") || eventType.endsWith("invited")) {
      return LifecycleEvent.INVITED;
    }
    if (eventType.endsWith("answer") || eventType.endsWith("started")) {
      return LifecycleEvent.STARTED;
    }
    if (eventType.endsWith("missed")) {
      return LifecycleEvent.MISSED;
    }
    return LifecycleEvent.ENDED;
  }

  private boolean isMembershipEvent(String eventType) {
    return eventType.equals("m.call.member") || eventType.equals("org.matrix.msc3401.call.member");
  }

  private LocalDateTime earlier(LocalDateTime existing, LocalDateTime candidate) {
    return existing == null || candidate.isBefore(existing) ? candidate : existing;
  }

  private static String firstPresent(Map<String, Object> values, String... keys) {
    for (String key : keys) {
      String value = stringValue(values.get(key));
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static String stringValue(Object raw) {
    if (raw == null) {
      return null;
    }
    String value = String.valueOf(raw);
    return value.isBlank() ? null : value;
  }

  private static void addPresent(Collection<String> target, String value) {
    if (value != null && !value.isBlank()) {
      target.add(value);
    }
  }

  private static void putIfPresent(Map<String, Object> params, String key, Object value) {
    if (value != null) {
      params.put(key, value);
    }
  }

  private String titleFor(String eventType, String callType) {
    String medium = "video".equals(callType) ? "Video call" : "Audio call";
    return switch (eventType) {
      case "call.invited" -> medium + " invitation";
      case "call.started" -> medium + " started";
      case "call.missed" -> "Missed " + medium.toLowerCase();
      default -> medium + " ended";
    };
  }

  private String textFor(String eventType, String callType) {
    String medium = "video".equals(callType) ? "video call" : "audio call";
    return switch (eventType) {
      case "call.invited" -> "You were invited to a " + medium + ".";
      case "call.started" -> "The " + medium + " has started.";
      case "call.missed" -> "You missed the " + medium + ".";
      default -> "The " + medium + " has ended.";
    };
  }

  private enum LifecycleEvent {
    INVITED,
    STARTED,
    ENDED,
    MISSED
  }

  private record ConversationContext(Long sessionId, Long tenantId, List<String> recipientIds) {}
}
