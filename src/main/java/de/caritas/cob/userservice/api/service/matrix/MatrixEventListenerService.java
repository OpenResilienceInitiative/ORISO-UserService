package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.liveevents.LiveEventNotificationService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.notification.PrivacyEnvelope;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.ConsultantMessageStatService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
  private final Optional<RedisMessageMirrorService> redisMessageMirrorService;
  private final @NonNull UserRepository userRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ConsultantMessageStatService consultantMessageStatService;
  private final @NonNull MatrixSyncCoordinationRegistry matrixSyncCoordinationRegistry;

  private OutboundHttpMetrics outboundHttpMetrics;

  // Batch scratch refreshed from the canonical session repository before processing each room.
  private final Map<String, Long> roomToSessionMap = new ConcurrentHashMap<>();

  // Recipient scratch paired with roomToSessionMap; never populated by HTTP registration.
  private final Map<String, Set<String>> roomToUsersMap = new ConcurrentHashMap<>();

  // Executor for async event processing
  private ExecutorService executorService;

  // Admin access token for Matrix /sync
  private String adminAccessToken;

  // Matrix sync token (updated after each sync)
  private String syncToken = null;

  // True only while this instance owns the shared Redis lease.
  private volatile boolean hasLeadership = false;

  // Flag to control sync loop
  private volatile boolean running = false;

  @Value("${matrix.event-listener.enabled:true}")
  private boolean eventListenerEnabled = true;

  @Autowired(required = false)
  void setOutboundHttpMetrics(OutboundHttpMetrics outboundHttpMetrics) {
    this.outboundHttpMetrics = outboundHttpMetrics;
  }

  // Backoff bounds (milliseconds) for both the token bootstrap and the sync error path.
  static final long INITIAL_BACKOFF_MS = 5_000L;
  static final long MAX_BACKOFF_MS = 60_000L;
  static final long LEASE_RETRY_MS = 1_000L;

  // Emit a heartbeat every N successful sync iterations so a healthy/stuck loop is observable.
  // performMatrixSync uses a 30s long-poll, so ~10 iterations is roughly every 5 minutes.
  static final int HEARTBEAT_EVERY_N_ITERATIONS = 10;

  // Re-bootstrap the admin token after this many consecutive sync failures (likely an auth issue).
  static final int SYNC_FAILURES_BEFORE_REBOOTSTRAP = 3;

  @PostConstruct
  public void initialize() {
    if (!eventListenerEnabled) {
      log.info("Matrix event listener is disabled");
      return;
    }
    log.info("🔷 Initializing Matrix Event Listener Service...");
    executorService = Executors.newSingleThreadExecutor();

    // Start Matrix sync loop in background
    executorService.submit(this::startMatrixSyncLoop);
  }

  @PreDestroy
  public void shutdown() {
    log.info("🔷 Shutting down Matrix Event Listener Service...");
    running = false;
    releaseLeadership();
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
   * Main sync loop - continuously polls Matrix /sync for new events. Uses long-polling with timeout
   * to get real-time updates.
   *
   * <p>A Redis lease ensures that only one service replica consumes the shared Matrix account at a
   * time. The durable cursor is committed only after the whole batch has completed.
   */
  private void startMatrixSyncLoop() {
    running = true;
    log.info("🔷 Starting Matrix sync loop...");

    long errorBackoffMs = INITIAL_BACKOFF_MS;
    long iteration = 0;
    int consecutiveSyncFailures = 0;

    while (running) {
      try {
        if (!hasLeadership) {
          if (!tryAcquireLeadership()) {
            sleep(LEASE_RETRY_MS);
            continue;
          }
          adminAccessToken = null;
        }

        if (adminAccessToken == null && !bootstrapAdminToken()) {
          if (!running) {
            break;
          }
          continue;
        }

        if (!matrixSyncCoordinationRegistry.renewLease()) {
          stepDownAfterLostLease();
          continue;
        }

        // Perform Matrix sync (long-polling with 30-second timeout)
        Map<String, Object> syncResult = performMatrixSync();

        if (syncResult != null) {
          // Re-check ownership after the long poll. A stale owner must never process or commit.
          if (!matrixSyncCoordinationRegistry.renewLease()) {
            stepDownAfterLostLease();
            continue;
          }
          processAndCommitSyncResult(syncResult);
          // Successful sync: reset the error backoff and failure counter.
          errorBackoffMs = INITIAL_BACKOFF_MS;
          consecutiveSyncFailures = 0;
        } else {
          // performMatrixSync swallows exceptions and returns null; treat as a soft failure so an
          // auth problem eventually forces a token re-bootstrap instead of spinning forever.
          consecutiveSyncFailures++;
          recordRetry("sync");
          if (consecutiveSyncFailures >= SYNC_FAILURES_BEFORE_REBOOTSTRAP) {
            log.warn(
                "⚠️ {} consecutive Matrix sync failures - re-acquiring admin token",
                consecutiveSyncFailures);
            adminAccessToken = null;
            if (!bootstrapAdminToken()) {
              break;
            }
            consecutiveSyncFailures = 0;
          }
        }

        // Heartbeat so a healthy/stuck loop is observable without log-spamming.
        iteration++;
        if (iteration % HEARTBEAT_EVERY_N_ITERATIONS == 0) {
          log.info("💓 Matrix sync loop healthy (iteration {})", iteration);
        }

        // Small delay to prevent CPU spinning if sync returns immediately.
        sleep(100);

      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        log.error("❌ Error in Matrix sync loop", e);
        recordRetry("sync-loop");
        try {
          // Exponential backoff before retrying on error (5s→10s→…→60s cap).
          sleep(errorBackoffMs);
          errorBackoffMs = nextBackoffMillis(errorBackoffMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    releaseLeadership();
    log.info("🔷 Matrix sync loop stopped");
  }

  private boolean tryAcquireLeadership() {
    if (!matrixSyncCoordinationRegistry.tryAcquireLease()) {
      return false;
    }
    try {
      syncToken = matrixSyncCoordinationRegistry.readCursor().orElse(null);
      hasLeadership = true;
      log.info("🔷 Matrix sync leadership acquired");
      return true;
    } catch (RuntimeException exception) {
      try {
        matrixSyncCoordinationRegistry.releaseLease();
      } catch (RuntimeException releaseFailure) {
        exception.addSuppressed(releaseFailure);
      }
      throw exception;
    }
  }

  private void stepDownAfterLostLease() {
    hasLeadership = false;
    adminAccessToken = null;
    syncToken = null;
    log.warn("⚠️ Matrix sync leadership lost; listener stepping down");
  }

  private void releaseLeadership() {
    if (!hasLeadership) {
      return;
    }
    try {
      matrixSyncCoordinationRegistry.releaseLease();
    } catch (RuntimeException exception) {
      log.warn("Matrix sync lease release failed; TTL remains the safety bound");
    } finally {
      hasLeadership = false;
      adminAccessToken = null;
      syncToken = null;
    }
  }

  /**
   * Acquire the Matrix admin token, retrying with exponential backoff until it succeeds or the loop
   * is shut down. On success {@link #adminAccessToken} is set.
   *
   * @return {@code true} once a token was obtained, {@code false} if the loop stopped (shutdown or
   *     interrupt) before a token could be acquired
   */
  private boolean bootstrapAdminToken() {
    long backoffMs = INITIAL_BACKOFF_MS;
    while (running) {
      if (hasLeadership && !matrixSyncCoordinationRegistry.renewLease()) {
        stepDownAfterLostLease();
        return false;
      }
      try {
        adminAccessToken = matrixSynapseService.getAdminToken();
        if (adminAccessToken != null) {
          log.info("✅ Admin token obtained for Matrix sync");
          return true;
        }
        log.error(
            "❌ Failed to get admin token - retrying in {}ms (sync loop not yet started)",
            backoffMs);
      } catch (Exception e) {
        log.error("❌ Error getting admin token - retrying in {}ms", backoffMs, e);
      }

      recordRetry("admin-token");
      try {
        sleep(backoffMs);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return false;
      }
      backoffMs = nextBackoffMillis(backoffMs);
    }
    return false;
  }

  private void recordRetry(String operation) {
    if (outboundHttpMetrics != null) {
      outboundHttpMetrics.recordRetry("matrix", operation);
    }
  }

  /**
   * Compute the next backoff delay: double the current delay, capped at {@link #MAX_BACKOFF_MS}.
   * Pure and side-effect free so it can be unit tested without real sleeps.
   *
   * @param currentMillis the current backoff delay in milliseconds
   * @return the next backoff delay in milliseconds (5s→10s→20s→40s→60s→60s…)
   */
  static long nextBackoffMillis(long currentMillis) {
    long doubled = currentMillis * 2;
    return Math.min(doubled, MAX_BACKOFF_MS);
  }

  /**
   * Sleep seam so the backoff timing can be stubbed out in unit tests (no real waiting).
   *
   * @param millis milliseconds to sleep
   * @throws InterruptedException if the thread is interrupted while sleeping
   */
  void sleep(long millis) throws InterruptedException {
    Thread.sleep(millis);
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

      return syncResult;

    } catch (Exception e) {
      log.error("❌ Matrix sync failed", e);
      return null;
    }
  }

  private void processAndCommitSyncResult(Map<String, Object> syncResult) {
    Object nextBatch = syncResult.get("next_batch");
    if (!(nextBatch instanceof String) || ((String) nextBatch).isBlank()) {
      throw new IllegalStateException("Matrix sync response did not contain a next_batch cursor");
    }

    processMatrixSyncEvents(syncResult);

    String nextCursor = (String) nextBatch;
    if (!matrixSyncCoordinationRegistry.commitCursor(nextCursor)) {
      stepDownAfterLostLease();
      throw new IllegalStateException("Matrix sync cursor commit rejected after lease loss");
    }
    syncToken = nextCursor;
    log.debug("🔷 Matrix sync cursor committed");
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

    // Process each room
    for (Map.Entry<String, Object> roomEntry : joinedRooms.entrySet()) {
      String roomId = roomEntry.getKey();
      Map<String, Object> roomData = (Map<String, Object>) roomEntry.getValue();

      // Refresh from the canonical database for every batch. A registration handled by another
      // replica, or a reassignment since the previous batch, must never leave this listener using
      // stale process-local routing state.
      if (!refreshRoomContextForBatch(roomId)) {
        continue;
      }

      try {
        log.info("🔷 Processing events for resolved room: {}", roomId);

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
      } finally {
        clearRoomContext(roomId);
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
        // E2EE rooms deliver messages as m.room.encrypted — the payload is opaque
        // (no msgtype/body), but sender + event id are cleartext, which is all the
        // metadata-only notification pipeline needs (preview mode NONE).
      case "m.room.encrypted":
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
    Optional<ResolvedMatrixSender> resolvedSender = resolveMatrixSender(senderId);
    String senderDomainUserId = resolvedSender.map(ResolvedMatrixSender::domainUserId).orElse(null);
    boolean senderIsConsultant = resolvedSender.map(ResolvedMatrixSender::consultant).orElse(false);
    String threadRootId = extractThreadRootId(content);
    String messageBody = extractMessageBody(content);
    PrivacyEnvelope privacyEnvelope =
        buildPrivacyEnvelope(event, roomId, senderId, msgtype, content);

    safeContentLog("matrix.message.received", privacyEnvelope);

    // Debug mirror: capture actual Matrix timeline messages so Redis Commander can show them.
    // This is feature-flagged/TTL-bound in RedisMessageMirrorService.
    Long sessionId = roomToSessionMap.get(roomId);
    redisMessageMirrorService.ifPresent(
        mirror ->
            mirror.mirrorOutgoingMessage(
                sessionId,
                roomId,
                senderId,
                senderIsConsultant,
                messageBody,
                event.get("event_id") != null ? String.valueOf(event.get("event_id")) : null));

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
        // The live STOMP push is best-effort; the persisted feed entry is the source of truth.
        try {
          liveEventNotificationService.sendLiveDirectMessageEventToUsers(roomId);
        } catch (Exception e) {
          log.error("❌ Failed to send LiveService notification", e);
        }
        if (threadRootId != null && !threadRootId.isBlank()) {
          eventNotificationService.createThreadReplyNotificationFromRoom(
              roomId, senderDomainUserId, threadRootId, true, privacyEnvelope);
        } else {
          eventNotificationService.createMessageNotificationFromRoom(
              roomId, senderDomainUserId, true, privacyEnvelope);
        }
        if (senderDomainUserId != null && senderIsConsultant) {
          consultantMessageStatService.recordMessageSent(
              senderDomainUserId, mappedSessionId, privacyEnvelope.getMessageId());
        }
      }
    }
  }

  private String resolveDomainUserIdFromMatrixUserId(String matrixUserId) {
    return resolveMatrixSender(matrixUserId).map(ResolvedMatrixSender::domainUserId).orElse(null);
  }

  private Optional<ResolvedMatrixSender> resolveMatrixSender(String matrixUserId) {
    if (matrixUserId == null || matrixUserId.isBlank()) {
      return Optional.empty();
    }
    return userRepository
        .findByMatrixUserIdAndDeleteDateIsNull(matrixUserId)
        .map(user -> new ResolvedMatrixSender(user.getUserId(), false))
        .or(
            () ->
                consultantRepository
                    .findByMatrixUserIdAndDeleteDateIsNull(matrixUserId)
                    .map(consultant -> new ResolvedMatrixSender(consultant.getId(), true)));
  }

  private record ResolvedMatrixSender(String domainUserId, boolean consultant) {}

  private boolean refreshRoomContextForBatch(String roomId) {
    Optional<Session> sessionOpt = sessionRepository.findByMatrixRoomId(roomId);
    if (sessionOpt.isEmpty()) {
      clearRoomContext(roomId);
      return false;
    }

    Session session = sessionOpt.get();
    roomToSessionMap.put(roomId, session.getId());
    roomToUsersMap.put(roomId, buildRecipientSet(session));
    return true;
  }

  private void clearRoomContext(String roomId) {
    roomToSessionMap.remove(roomId);
    roomToUsersMap.remove(roomId);
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
    // msgtype is null for m.room.encrypted events (opaque payload); Set.of(...) is
    // null-hostile, so guard before the membership check.
    boolean hasAttachment =
        (msgtype != null && Set.of("m.image", "m.file", "m.audio", "m.video").contains(msgtype))
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
    Set<String> userIds = getRecipientCandidatesForRoom(roomId);
    if (userIds.isEmpty()) {
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
