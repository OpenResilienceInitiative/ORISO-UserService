package de.caritas.cob.userservice.api.service.notification;

import static java.util.Objects.nonNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.EventNotification;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventNotificationService {

  public static final String CATEGORY_SYSTEM = "system";
  public static final String CATEGORY_MESSAGE = "message";

  /** Matches the {@code deduplication_key} column length (VARCHAR(191), changeset 0063). */
  static final int MAX_DEDUPLICATION_KEY_LENGTH = 191;

  private static final String SYSTEM_NOTIFICATION_PREFIX = "[SYSTEM_NOTIFICATION]";
  private static final String REDACTED_PREVIEW = "[content hidden]";
  private static final long ACTIVE_VIEW_TTL_NANOS = Duration.ofSeconds(30).toNanos();

  private final @NonNull EventNotificationRepository eventNotificationRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull UserRepository userRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull IdentityTombstoneService identityTombstoneService;
  private final @NonNull EventNotificationDeduplicationWriter deduplicationWriter;
  private final Map<String, ActiveViewState> activeViewByUserId = new ConcurrentHashMap<>();
  private final ObjectMapper paramsObjectMapper = new ObjectMapper();
  private volatile LongSupplier monotonicNanos = System::nanoTime;

  @Value("${privacy.notificationPreviewMode:NONE}")
  private String notificationPreviewMode;

  @Transactional
  public void createInquiryAcceptedNotification(Session session, Consultant consultant) {
    if (session == null || session.getUser() == null || session.getUser().getUserId() == null) {
      return;
    }

    String consultantName = resolveConsultantName(consultant);
    Map<String, Object> params = baseParams(session);
    params.put("consultantName", consultantName);
    createEvent(
        session.getUser().getUserId(),
        "inquiry.accepted",
        CATEGORY_SYSTEM,
        "Inquiry accepted",
        String.format("Your request was accepted by %s. Chat is now active.", consultantName),
        serializeParams(params),
        buildSessionActionPath(session),
        session.getId(),
        session.getTenantId());
  }

  @Transactional
  public void createSupervisorAddedNotification(
      Session session, String recipientUserId, String supervisorName) {
    Map<String, Object> params = baseParams(session);
    putIfPresent(params, "supervisorName", supervisorName);
    createEvent(
        recipientUserId,
        "supervisor.added",
        CATEGORY_SYSTEM,
        "Consultant added to your chat",
        String.format(
            "%s was added as a consultant supervisor to your chat #%s.",
            safeValue(supervisorName, "A supervisor"), session.getId()),
        serializeParams(params),
        buildSessionActionPath(session),
        session.getId(),
        session.getTenantId());
  }

  /**
   * WP-06 Slice 2b: notify the consultant who was just assigned as supervisor. Extracted from
   * SessionSupervisorController so every event_notification producer goes through this service and
   * carries structured params uniformly.
   */
  @Transactional
  public void createSupervisorAssignedNotification(Session session, String recipientConsultantId) {
    if (session == null || recipientConsultantId == null || recipientConsultantId.isBlank()) {
      return;
    }
    createEvent(
        recipientConsultantId,
        "supervisor.assigned",
        CATEGORY_SYSTEM,
        "Supervisor assignment",
        String.format("You were added as supervisor to chat #%s.", session.getId()),
        serializeParams(baseParams(session)),
        buildSessionActionPath(session, null, true),
        session.getId(),
        session.getTenantId());
  }

  @Transactional
  public void createSupervisorRemovedNotification(
      Session session, String recipientUserId, String supervisorName) {
    if (session == null || recipientUserId == null || recipientUserId.isBlank()) {
      return;
    }
    Map<String, Object> params = baseParams(session);
    putIfPresent(params, "supervisorName", supervisorName);
    createEvent(
        recipientUserId,
        "supervisor.removed",
        CATEGORY_SYSTEM,
        "Supervisor removed",
        String.format(
            "%s was removed from supervision for chat #%s.",
            safeValue(supervisorName, "A supervisor"), session.getId()),
        serializeParams(params),
        buildSessionActionPath(session),
        session.getId(),
        session.getTenantId());
  }

  @Transactional
  public void createCounselorRenamedNotification(
      Session session, String recipientUserId, String oldDisplayName, String newDisplayName) {
    if (session == null || recipientUserId == null || recipientUserId.isBlank()) {
      return;
    }
    String previous = safeValue(oldDisplayName, "your counselor");
    String updated = safeValue(newDisplayName, "your counselor");
    String changedAt = LocalDateTime.now(ZoneOffset.UTC).toString();
    Map<String, Object> params = baseParams(session);
    params.put("oldName", previous);
    params.put("newName", updated);
    createEvent(
        recipientUserId,
        "counselor.renamed",
        CATEGORY_SYSTEM,
        "Counselor name updated",
        String.format(
            "Your counselor display name changed from \"%s\" to \"%s\" at %s UTC.",
            previous, updated, changedAt),
        serializeParams(params),
        buildSessionActionPath(session),
        session.getId(),
        session.getTenantId());
  }

  /**
   * WP-06 Slice 2 (Tier 1): persist a "New client request" timeline event ({@code request.new}) for
   * each eligible consultant when a new enquiry is created. The recipient population mirrors the
   * consultants already notified by email — the Activity Timeline is the consultant's inbox.
   *
   * <p>Carries structured params (ADR-AT-01) for client-side rendering, plus a title/text fallback
   * so today's frontend renders the same card until it reads params. Best-effort: persisting a
   * notification must never break enquiry creation, so per-recipient failures are swallowed and
   * logged.
   */
  @Transactional
  /**
   * ADR-018 §11 / ORISO-UserService#926: <b>exactly one</b> Activity Timeline entry for the whole
   * Erstantwort, targeting the chat.
   *
   * <p>One entry, not one per open action. A person who has just written a first message about
   * something hard should not receive three to-do items in response — that was the explicit
   * rejection recorded in ADR-018 §11.
   *
   * <p>This is also the "narrow exception" the issue asks for, and it is narrow by construction
   * rather than by a flag: system notifications are filtered out of the timeline on the <b>Matrix
   * ingestion</b> path ({@code isSystemNotificationMessage}), where an unfiltered system message
   * would produce a timeline row for every internal event in every room. That filter stays exactly
   * as strict as it is. The Erstantwort instead writes its own single row here, from the enquiry
   * facade, so nothing else slips through with it.
   *
   * <p>Failures are swallowed: a missing timeline row must never fail the enquiry.
   *
   * @param session the session whose advice seeker received the Erstantwort
   */
  public void createFirstResponseNotification(Session session) {
    if (session == null || session.getUser() == null) {
      return;
    }
    var recipientUserId = session.getUser().getUserId();
    if (recipientUserId == null || recipientUserId.isBlank()) {
      return;
    }
    try {
      createEvent(
          recipientUserId,
          "first_response.received",
          CATEGORY_SYSTEM,
          "Ihre ersten Schritte",
          "Wir haben Ihnen im Chat die wichtigsten Informationen geschrieben.",
          /* The frontend resolves the room reference out of `params`. Without them
          the entry still renders but has no chat to open, which for the single
          timeline entry the Erstantwort is allowed to produce means the person is
          told something happened and given no way back to it. */
          serializeParams(baseParams(session)),
          buildAskerSessionActionPath(session),
          session.getId(),
          session.getTenantId());
    } catch (RuntimeException ex) {
      log.warn(
          "Could not persist first_response.received notification for session {}",
          session.getId(),
          ex);
    }
  }

  /** The advice seeker's own view of their conversation — never the consultant preview list. */
  private String buildAskerSessionActionPath(Session session) {
    String listPath = "/sessions/user/view";
    if (session == null || session.getId() == null) {
      return listPath;
    }
    var roomRef = session.getMatrixRoomId();
    if (roomRef == null || roomRef.isBlank()) {
      return listPath;
    }
    return listPath + "/" + roomRef + "/" + session.getId();
  }

  public void createNewClientRequestNotifications(
      Session session, Collection<String> recipientConsultantIds) {
    if (session == null || recipientConsultantIds == null) {
      return;
    }
    String actionPath = buildEnquiryActionPath(session);
    String params = buildNewClientRequestParams(session);
    recipientConsultantIds.stream()
        .filter(id -> id != null && !id.isBlank())
        .distinct()
        .forEach(
            consultantId -> {
              try {
                createEvent(
                    consultantId,
                    "request.new",
                    CATEGORY_SYSTEM,
                    "New client request",
                    "A new client request is waiting.",
                    params,
                    actionPath,
                    session.getId(),
                    session.getTenantId());
              } catch (RuntimeException ex) {
                log.warn(
                    "Could not persist request.new notification for consultant {} (session {})",
                    consultantId,
                    session.getId(),
                    ex);
              }
            });
  }

  /**
   * WP-06 Slice 3 (Tier 3): persist a {@code waiting_room.client.joined} timeline event for each
   * eligible consultant when a client enters the anonymous live-chat waiting room (a new anonymous
   * enquiry). Mirrors the recipients already reached by the {@code NEW_ANONYMOUS_ENQUIRY} live
   * event — the Activity Timeline is the consultant's inbox — and carries the same structured
   * params (ADR-AT-01) plus a title/text fallback. Best-effort: a failed notification must never
   * break anonymous-conversation creation, so per-recipient failures are swallowed and logged.
   */
  @Transactional
  public void createWaitingRoomClientJoinedNotifications(
      Session session, Collection<String> recipientConsultantIds) {
    if (session == null || recipientConsultantIds == null) {
      return;
    }
    String actionPath = buildEnquiryActionPath(session);
    String params = buildNewClientRequestParams(session);
    recipientConsultantIds.stream()
        .filter(id -> id != null && !id.isBlank())
        .distinct()
        .forEach(
            consultantId -> {
              try {
                createEvent(
                    consultantId,
                    "waiting_room.client.joined",
                    CATEGORY_SYSTEM,
                    "Client joined the waiting room",
                    "A client is waiting in the live chat.",
                    params,
                    actionPath,
                    session.getId(),
                    session.getTenantId());
              } catch (RuntimeException ex) {
                log.warn(
                    "Could not persist waiting_room.client.joined notification for consultant {} "
                        + "(session {})",
                    consultantId,
                    session.getId(),
                    ex);
              }
            });
  }

  private String buildNewClientRequestParams(Session session) {
    Map<String, Object> params = baseParams(session);
    if (session.getAgencyId() != null) {
      params.put("agencyId", session.getAgencyId());
    }
    if (session.getMainTopicId() != null) {
      params.put("topicId", session.getMainTopicId());
    }
    params.put("consultingTypeId", session.getConsultingTypeId());
    return serializeParams(params);
  }

  // WP-06 Slice 2b: structured params for message/thread events. Per ADR-AT-01 these carry only
  // non-content metadata (sender label, content class, recipient role, thread id) — NEVER the
  // message preview/body, which stays client-hydrated from the Matrix room.
  private String buildMessageParams(
      Session session, String senderName, String contentClass, String recipientRole) {
    Map<String, Object> params = baseParams(session);
    putIfPresent(params, "senderName", senderName);
    putIfPresent(params, "contentClass", contentClass);
    putIfPresent(params, "recipientRole", recipientRole);
    return serializeParams(params);
  }

  private String buildThreadReplyParams(
      Session session,
      String senderName,
      String contentClass,
      String threadRootId,
      String recipientRole) {
    Map<String, Object> params = baseParams(session);
    putIfPresent(params, "senderName", senderName);
    putIfPresent(params, "contentClass", contentClass);
    putIfPresent(params, "threadRootId", threadRootId);
    putIfPresent(params, "recipientRole", recipientRole);
    return serializeParams(params);
  }

  /**
   * Seed a params map with the session id every timeline event references, plus the Matrix room
   * reference when the session has one (#846) — the frontend resolves rooms from params instead of
   * string-splitting actionPath.
   */
  private Map<String, Object> baseParams(Session session) {
    Map<String, Object> params = new LinkedHashMap<>();
    if (session != null && session.getId() != null) {
      params.put("sessionId", session.getId());
    }
    if (session != null
        && session.getMatrixRoomId() != null
        && !session.getMatrixRoomId().isBlank()) {
      params.put("roomRef", session.getMatrixRoomId());
    }
    return params;
  }

  private void putIfPresent(Map<String, Object> params, String key, String value) {
    if (value != null && !value.isBlank()) {
      params.put(key, value);
    }
  }

  private String serializeParams(Map<String, Object> params) {
    if (params == null || params.isEmpty()) {
      return null;
    }
    try {
      return paramsObjectMapper.writeValueAsString(params);
    } catch (JsonProcessingException ex) {
      log.warn("Could not serialize event_notification params", ex);
      return null;
    }
  }

  @Transactional
  public void createMessageNotificationFromRoom(
      String roomId, String senderUserId, String messagePreview) {
    createMessageNotificationFromRoom(roomId, senderUserId, messagePreview, false, null, null);
  }

  @Transactional
  public void createMessageNotificationFromRoom(
      String roomId, String senderUserId, PrivacyEnvelope envelope) {
    createMessageNotificationFromRoom(roomId, senderUserId, null, false, null, envelope);
  }

  @Transactional
  public void createMessageNotificationFromRoom(
      String roomId,
      String senderUserId,
      String messagePreview,
      boolean supervisorMessage,
      String senderDisplayName) {
    createMessageNotificationFromRoom(
        roomId, senderUserId, messagePreview, supervisorMessage, senderDisplayName, null);
  }

  @Transactional
  public void createMessageNotificationFromRoom(
      String roomId,
      String senderUserId,
      String messagePreview,
      boolean supervisorMessage,
      String senderDisplayName,
      PrivacyEnvelope envelope) {
    if (roomId == null || roomId.isBlank()) {
      return;
    }

    Optional<Session> sessionOpt = sessionRepository.findByMatrixRoomId(roomId);
    if (sessionOpt.isEmpty()) {
      return;
    }
    if (isSystemNotificationMessage(messagePreview)) {
      return;
    }

    Session session = sessionOpt.get();
    String senderLabel = resolveSenderName(senderUserId, senderDisplayName);
    String text = buildMessageNotificationText(senderLabel, messagePreview, envelope);
    String contentClass = envelope != null ? envelope.getContentClass() : null;

    if (!supervisorMessage
        && session.getUser() != null
        && session.getUser().getUserId() != null
        && !session.getUser().getUserId().equals(senderUserId)
        && !shouldSuppressNotification(session.getUser().getUserId(), roomId, null)) {
      createMessageEventDeduplicated(
          "message.new",
          envelope != null ? envelope.getMessageId() : null,
          session.getUser().getUserId(),
          "New message",
          text,
          buildMessageParams(session, senderLabel, contentClass, "user"),
          buildSessionActionPathForRecipient(session, session.getUser().getUserId(), null),
          session.getId(),
          session.getTenantId());
    }

    if (session.getConsultant() != null
        && session.getConsultant().getId() != null
        && !session.getConsultant().getId().equals(senderUserId)
        && !shouldSuppressNotification(session.getConsultant().getId(), roomId, null)) {
      createMessageEventDeduplicated(
          "message.new",
          envelope != null ? envelope.getMessageId() : null,
          session.getConsultant().getId(),
          "New message",
          text,
          buildMessageParams(session, senderLabel, contentClass, "consultant"),
          buildSessionActionPathForRecipient(session, session.getConsultant().getId(), null),
          session.getId(),
          session.getTenantId());
    }
  }

  @Transactional
  public void createThreadReplyNotificationFromRoom(
      String roomId, String senderUserId, String threadRootId, PrivacyEnvelope envelope) {
    createThreadReplyNotificationFromRoom(
        roomId, senderUserId, null, threadRootId, false, null, null, envelope);
  }

  @Transactional
  public void createThreadReplyNotificationFromRoom(
      String roomId, String senderUserId, String messagePreview, String threadRootId) {
    createThreadReplyNotificationFromRoom(
        roomId, senderUserId, messagePreview, threadRootId, false, null, null, null);
  }

  @Transactional
  public void createThreadReplyNotificationFromRoom(
      String roomId,
      String senderUserId,
      String messagePreview,
      String threadRootId,
      boolean supervisorMessage,
      String senderDisplayName,
      String threadParentPreview) {
    createThreadReplyNotificationFromRoom(
        roomId,
        senderUserId,
        messagePreview,
        threadRootId,
        supervisorMessage,
        senderDisplayName,
        threadParentPreview,
        null);
  }

  @Transactional
  public void createThreadReplyNotificationFromRoom(
      String roomId,
      String senderUserId,
      String messagePreview,
      String threadRootId,
      boolean supervisorMessage,
      String senderDisplayName,
      String threadParentPreview,
      PrivacyEnvelope envelope) {
    if (roomId == null || roomId.isBlank()) {
      return;
    }

    Optional<Session> sessionOpt = sessionRepository.findByMatrixRoomId(roomId);
    if (sessionOpt.isEmpty()) {
      return;
    }
    if (isSystemNotificationMessage(messagePreview)) {
      return;
    }

    Session session = sessionOpt.get();
    String senderLabel = resolveSenderName(senderUserId, senderDisplayName);
    String text =
        buildThreadReplyNotificationText(
            senderLabel, messagePreview, threadParentPreview, envelope);
    String contentClass = envelope != null ? envelope.getContentClass() : null;

    if (!supervisorMessage
        && session.getUser() != null
        && session.getUser().getUserId() != null
        && !session.getUser().getUserId().equals(senderUserId)
        && !shouldSuppressNotification(session.getUser().getUserId(), roomId, threadRootId)) {
      createMessageEventDeduplicated(
          "thread.reply.new",
          envelope != null ? envelope.getMessageId() : null,
          session.getUser().getUserId(),
          "New thread reply",
          text,
          buildThreadReplyParams(session, senderLabel, contentClass, threadRootId, "user"),
          buildSessionActionPathForRecipient(session, session.getUser().getUserId(), threadRootId),
          session.getId(),
          session.getTenantId());
    }

    if (session.getConsultant() != null
        && session.getConsultant().getId() != null
        && !session.getConsultant().getId().equals(senderUserId)
        && !shouldSuppressNotification(session.getConsultant().getId(), roomId, threadRootId)) {
      createMessageEventDeduplicated(
          "thread.reply.new",
          envelope != null ? envelope.getMessageId() : null,
          session.getConsultant().getId(),
          "New thread reply",
          text,
          buildThreadReplyParams(session, senderLabel, contentClass, threadRootId, "consultant"),
          buildSessionActionPathForRecipient(
              session, session.getConsultant().getId(), threadRootId),
          session.getId(),
          session.getTenantId());
    }
  }

  @Transactional(readOnly = true)
  public NotificationFeedResponse getFeed(String recipientUserId, int page, int perPage) {
    int safePage = Math.max(0, page);
    int safePerPage = Math.max(1, Math.min(perPage, 100));

    var pageable = PageRequest.of(safePage, safePerPage);
    List<NotificationItem> items =
        eventNotificationRepository
            .findByRecipientUserIdOrderByCreateDateDescIdDesc(recipientUserId, pageable)
            .stream()
            .map(this::toItem)
            .collect(Collectors.toList());

    long unreadCount =
        eventNotificationRepository.countByRecipientUserIdAndReadDateIsNull(recipientUserId);
    return NotificationFeedResponse.builder()
        .items(items)
        .unreadCount(unreadCount)
        .page(safePage)
        .perPage(safePerPage)
        .build();
  }

  @Transactional
  public void markAsRead(String recipientUserId, Long notificationId) {
    eventNotificationRepository
        .findByIdAndRecipientUserId(notificationId, recipientUserId)
        .ifPresent(
            item -> {
              if (item.getReadDate() == null) {
                item.setReadDate(LocalDateTime.now());
                eventNotificationRepository.save(item);
              }
            });
  }

  @Transactional
  public void markAllAsRead(String recipientUserId) {
    var unread =
        eventNotificationRepository.findByRecipientUserIdAndReadDateIsNull(recipientUserId);
    if (unread.isEmpty()) {
      return;
    }
    var now = LocalDateTime.now();
    unread.forEach(item -> item.setReadDate(now));
    eventNotificationRepository.saveAll(unread);
  }

  @Transactional
  public void clearFeed(String recipientUserId) {
    eventNotificationRepository.deleteByRecipientUserId(recipientUserId);
  }

  public void updateActiveView(String userId, String roomId, String threadRootId, boolean active) {
    if (userId == null || userId.isBlank()) {
      return;
    }
    if (!active) {
      activeViewByUserId.remove(userId);
      return;
    }
    if (roomId == null || roomId.isBlank()) {
      activeViewByUserId.remove(userId);
      return;
    }
    activeViewByUserId.put(
        userId, new ActiveViewState(roomId, threadRootId, monotonicNanos.getAsLong()));
  }

  void setMonotonicNanosForTesting(LongSupplier monotonicNanos) {
    this.monotonicNanos = monotonicNanos;
  }

  @Transactional
  public void createEvent(
      String recipientUserId,
      String eventType,
      String category,
      String title,
      String text,
      String actionPath,
      Long sourceSessionId,
      Long tenantId) {
    createEvent(
        recipientUserId,
        eventType,
        category,
        title,
        text,
        null,
        actionPath,
        sourceSessionId,
        tenantId);
  }

  /**
   * #942: message-type events carry a deterministic deduplication key derived from the Matrix event
   * id, so the Matrix sync listener and the frontend's {@code POST /message-events} for the same
   * message collapse into one row per recipient. Without an event id (legacy callers) the event
   * persists unconditionally, as before.
   */
  private void createMessageEventDeduplicated(
      String eventType,
      String matrixEventId,
      String recipientUserId,
      String title,
      String text,
      String params,
      String actionPath,
      Long sourceSessionId,
      Long tenantId) {
    if (matrixEventId != null && !matrixEventId.isBlank()) {
      String deduplicationKey = eventType + ":" + matrixEventId;
      if (deduplicationKey.length() <= MAX_DEDUPLICATION_KEY_LENGTH) {
        createEventOnce(
            deduplicationKey,
            recipientUserId,
            eventType,
            CATEGORY_MESSAGE,
            title,
            text,
            params,
            actionPath,
            sourceSessionId,
            tenantId);
        return;
      }
      // An oversized key would fail the insert with a truncation error that the
      // dedup path misreads as "already persisted" — persist unconditionally instead.
      log.warn(
          "Deduplication key for event type {} exceeds {} chars; persisting without deduplication",
          eventType,
          MAX_DEDUPLICATION_KEY_LENGTH);
    }
    createEvent(
        recipientUserId,
        eventType,
        CATEGORY_MESSAGE,
        title,
        text,
        params,
        actionPath,
        sourceSessionId,
        tenantId);
  }

  /**
   * Low-level builder. WP-06 / ADR-AT-01: {@code params} carries structured JSON the client renders
   * visible strings from; {@code title}/{@code text} remain a fallback until the frontend renders
   * purely from params.
   */
  @Transactional
  public void createEvent(
      String recipientUserId,
      String eventType,
      String category,
      String title,
      String text,
      String params,
      String actionPath,
      Long sourceSessionId,
      Long tenantId) {
    if (recipientUserId == null || recipientUserId.isBlank()) {
      return;
    }
    eventNotificationRepository.save(
        buildEvent(
            recipientUserId,
            eventType,
            category,
            title,
            text,
            params,
            actionPath,
            sourceSessionId,
            tenantId,
            null));
  }

  /** Persists an event at most once for a producer-owned key and recipient. */
  @Transactional
  public void createEventOnce(
      String deduplicationKey,
      String recipientUserId,
      String eventType,
      String category,
      String title,
      String text,
      String params,
      String actionPath,
      Long sourceSessionId,
      Long tenantId) {
    if (recipientUserId == null
        || recipientUserId.isBlank()
        || deduplicationKey == null
        || deduplicationKey.isBlank()
        || eventNotificationRepository.existsByRecipientUserIdAndDeduplicationKey(
            recipientUserId, deduplicationKey)) {
      return;
    }

    try {
      deduplicationWriter.persistInNewTransaction(
          buildEvent(
              recipientUserId,
              eventType,
              category,
              title,
              text,
              params,
              actionPath,
              sourceSessionId,
              tenantId,
              deduplicationKey));
    } catch (DataIntegrityViolationException duplicate) {
      // Another scheduler replica won the unique-key race. The desired event already exists.
      log.debug(
          "Notification {} already persisted for recipient {}", deduplicationKey, recipientUserId);
    }
  }

  private EventNotification buildEvent(
      String recipientUserId,
      String eventType,
      String category,
      String title,
      String text,
      String params,
      String actionPath,
      Long sourceSessionId,
      Long tenantId,
      String deduplicationKey) {
    return EventNotification.builder()
        .recipientUserId(recipientUserId)
        .eventType(eventType)
        .category(nonNull(category) ? category : CATEGORY_SYSTEM)
        .title(nonNull(title) ? title : "Notification")
        .text(nonNull(text) ? text : "")
        .params(params)
        .actionPath(actionPath)
        .sourceSessionId(sourceSessionId)
        .deduplicationKey(deduplicationKey)
        .readDate(null)
        .createDate(LocalDateTime.now())
        .tenantId(tenantId)
        .build();
  }

  private NotificationItem toItem(EventNotification item) {
    return NotificationItem.builder()
        .id(item.getId())
        .eventType(item.getEventType())
        .category(item.getCategory())
        .title(item.getTitle())
        .text(item.getText())
        .params(item.getParams())
        .actionPath(item.getActionPath())
        .sourceSessionId(item.getSourceSessionId())
        .readAt(
            item.getReadDate() != null
                ? item.getReadDate().atOffset(ZoneOffset.UTC).toString()
                : null)
        .createdAt(
            item.getCreateDate() != null
                ? item.getCreateDate().atOffset(ZoneOffset.UTC).toString()
                : null)
        .build();
  }

  private String resolveSenderName(String senderUserId) {
    return resolveSenderName(senderUserId, null);
  }

  private String resolveSenderName(String senderUserId, String senderDisplayName) {
    if (senderDisplayName != null
        && !senderDisplayName.isBlank()
        && !looksEncoded(senderDisplayName)) {
      return senderDisplayName;
    }
    if (senderUserId == null || senderUserId.isBlank()) {
      return "Someone";
    }
    return consultantRepository
        .findByIdAndDeleteDateIsNull(senderUserId)
        .map(this::resolveConsultantName)
        .or(
            () ->
                userRepository
                    .findByUserIdAndDeleteDateIsNull(senderUserId)
                    .map(
                        user -> {
                          String candidate = user.getUsername();
                          if (looksEncoded(candidate)) {
                            return "User";
                          }
                          return safeValue(candidate, "User");
                        }))
        .or(() -> identityTombstoneService.resolveDisplayLabel(senderUserId))
        .orElse("Someone");
  }

  private String resolveConsultantName(Consultant consultant) {
    if (consultant == null) {
      return "Counselor";
    }
    if (consultant.getDisplayName() != null
        && !consultant.getDisplayName().isBlank()
        && !looksEncoded(consultant.getDisplayName())) {
      return consultant.getDisplayName();
    }
    if (consultant.getFullName() != null
        && !consultant.getFullName().isBlank()
        && !looksEncoded(consultant.getFullName())) {
      return consultant.getFullName();
    }
    if (consultant.getUsername() != null
        && !consultant.getUsername().isBlank()
        && !looksEncoded(consultant.getUsername())) {
      return consultant.getUsername();
    }
    return "Counselor";
  }

  private String normalizePreview(String messagePreview) {
    if (messagePreview == null) {
      return "";
    }
    String normalized = messagePreview.replaceAll("\\s+", " ").trim();
    return normalized.length() > 120 ? normalized.substring(0, 117) + "..." : normalized;
  }

  private String buildMessageNotificationText(
      String senderLabel, String messagePreview, PrivacyEnvelope envelope) {
    NotificationPreviewMode mode = currentPreviewMode();
    String contentLabel = contentLabel(envelope);
    if (mode == NotificationPreviewMode.NONE) {
      return String.format("%s sent a new %s.", senderLabel, contentLabel);
    }
    if (mode == NotificationPreviewMode.MASKED) {
      return String.format("%s sent a new %s: %s", senderLabel, contentLabel, REDACTED_PREVIEW);
    }
    String preview = normalizePreview(cleanMessageBody(messagePreview));
    if (!preview.isBlank()) {
      return String.format("%s sent a new message: \"%s\"", senderLabel, preview);
    }
    return String.format(
        "%s sent a new %s (messageId: %s).",
        senderLabel,
        contentLabel,
        envelope != null ? safeValue(envelope.getMessageId(), "n/a") : "n/a");
  }

  private String buildThreadReplyNotificationText(
      String senderLabel,
      String messagePreview,
      String threadParentPreview,
      PrivacyEnvelope envelope) {
    NotificationPreviewMode mode = currentPreviewMode();
    if (mode == NotificationPreviewMode.NONE) {
      return String.format("%s replied in a thread.", senderLabel);
    }
    if (mode == NotificationPreviewMode.MASKED) {
      return String.format("%s replied in a thread: %s", senderLabel, REDACTED_PREVIEW);
    }
    String preview = normalizePreview(cleanMessageBody(messagePreview));
    String parentPreview = normalizePreview(cleanMessageBody(threadParentPreview));
    if (!preview.isBlank() || !parentPreview.isBlank()) {
      return String.format(
          "%s replied under thread \"%s\"%s",
          senderLabel,
          parentPreview.isBlank() ? "message" : parentPreview,
          preview.isBlank() ? "." : ": \"" + preview + "\"");
    }
    return String.format(
        "%s replied in a thread (messageId: %s).",
        senderLabel, envelope != null ? safeValue(envelope.getMessageId(), "n/a") : "n/a");
  }

  private String contentLabel(PrivacyEnvelope envelope) {
    if (envelope == null || envelope.getContentClass() == null) {
      return "message";
    }
    switch (envelope.getContentClass()) {
      case "IMAGE":
        return "image";
      case "FILE":
        return "file";
      case "AUDIO":
        return "audio message";
      case "VIDEO":
        return "video message";
      case "NOTICE":
        return "notice";
      default:
        return "message";
    }
  }

  private NotificationPreviewMode currentPreviewMode() {
    try {
      return NotificationPreviewMode.valueOf(
          safeValue(notificationPreviewMode, "NONE").toUpperCase());
    } catch (IllegalArgumentException ex) {
      return NotificationPreviewMode.NONE;
    }
  }

  private String cleanMessageBody(String messagePreview) {
    if (messagePreview == null) {
      return "";
    }
    String cleaned = messagePreview;
    cleaned = cleaned.replaceFirst("^\\Q" + SYSTEM_NOTIFICATION_PREFIX + "\\E\\s*", "");
    cleaned = cleaned.replaceFirst("^\\[THREAD:[^\\]]+\\]\\s*", "");
    cleaned = cleaned.replaceFirst("^\\[SUPERVISOR_FEEDBACK\\]\\s*", "");
    return cleaned;
  }

  private boolean isSystemNotificationMessage(String messagePreview) {
    return messagePreview != null && messagePreview.startsWith(SYSTEM_NOTIFICATION_PREFIX);
  }

  private boolean looksEncoded(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    return value.startsWith("enc.") || value.matches("^[A-Za-z0-9+/=]{25,}$");
  }

  /**
   * Public room-level probe for sibling producers (US#473 team-discussion fan-out): true when the
   * recipient is actively viewing the room and the notification should be dropped.
   */
  public boolean isNotificationSuppressed(String recipientUserId, String roomId) {
    return shouldSuppressNotification(recipientUserId, roomId, null);
  }

  private boolean shouldSuppressNotification(
      String recipientUserId, String roomId, String threadRootId) {
    while (true) {
      ActiveViewState activeView = activeViewByUserId.get(recipientUserId);
      if (activeView == null || roomId == null || roomId.isBlank()) {
        return false;
      }
      if (monotonicNanos.getAsLong() - activeView.lastHeartbeatNanos >= ACTIVE_VIEW_TTL_NANOS) {
        if (activeViewByUserId.remove(recipientUserId, activeView)) {
          return false;
        }
        // A concurrent heartbeat replaced the expired entry. Re-evaluate that
        // fresh state so an active recipient remains correctly suppressed.
        continue;
      }
      if (!roomId.equals(activeView.roomId)) {
        return false;
      }

      // For room-level messages, suppress when recipient is on that room.
      if (threadRootId == null || threadRootId.isBlank()) {
        return true;
      }

      // For thread replies, suppress only when recipient is actively inside same thread.
      return threadRootId.equals(activeView.threadRootId);
    }
  }

  private String buildSessionActionPath(Session session) {
    return buildSessionActionPath(session, null);
  }

  /**
   * Deep link for enquiry-state events ({@code request.new}, {@code waiting_room.client.joined},
   * #846): un-accepted enquiries render under the consultant's sessionPreview list, not
   * sessionView. A waiting-room client has no Matrix room yet — link to the enquiry list itself
   * instead of returning null (which the frontend turned into the bare sessions root).
   */
  private String buildEnquiryActionPath(Session session) {
    String listPath = "/sessions/consultant/sessionPreview";
    if (session == null || session.getId() == null) {
      return listPath;
    }
    String roomRef =
        session.getMatrixRoomId() != null && !session.getMatrixRoomId().isBlank()
            ? session.getMatrixRoomId()
            : null;
    if (roomRef == null) {
      return listPath;
    }
    return listPath + "/" + roomRef + "/" + session.getId();
  }

  private String buildSessionActionPathForRecipient(
      Session session, String recipientUserId, String threadRootId) {
    if (session == null || session.getId() == null) {
      return null;
    }
    boolean isConsultantRecipient =
        session.getConsultant() != null
            && session.getConsultant().getId() != null
            && session.getConsultant().getId().equals(recipientUserId);
    return buildSessionActionPath(session, threadRootId, isConsultantRecipient);
  }

  private String buildSessionActionPath(Session session, String threadRootId) {
    return buildSessionActionPath(session, threadRootId, false);
  }

  private String buildSessionActionPath(
      Session session, String threadRootId, boolean consultantPath) {
    if (session == null || session.getId() == null) {
      return null;
    }
    String roomRef = null;
    if (session.getMatrixRoomId() != null && !session.getMatrixRoomId().isBlank()) {
      roomRef = session.getMatrixRoomId();
    }
    if (roomRef == null) {
      return null;
    }
    String pathPrefix =
        consultantPath ? "/sessions/consultant/sessionView/" : "/sessions/user/view/";
    String path = pathPrefix + roomRef + "/" + session.getId();
    if (threadRootId != null && !threadRootId.isBlank()) {
      return path
          + "?threadRootId="
          + java.net.URLEncoder.encode(threadRootId, java.nio.charset.StandardCharsets.UTF_8);
    }
    return path;
  }

  private String safeValue(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  @Getter
  @Builder
  public static class NotificationFeedResponse {
    private final List<NotificationItem> items;
    private final long unreadCount;
    private final int page;
    private final int perPage;
  }

  @Getter
  @Builder
  public static class NotificationItem {
    private final Long id;
    private final String eventType;
    private final String category;
    private final String title;
    private final String text;

    /** WP-06 / ADR-AT-01: structured JSON params for client-side string rendering. */
    private final String params;

    private final String actionPath;
    private final Long sourceSessionId;
    private final String createdAt;
    private final String readAt;
  }

  private static class ActiveViewState {
    private final String roomId;
    private final String threadRootId;
    private final long lastHeartbeatNanos;

    private ActiveViewState(String roomId, String threadRootId, long lastHeartbeatNanos) {
      this.roomId = roomId;
      this.threadRootId = threadRootId;
      this.lastHeartbeatNanos = lastHeartbeatNanos;
    }
  }

  private enum NotificationPreviewMode {
    NONE,
    MASKED,
    FULL
  }
}
