package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.NotificationRoomLevel;
import de.caritas.cob.userservice.api.service.matrix.RedisMessageMirrorService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.notification.PrivacyEnvelope;
import de.caritas.cob.userservice.api.service.notification.TeamDiscussionNotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/event-notifications")
@RequiredArgsConstructor
public class EventNotificationController {

  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull TeamDiscussionNotificationService teamDiscussionNotificationService;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final Optional<RedisMessageMirrorService> redisMessageMirrorService;

  /** Upper bound of the {@code excludeEventTypes}/{@code eventTypes} lists (#1377 slice 7). */
  static final int MAX_EVENT_TYPES = 100;

  /** Matches the {@code event_type} column (VARCHAR(100)). */
  static final int MAX_EVENT_TYPE_LENGTH = 100;

  @GetMapping
  public ResponseEntity<EventNotificationService.NotificationFeedResponse> getFeed(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) int perPage,
      @RequestParam(required = false) String excludeEventTypes) {
    Optional<Set<String>> excluded = parseEventTypes(excludeEventTypes);
    if (excluded.isEmpty()) {
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }
    return ResponseEntity.ok(
        eventNotificationService.getFeed(
            authenticatedUser.getUserId(), page, perPage, excluded.get()));
  }

  /**
   * #1377 slice 7: the unread total without the given event types, so a client that hides some
   * kinds shows an exact badge instead of an upper bound.
   */
  @GetMapping("/unread-count")
  public ResponseEntity<EventNotificationService.UnreadCountResponse> getUnreadCount(
      @RequestParam(required = false) String excludeEventTypes) {
    Optional<Set<String>> excluded = parseEventTypes(excludeEventTypes);
    if (excluded.isEmpty()) {
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }
    long unreadCount =
        eventNotificationService.countUnread(authenticatedUser.getUserId(), excluded.get());
    return ResponseEntity.ok(
        EventNotificationService.UnreadCountResponse.builder()
            .unreadCount(unreadCount)
            .excludedEventTypes(List.copyOf(new java.util.TreeSet<>(excluded.get())))
            .build());
  }

  /**
   * #1377 slice 7: marks every unread notification of the given event types read, on unloaded pages
   * too ("hidden ⇒ read"). An empty list is a bad request, not a silent read-all.
   */
  @PatchMapping("/read")
  public ResponseEntity<EventNotificationService.MarkReadResponse> markAsReadByEventTypes(
      @RequestParam(required = false) String eventTypes) {
    Optional<Set<String>> types = parseEventTypes(eventTypes);
    if (types.isEmpty() || types.get().isEmpty()) {
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }
    int updated =
        eventNotificationService.markAsReadByEventTypes(authenticatedUser.getUserId(), types.get());
    return ResponseEntity.ok(
        EventNotificationService.MarkReadResponse.builder()
            .updated(updated)
            .eventTypes(List.copyOf(new java.util.TreeSet<>(types.get())))
            .build());
  }

  /**
   * Comma-separated event types → set. Empty result for an absent/blank parameter; {@link
   * Optional#empty()} when the list is too long or an entry is longer than the column allows.
   */
  static Optional<Set<String>> parseEventTypes(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.of(Set.of());
    }
    Set<String> types = new java.util.LinkedHashSet<>();
    for (String part : raw.split(",")) {
      String type = part.trim();
      if (type.isEmpty()) {
        continue;
      }
      if (type.length() > MAX_EVENT_TYPE_LENGTH) {
        return Optional.empty();
      }
      types.add(type);
      if (types.size() > MAX_EVENT_TYPES) {
        return Optional.empty();
      }
    }
    return Optional.of(types);
  }

  @PatchMapping("/{notificationId}/read")
  public ResponseEntity<Void> markAsRead(@PathVariable Long notificationId) {
    eventNotificationService.markAsRead(authenticatedUser.getUserId(), notificationId);
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @PatchMapping("/read-all")
  public ResponseEntity<Void> markAllAsRead() {
    eventNotificationService.markAllAsRead(authenticatedUser.getUserId());
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @DeleteMapping
  public ResponseEntity<Void> clear() {
    eventNotificationService.clearFeed(authenticatedUser.getUserId());
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @PatchMapping("/active-view")
  public ResponseEntity<Void> updateActiveView(@RequestBody ActiveViewRequestDTO request) {
    boolean active = request == null || request.getActive() == null || request.getActive();
    String roomId = request != null ? request.getRoomId() : null;
    String threadRootId = request != null ? request.getThreadRootId() : null;
    eventNotificationService.updateActiveView(
        authenticatedUser.getUserId(), roomId, threadRootId, active);
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @PostMapping("/message-events")
  public ResponseEntity<Void> createMessageEventNotification(
      @Valid @RequestBody MessageEventRequestDTO request) {
    if (request == null || request.getRoomId() == null || request.getRoomId().isBlank()) {
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }

    if (request.getTeamDiscussion() != null && request.getTeamDiscussion()) {
      // US#473: team-discussion rooms have no session recipient pair — dedicated hybrid fan-out.
      teamDiscussionNotificationService.createTeamDiscussionNotification(
          request.getRoomId(),
          authenticatedUser.getUserId(),
          request.getSenderDisplayName(),
          request.getMentionedUserIds());
      return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    // #942: the Matrix event id (when the client sends it) keys deduplication,
    // so this producer and the server-side Matrix listener collapse into one
    // row per recipient for the same message.
    PrivacyEnvelope envelope =
        request.getMatrixEventId() != null && !request.getMatrixEventId().isBlank()
            ? PrivacyEnvelope.builder()
                .messageId(request.getMatrixEventId())
                .roomId(request.getRoomId())
                .senderId(authenticatedUser.getUserId())
                // Mirror MatrixEventListenerService#buildPrivacyEnvelope so the
                // persisted row keeps the correct content class no matter which
                // producer wins the dedup race.
                .contentClass(normaliseContentClass(request.getContentClass()))
                .hasAttachment(request.getHasAttachment() != null && request.getHasAttachment())
                .build()
            : null;
    if (request.getThreadRootId() != null && !request.getThreadRootId().isBlank()) {
      eventNotificationService.createThreadReplyNotificationFromRoom(
          request.getRoomId(),
          authenticatedUser.getUserId(),
          request.getMessagePreview(),
          request.getThreadRootId(),
          request.getSupervisorMessage() != null && request.getSupervisorMessage(),
          request.getThreadParentPreview(),
          envelope);
    } else {
      eventNotificationService.createMessageNotificationFromRoom(
          request.getRoomId(),
          authenticatedUser.getUserId(),
          request.getMessagePreview(),
          request.getSupervisorMessage() != null && request.getSupervisorMessage(),
          envelope);
    }

    // Debug-only mirror to Redis for Redis Commander verification of outgoing preview flow.
    redisMessageMirrorService.ifPresent(
        mirror ->
            mirror.mirrorOutgoingMessage(
                null,
                request.getRoomId(),
                authenticatedUser.getUsername(),
                authenticatedUser.getRoles() != null
                    && authenticatedUser.getRoles().contains("consultant"),
                request.getMessagePreview(),
                null));

    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  /** Vocabulary of {@code MatrixEventListenerService#classifyContent}. */
  private static final Set<String> KNOWN_CONTENT_CLASSES =
      Set.of("TEXT", "IMAGE", "FILE", "AUDIO", "VIDEO", "NOTICE", "EMOTE", "OTHER", "UNKNOWN");

  /**
   * Keep the client-supplied content class inside the classification vocabulary of {@code
   * MatrixEventListenerService#classifyContent} — it feeds notification fallback text and the
   * structured params rendered for other users.
   */
  private static String normaliseContentClass(String contentClass) {
    if (contentClass == null || contentClass.isBlank()) {
      return null;
    }
    String normalised = contentClass.trim().toUpperCase(Locale.ROOT);
    return KNOWN_CONTENT_CLASSES.contains(normalised) ? normalised : "OTHER";
  }

  /**
   * US#473: mirrors the user's per-conversation notification level (All / Mentions / Muted /
   * Snoozed) so the server-side fan-out can honour it.
   */
  @PatchMapping("/conversation-level")
  public ResponseEntity<Void> updateConversationLevel(
      @RequestBody ConversationLevelRequestDTO request) {
    if (request == null || request.getRoomId() == null || request.getRoomId().isBlank()) {
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }
    NotificationRoomLevel.Level level;
    try {
      level =
          request.getLevel() != null
              ? NotificationRoomLevel.Level.valueOf(request.getLevel().toUpperCase())
              : NotificationRoomLevel.Level.ALL;
    } catch (IllegalArgumentException ex) {
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }
    teamDiscussionNotificationService.updateConversationLevel(
        authenticatedUser.getUserId(), request.getRoomId(), level, request.getSnoozedUntil());
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  public static class ConversationLevelRequestDTO {
    @NotBlank private String roomId;
    private String level;
    private java.time.LocalDateTime snoozedUntil;

    public String getRoomId() {
      return roomId;
    }

    public void setRoomId(String roomId) {
      this.roomId = roomId;
    }

    public String getLevel() {
      return level;
    }

    public void setLevel(String level) {
      this.level = level;
    }

    public java.time.LocalDateTime getSnoozedUntil() {
      return snoozedUntil;
    }

    public void setSnoozedUntil(java.time.LocalDateTime snoozedUntil) {
      this.snoozedUntil = snoozedUntil;
    }
  }

  public static class MessageEventRequestDTO {
    @NotBlank private String roomId;
    private String messagePreview;
    private String threadRootId;
    private Boolean supervisorMessage;

    /**
     * ADR-002 §2 / #1201: only the team-discussion branch still reads this, a
     * consultant-to-consultant surface. The session paths ignore it — a client does not get to
     * decide what a third party is told the sender is called, and the app sends the real name here
     * when the counsellor has no pseudonym. Kept on the wire so existing clients keep working.
     */
    private String senderDisplayName;

    private String threadParentPreview;
    private Boolean teamDiscussion;
    private java.util.List<String> mentionedUserIds;

    /**
     * Matrix event id of the message this event mirrors (#942, dedup key). Matrix event ids are at
     * most 255 chars; the bound keeps oversized values away from the 191-char dedup column.
     */
    @Size(max = 255)
    private String matrixEventId;

    /**
     * Optional content class of the mirrored message, from the client's {@code msgtype} — mirrors
     * {@code MatrixEventListenerService#classifyContent} (TEXT, IMAGE, FILE, AUDIO, VIDEO, ...).
     */
    private String contentClass;

    /** Optional: whether the mirrored message carries an attachment. */
    private Boolean hasAttachment;

    public Boolean getTeamDiscussion() {
      return teamDiscussion;
    }

    public String getMatrixEventId() {
      return matrixEventId;
    }

    public void setMatrixEventId(String matrixEventId) {
      this.matrixEventId = matrixEventId;
    }

    public String getContentClass() {
      return contentClass;
    }

    public void setContentClass(String contentClass) {
      this.contentClass = contentClass;
    }

    public Boolean getHasAttachment() {
      return hasAttachment;
    }

    public void setHasAttachment(Boolean hasAttachment) {
      this.hasAttachment = hasAttachment;
    }

    public void setTeamDiscussion(Boolean teamDiscussion) {
      this.teamDiscussion = teamDiscussion;
    }

    public java.util.List<String> getMentionedUserIds() {
      return mentionedUserIds;
    }

    public void setMentionedUserIds(java.util.List<String> mentionedUserIds) {
      this.mentionedUserIds = mentionedUserIds;
    }

    public String getRoomId() {
      return roomId;
    }

    public void setRoomId(String roomId) {
      this.roomId = roomId;
    }

    public String getMessagePreview() {
      return messagePreview;
    }

    public void setMessagePreview(String messagePreview) {
      this.messagePreview = messagePreview;
    }

    public String getThreadRootId() {
      return threadRootId;
    }

    public void setThreadRootId(String threadRootId) {
      this.threadRootId = threadRootId;
    }

    public String getSenderDisplayName() {
      return senderDisplayName;
    }

    public void setSenderDisplayName(String senderDisplayName) {
      this.senderDisplayName = senderDisplayName;
    }

    public Boolean getSupervisorMessage() {
      return supervisorMessage;
    }

    public void setSupervisorMessage(Boolean supervisorMessage) {
      this.supervisorMessage = supervisorMessage;
    }

    public String getThreadParentPreview() {
      return threadParentPreview;
    }

    public void setThreadParentPreview(String threadParentPreview) {
      this.threadParentPreview = threadParentPreview;
    }
  }

  public static class ActiveViewRequestDTO {
    private String roomId;
    private String threadRootId;
    private Boolean active;

    public String getRoomId() {
      return roomId;
    }

    public void setRoomId(String roomId) {
      this.roomId = roomId;
    }

    public String getThreadRootId() {
      return threadRootId;
    }

    public void setThreadRootId(String threadRootId) {
      this.threadRootId = threadRootId;
    }

    public Boolean getActive() {
      return active;
    }

    public void setActive(Boolean active) {
      this.active = active;
    }
  }
}
