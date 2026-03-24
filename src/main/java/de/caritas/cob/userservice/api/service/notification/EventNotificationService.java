package de.caritas.cob.userservice.api.service.notification;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.EventNotification;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventNotificationService {

  public static final String CATEGORY_SYSTEM = "system";
  public static final String CATEGORY_MESSAGE = "message";
  private static final String SYSTEM_NOTIFICATION_PREFIX = "[SYSTEM_NOTIFICATION]";

  private final @NonNull EventNotificationRepository eventNotificationRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull UserRepository userRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final Map<String, ActiveViewState> activeViewByUserId = new ConcurrentHashMap<>();

  @Transactional
  public void createInquiryAcceptedNotification(Session session, Consultant consultant) {
    if (session == null || session.getUser() == null || session.getUser().getUserId() == null) {
      return;
    }

    String consultantName = resolveConsultantName(consultant);
    createEvent(
        session.getUser().getUserId(),
        "inquiry.accepted",
        CATEGORY_SYSTEM,
        "Inquiry accepted",
        String.format(
            "Your request was accepted by %s. Chat is now active.", consultantName),
        buildSessionActionPath(session),
        session.getId(),
        session.getTenantId());
  }

  @Transactional
  public void createSupervisorAddedNotification(
      Session session, String recipientUserId, String supervisorName) {
    createEvent(
        recipientUserId,
        "supervisor.added",
        CATEGORY_SYSTEM,
        "Consultant added to your chat",
        String.format(
            "%s was added as a consultant supervisor to your chat #%s.",
            safeValue(supervisorName, "A supervisor"), session.getId()),
        buildSessionActionPath(session),
        session.getId(),
        session.getTenantId());
  }

  @Transactional
  public void createSupervisorRemovedNotification(
      Session session, String recipientUserId, String supervisorName) {
    createEvent(
        recipientUserId,
        "supervisor.removed",
        CATEGORY_SYSTEM,
        "Supervisor removed",
        String.format(
            "%s was removed from supervision for chat #%s.",
            safeValue(supervisorName, "A supervisor"), session.getId()),
        buildSessionActionPath(session),
        session.getId(),
        session.getTenantId());
  }

  @Transactional
  public void createMessageNotificationFromRoom(
      String roomId, String senderUserId, String messagePreview, boolean matrixRoom) {
    createMessageNotificationFromRoom(
        roomId, senderUserId, messagePreview, matrixRoom, false, null);
  }

  @Transactional
  public void createMessageNotificationFromRoom(
      String roomId,
      String senderUserId,
      String messagePreview,
      boolean matrixRoom,
      boolean supervisorMessage,
      String senderDisplayName) {
    if (roomId == null || roomId.isBlank()) {
      return;
    }

    Optional<Session> sessionOpt =
        matrixRoom ? sessionRepository.findByMatrixRoomId(roomId) : sessionRepository.findByGroupId(roomId);
    if (sessionOpt.isEmpty()) {
      return;
    }
    if (isSystemNotificationMessage(messagePreview)) {
      return;
    }

    Session session = sessionOpt.get();
    String senderLabel = resolveSenderName(senderUserId, senderDisplayName);
    String preview = normalizePreview(cleanMessageBody(messagePreview));
    String text =
        String.format(
            "%s sent a new message%s",
            senderLabel, preview.isBlank() ? "." : ": \"" + preview + "\"");

    if (!supervisorMessage
        && session.getUser() != null
        && session.getUser().getUserId() != null
        && !session.getUser().getUserId().equals(senderUserId)
        && !shouldSuppressNotification(session.getUser().getUserId(), roomId, null)) {
      createEvent(
          session.getUser().getUserId(),
          "message.new",
          CATEGORY_MESSAGE,
          "New message",
          text,
          buildSessionActionPathForRecipient(session, session.getUser().getUserId(), null),
          session.getId(),
          session.getTenantId());
    }

    if (session.getConsultant() != null
        && session.getConsultant().getId() != null
        && !session.getConsultant().getId().equals(senderUserId)
        && !shouldSuppressNotification(session.getConsultant().getId(), roomId, null)) {
      createEvent(
          session.getConsultant().getId(),
          "message.new",
          CATEGORY_MESSAGE,
          "New message",
          text,
          buildSessionActionPathForRecipient(session, session.getConsultant().getId(), null),
          session.getId(),
          session.getTenantId());
    }
  }

  @Transactional
  public void createThreadReplyNotificationFromRoom(
      String roomId,
      String senderUserId,
      String messagePreview,
      String threadRootId,
      boolean matrixRoom) {
    createThreadReplyNotificationFromRoom(
        roomId, senderUserId, messagePreview, threadRootId, matrixRoom, false, null, null);
  }

  @Transactional
  public void createThreadReplyNotificationFromRoom(
      String roomId,
      String senderUserId,
      String messagePreview,
      String threadRootId,
      boolean matrixRoom,
      boolean supervisorMessage,
      String senderDisplayName,
      String threadParentPreview) {
    if (roomId == null || roomId.isBlank()) {
      return;
    }

    Optional<Session> sessionOpt =
        matrixRoom ? sessionRepository.findByMatrixRoomId(roomId) : sessionRepository.findByGroupId(roomId);
    if (sessionOpt.isEmpty()) {
      return;
    }
    if (isSystemNotificationMessage(messagePreview)) {
      return;
    }

    Session session = sessionOpt.get();
    String senderLabel = resolveSenderName(senderUserId, senderDisplayName);
    String preview = normalizePreview(cleanMessageBody(messagePreview));
    String parentPreview = normalizePreview(cleanMessageBody(threadParentPreview));
    String text =
        String.format(
            "%s replied under thread \"%s\"%s",
            senderLabel,
            parentPreview.isBlank() ? "message" : parentPreview,
            preview.isBlank() ? "." : ": \"" + preview + "\"");

    if (!supervisorMessage
        && session.getUser() != null
        && session.getUser().getUserId() != null
        && !session.getUser().getUserId().equals(senderUserId)
        && !shouldSuppressNotification(session.getUser().getUserId(), roomId, threadRootId)) {
      createEvent(
          session.getUser().getUserId(),
          "thread.reply.new",
          CATEGORY_MESSAGE,
          "New thread reply",
          text,
          buildSessionActionPathForRecipient(
              session, session.getUser().getUserId(), threadRootId),
          session.getId(),
          session.getTenantId());
    }

    if (session.getConsultant() != null
        && session.getConsultant().getId() != null
        && !session.getConsultant().getId().equals(senderUserId)
        && !shouldSuppressNotification(session.getConsultant().getId(), roomId, threadRootId)) {
      createEvent(
          session.getConsultant().getId(),
          "thread.reply.new",
          CATEGORY_MESSAGE,
          "New thread reply",
          text,
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
        eventNotificationRepository.findByRecipientUserIdOrderByCreateDateDesc(recipientUserId, pageable).stream()
            .map(this::toItem)
            .collect(Collectors.toList());

    long unreadCount = eventNotificationRepository.countByRecipientUserIdAndReadDateIsNull(recipientUserId);
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
    var unread = eventNotificationRepository.findByRecipientUserIdAndReadDateIsNull(recipientUserId);
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

  public void updateActiveView(
      String userId, String roomId, String threadRootId, boolean active) {
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
    activeViewByUserId.put(userId, new ActiveViewState(roomId, threadRootId));
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
    if (recipientUserId == null || recipientUserId.isBlank()) {
      return;
    }
    var event =
        EventNotification.builder()
            .recipientUserId(recipientUserId)
            .eventType(eventType)
            .category(nonNull(category) ? category : CATEGORY_SYSTEM)
            .title(nonNull(title) ? title : "Notification")
            .text(nonNull(text) ? text : "")
            .actionPath(actionPath)
            .sourceSessionId(sourceSessionId)
            .readDate(null)
            .createDate(LocalDateTime.now())
            .tenantId(tenantId)
            .build();
    eventNotificationRepository.save(event);
  }

  private NotificationItem toItem(EventNotification item) {
    return NotificationItem.builder()
        .id(item.getId())
        .eventType(item.getEventType())
        .category(item.getCategory())
        .title(item.getTitle())
        .text(item.getText())
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

  private boolean shouldSuppressNotification(
      String recipientUserId, String roomId, String threadRootId) {
    ActiveViewState activeView = activeViewByUserId.get(recipientUserId);
    if (activeView == null || roomId == null || roomId.isBlank()) {
      return false;
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

  private String buildSessionActionPath(Session session) {
    return buildSessionActionPath(session, null);
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
    } else if (session.getGroupId() != null && !session.getGroupId().isBlank()) {
      roomRef = session.getGroupId();
    }
    if (roomRef == null) {
      return null;
    }
    String pathPrefix =
        consultantPath ? "/sessions/consultant/sessionView/" : "/sessions/user/view/";
    String path = pathPrefix + roomRef + "/" + session.getId();
    if (threadRootId != null && !threadRootId.isBlank()) {
      return path + "?threadRootId=" + java.net.URLEncoder.encode(threadRootId, java.nio.charset.StandardCharsets.UTF_8);
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
    private final String actionPath;
    private final Long sourceSessionId;
    private final String createdAt;
    private final String readAt;
  }

  private static class ActiveViewState {
    private final String roomId;
    private final String threadRootId;

    private ActiveViewState(String roomId, String threadRootId) {
      this.roomId = roomId;
      this.threadRootId = threadRootId;
    }
  }
}

