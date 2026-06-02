package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.liveevents.LiveEventNotificationService;
import de.caritas.cob.userservice.api.service.matrix.RedisMessageMirrorService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.notification.PrivacyEnvelope;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service to listen to Matrix events and trigger LiveService notifications. Uses Matrix /sync
 * endpoint for real-time event detection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatrixEventListenerService {

  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull SessionService sessionService;
  private final @NonNull LiveEventNotificationService liveEventNotificationService;
  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull RedisMessageMirrorService redisMessageMirrorService;
  private final @NonNull UserRepository userRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull SessionRepository sessionRepository;

  // Maps Matrix room ID to session ID for quick lookup
  private final Map<String, Long> roomToSessionMap = new ConcurrentHashMap<>();

  // Maps Matrix room ID to list of user IDs who should receive notifications
  private final Map<String, Set<String>> roomToUsersMap = new ConcurrentHashMap<>();

  // Executor for async event processing
  private ExecutorService executorService;

  // Admin access token for Matrix /sync
  private String adminAccessToken;

  // Matrix sync token (updated after each sync)
  private String syncToken = null;

  // Flag to control sync loop
  private volatile boolean running = false;

  @PostConstruct
  public void initialize() {
    log.info("🔷 Initializing Matrix Event Listener Service...");
    executorService = Executors.newFixedThreadPool(2);

    // Start Matrix sync loop in background
    executorService.submit(this::startMatrixSyncLoop);
  }

  @PreDestroy
  public void shutdown() {
    log.info("🔷 Shutting down Matrix Event Listener Service...");
    running = false;
    if (executorService != null) {
      executorService.shutdownNow();
      try {
        executorService.awaitTermination(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Register a session's Matrix room for event listening. This should be called when a session is
   * created or accessed.
   *
   * @param sessionId the session ID
   * @param matrixRoomId the Matrix room ID
   * @param userIds list of user IDs who should receive notifications
   */
  public void registerRoom(Long sessionId, String matrixRoomId, Set<String> userIds) {
    if (matrixRoomId != null && !matrixRoomId.isEmpty()) {
      roomToSessionMap.put(matrixRoomId, sessionId);
      roomToUsersMap.put(matrixRoomId, userIds);
      log.info(
          "🔷 Registered Matrix room {} for session {} with {} users",
          matrixRoomId,
          sessionId,
          userIds.size());
    }
  }

  /**
   * Unregister a Matrix room (when session ends).
   *
   * @param matrixRoomId the Matrix room ID
   */
  public void unregisterRoom(String matrixRoomId) {
    roomToSessionMap.remove(matrixRoomId);
    roomToUsersMap.remove(matrixRoomId);
    log.info("🔷 Unregistered Matrix room {}", matrixRoomId);
  }

  /**
   * Main sync loop - continuously polls Matrix /sync for new events. Uses long-polling with timeout
   * to get real-time updates.
   */
  private void startMatrixSyncLoop() {
    running = true;
    log.info("🔷 Starting Matrix sync loop...");

    // Get admin token
    try {
      adminAccessToken = matrixSynapseService.getAdminToken();
      if (adminAccessToken == null) {
        log.error("❌ Failed to get admin token - sync loop cannot start");
        return;
      }
      log.info("✅ Admin token obtained for Matrix sync");
    } catch (Exception e) {
      log.error("❌ Error getting admin token", e);
      return;
    }

    while (running) {
      try {
        // Perform Matrix sync (long-polling with 30-second timeout)
        Map<String, Object> syncResult = performMatrixSync();

        if (syncResult != null) {
          // Process events from sync result
          processMatrixSyncEvents(syncResult);
        }

        // Small delay to prevent CPU spinning if sync fails immediately
        Thread.sleep(100);

      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        log.error("❌ Error in Matrix sync loop", e);
        try {
          // Wait before retrying on error
          Thread.sleep(5000);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    log.info("🔷 Matrix sync loop stopped");
  }

  /**
   * Perform Matrix /sync API call with long-polling.
   *
   * @return sync result from Matrix
   */
  private Map<String, Object> performMatrixSync() {
    try {
      // Build sync URL with optional since token
      String syncUrl = matrixSynapseService.getMatrixApiUrl() + "/_matrix/client/r0/sync";
      if (syncToken != null) {
        syncUrl += "?since=" + syncToken + "&timeout=30000";
      } else {
        syncUrl += "?timeout=30000";
      }

      // Call Matrix sync endpoint
      Map<String, Object> syncResult =
          matrixSynapseService.makeMatrixRequest(syncUrl, "GET", adminAccessToken, null);

      // Update sync token for next request
      if (syncResult != null && syncResult.containsKey("next_batch")) {
        syncToken = (String) syncResult.get("next_batch");
        log.debug("🔷 Sync token updated: {}", syncToken);
      }

      return syncResult;

    } catch (Exception e) {
      log.error("❌ Matrix sync failed", e);
      return null;
    }
  }

  /**
   * Process events from Matrix sync response.
   *
   * @param syncResult the sync result from Matrix
   */
  @SuppressWarnings("unchecked")
  private void processMatrixSyncEvents(Map<String, Object> syncResult) {
    if (syncResult == null || !syncResult.containsKey("rooms")) {
      return;
    }

    Map<String, Object> rooms = (Map<String, Object>) syncResult.get("rooms");
    if (rooms == null || !rooms.containsKey("join")) {
      return;
    }

    Map<String, Object> joinedRooms = (Map<String, Object>) rooms.get("join");
    if (joinedRooms == null) {
      return;
    }

    // Log registered rooms (for debugging)
    if (!roomToSessionMap.isEmpty()) {
      log.debug("🔷 Registered rooms: {}", roomToSessionMap.keySet());
    }

    // Process each room
    for (Map.Entry<String, Object> roomEntry : joinedRooms.entrySet()) {
      String roomId = roomEntry.getKey();
      Map<String, Object> roomData = (Map<String, Object>) roomEntry.getValue();

      // Resolve session context even if room wasn't explicitly registered by UI.
      Optional<Long> sessionIdOpt = resolveSessionIdForRoom(roomId);
      if (sessionIdOpt.isEmpty()) {
        continue;
      }

      log.info("🔷 Processing events for registered room: {}", roomId);

      // Process timeline events
      if (roomData.containsKey("timeline")) {
        Map<String, Object> timeline = (Map<String, Object>) roomData.get("timeline");
        if (timeline.containsKey("events")) {
          List<Map<String, Object>> events = (List<Map<String, Object>>) timeline.get("events");

          for (Map<String, Object> event : events) {
            processMatrixEvent(roomId, event);
          }
        }
      }
    }
  }

  /**
   * Process a single Matrix event and trigger appropriate LiveService notifications.
   *
   * @param roomId the Matrix room ID
   * @param event the Matrix event
   */
  @SuppressWarnings("unchecked")
  private void processMatrixEvent(String roomId, Map<String, Object> event) {
    String eventType = (String) event.get("type");
    String senderId = (String) event.get("sender");

    if (eventType == null) {
      return;
    }

    log.debug("🔷 Matrix event: {} in room {} from {}", eventType, roomId, senderId);

    // Handle different event types
    switch (eventType) {
      case "m.room.message":
        handleRoomMessage(roomId, event);
        break;

      case "m.call.invite":
        handleCallInvite(roomId, event);
        break;

      case "m.call.answer":
        handleCallAnswer(roomId, event);
        break;

      case "m.call.hangup":
        handleCallHangup(roomId, event);
        break;

      default:
        // Ignore other event types
        break;
    }
  }

  /**
   * Handle m.room.message event - trigger directMessage live event.
   *
   * @param roomId the Matrix room ID
   * @param event the message event
   */
  @SuppressWarnings("unchecked")
  private void handleRoomMessage(String roomId, Map<String, Object> event) {
    String senderId = (String) event.get("sender");
    Map<String, Object> content = (Map<String, Object>) event.get("content");

    if (content == null) {
      return;
    }

    String msgtype = (String) content.get("msgtype");
    String senderDomainUserId = resolveDomainUserIdFromMatrixUserId(senderId);
    String threadRootId = extractThreadRootId(content);
    String messageBody = extractMessageBody(content);
    PrivacyEnvelope privacyEnvelope = buildPrivacyEnvelope(event, roomId, senderId, msgtype, content);

    safeContentLog("matrix.message.received", privacyEnvelope);

    // Debug mirror: capture actual Matrix timeline messages so Redis Commander can show them.
    // This is feature-flagged/TTL-bound in RedisMessageMirrorService.
    Long sessionId = roomToSessionMap.get(roomId);
    redisMessageMirrorService.mirrorOutgoingMessage(
        sessionId,
        roomId,
        senderId,
        senderDomainUserId != null && senderDomainUserId.startsWith("consultant"),
        messageBody,
        event.get("event_id") != null ? String.valueOf(event.get("event_id")) : null);

    // Get users who should receive notification (exclude sender)
    Set<String> userIds = getRecipientCandidatesForRoom(roomId);
    if (userIds == null || userIds.isEmpty()) {
      return;
    }

    // Trigger LiveService directMessage event for all users except sender
    List<String> recipientIds =
        userIds.stream()
            .filter(userId -> senderDomainUserId == null || !userId.equals(senderDomainUserId))
            .collect(java.util.stream.Collectors.toList());

    if (!recipientIds.isEmpty()) {
      log.info("🔔 Triggering LiveService directMessage event for {} users", recipientIds.size());

      // Use existing LiveService notification service
      // Note: We need to convert Matrix room ID to session/group ID
      Long mappedSessionId = roomToSessionMap.get(roomId);
      if (mappedSessionId != null) {
        // Trigger notification asynchronously to not block sync loop
        executorService.submit(
            () -> {
              try {
                liveEventNotificationService.sendLiveDirectMessageEventToUsers(roomId);
                if (threadRootId != null && !threadRootId.isBlank()) {
                  eventNotificationService.createThreadReplyNotificationFromRoom(
                      roomId, senderDomainUserId, threadRootId, true, privacyEnvelope);
                } else {
                  eventNotificationService.createMessageNotificationFromRoom(
                      roomId, senderDomainUserId, true, privacyEnvelope);
                }
              } catch (Exception e) {
                log.error("❌ Failed to send LiveService notification", e);
              }
            });
      }
    }
  }

  private String resolveDomainUserIdFromMatrixUserId(String matrixUserId) {
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

  private Optional<Long> resolveSessionIdForRoom(String roomId) {
    Long cached = roomToSessionMap.get(roomId);
    if (cached != null) {
      return Optional.of(cached);
    }

    Optional<Session> sessionOpt = sessionRepository.findByMatrixRoomId(roomId);
    if (sessionOpt.isEmpty()) {
      return Optional.empty();
    }

    Session session = sessionOpt.get();
    roomToSessionMap.put(roomId, session.getId());
    roomToUsersMap.put(roomId, buildRecipientSet(session));
    return Optional.of(session.getId());
  }

  private Set<String> getRecipientCandidatesForRoom(String roomId) {
    Set<String> cached = roomToUsersMap.get(roomId);
    if (cached != null && !cached.isEmpty()) {
      return cached;
    }

    Optional<Session> sessionOpt = sessionRepository.findByMatrixRoomId(roomId);
    if (sessionOpt.isEmpty()) {
      return Collections.emptySet();
    }

    Session session = sessionOpt.get();
    Set<String> recipients = buildRecipientSet(session);
    roomToSessionMap.put(roomId, session.getId());
    roomToUsersMap.put(roomId, recipients);
    return recipients;
  }

  private Set<String> buildRecipientSet(Session session) {
    Set<String> userIds = new HashSet<>();
    if (session.getUser() != null && session.getUser().getUserId() != null) {
      userIds.add(session.getUser().getUserId());
    }
    if (session.getConsultant() != null && session.getConsultant().getId() != null) {
      userIds.add(session.getConsultant().getId());
    }
    return userIds;
  }

  @SuppressWarnings("unchecked")
  private String extractThreadRootId(Map<String, Object> content) {
    if (content == null) {
      return null;
    }
    Object relatesToRaw = content.get("m.relates_to");
    if (!(relatesToRaw instanceof Map)) {
      return null;
    }
    Map<String, Object> relatesTo = (Map<String, Object>) relatesToRaw;
    String relType = String.valueOf(relatesTo.getOrDefault("rel_type", ""));
    if (!"m.thread".equals(relType)) {
      return null;
    }
    Object eventId = relatesTo.get("event_id");
    return eventId != null ? String.valueOf(eventId) : null;
  }

  private void safeContentLog(String marker, PrivacyEnvelope envelope) {
    if (envelope == null) {
      log.debug("🔒 {} room=unknown", marker);
      return;
    }
    log.debug(
        "🔒 {} room={} messageId={} sender={} contentClass={} hasAttachment={} ts={}",
        marker,
        envelope.getRoomId(),
        envelope.getMessageId(),
        envelope.getSenderId(),
        envelope.getContentClass(),
        envelope.isHasAttachment(),
        envelope.getTimestamp());
  }

  @SuppressWarnings("unchecked")
  private PrivacyEnvelope buildPrivacyEnvelope(
      Map<String, Object> event,
      String roomId,
      String senderId,
      String msgtype,
      Map<String, Object> content) {
    String contentClass = classifyContent(msgtype);
    boolean hasAttachment =
        Set.of("m.image", "m.file", "m.audio", "m.video").contains(msgtype)
            || (content != null && (content.containsKey("url") || content.containsKey("file")));

    Long timestamp = null;
    Object timestampRaw = event.get("origin_server_ts");
    if (timestampRaw instanceof Number) {
      timestamp = ((Number) timestampRaw).longValue();
    }

    Object eventIdRaw = event.get("event_id");
    String eventId = eventIdRaw == null ? null : String.valueOf(eventIdRaw);

    return PrivacyEnvelope.builder()
        .messageId(eventId)
        .roomId(roomId)
        .senderId(senderId)
        .timestamp(timestamp)
        .hasAttachment(hasAttachment)
        .contentClass(contentClass)
        .build();
  }

  private String classifyContent(String msgtype) {
    if (msgtype == null || msgtype.isBlank()) {
      return "UNKNOWN";
    }
    switch (msgtype) {
      case "m.text":
        return "TEXT";
      case "m.image":
        return "IMAGE";
      case "m.file":
        return "FILE";
      case "m.audio":
        return "AUDIO";
      case "m.video":
        return "VIDEO";
      case "m.notice":
        return "NOTICE";
      case "m.emote":
        return "EMOTE";
      default:
        return "OTHER";
    }
  }

  @SuppressWarnings("unchecked")
  private String extractMessageBody(Map<String, Object> content) {
    if (content == null) {
      return null;
    }

    Object body = content.get("body");
    if (body != null) {
      return String.valueOf(body);
    }

    Object formattedBody = content.get("formatted_body");
    if (formattedBody != null) {
      return String.valueOf(formattedBody);
    }

    Object relatesToRaw = content.get("m.relates_to");
    if (relatesToRaw instanceof Map) {
      Object eventId = ((Map<String, Object>) relatesToRaw).get("event_id");
      if (eventId != null) {
        return "thread-reply:" + eventId;
      }
    }

    return null;
  }

  /**
   * Handle m.call.invite event - trigger videoCallRequest live event.
   *
   * @param roomId the Matrix room ID
   * @param event the call invite event
   */
  @SuppressWarnings("unchecked")
  private void handleCallInvite(String roomId, Map<String, Object> event) {
    String senderId = (String) event.get("sender");
    Map<String, Object> content = (Map<String, Object>) event.get("content");

    if (content == null) {
      return;
    }

    String callId = (String) content.get("call_id");
    Integer lifetime = (Integer) content.get("lifetime");

    log.info(
        "📞 Incoming Matrix call in room {} from {} (call_id: {}, lifetime: {})",
        roomId,
        senderId,
        callId,
        lifetime);

    // Get users who should receive notification (exclude sender)
    Set<String> userIds = roomToUsersMap.get(roomId);
    if (userIds == null || userIds.isEmpty()) {
      return;
    }

    List<String> recipientIds =
        userIds.stream()
            .filter(userId -> !userId.equals(senderId))
            .collect(java.util.stream.Collectors.toList());

    if (!recipientIds.isEmpty()) {
      log.info(
          "🔔 Triggering LiveService videoCallRequest event for {} users", recipientIds.size());

      // TODO: Implement videoCallRequest event trigger
      // This requires extending LiveEventNotificationService to support call events
      // For now, we'll log it
      log.warn("⚠️ videoCallRequest live event not yet implemented");
    }
  }

  /**
   * Handle m.call.answer event.
   *
   * @param roomId the Matrix room ID
   * @param event the call answer event
   */
  private void handleCallAnswer(String roomId, Map<String, Object> event) {
    String senderId = (String) event.get("sender");
    log.info("📞 Call answered in room {} by {}", roomId, senderId);
    // Can trigger additional live events if needed
  }

  /**
   * Handle m.call.hangup event.
   *
   * @param roomId the Matrix room ID
   * @param event the call hangup event
   */
  private void handleCallHangup(String roomId, Map<String, Object> event) {
    String senderId = (String) event.get("sender");
    log.info("📞 Call ended in room {} by {}", roomId, senderId);
    // Can trigger additional live events if needed
  }
}
