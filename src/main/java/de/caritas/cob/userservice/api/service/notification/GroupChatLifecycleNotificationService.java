package de.caritas.cob.userservice.api.service.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Publishes privacy-safe timeline events for self-help group occurrences. */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupChatLifecycleNotificationService {

  public static final String EVENT_GROUP_CHAT_OPENED = "group_chat.opened";
  public static final String EVENT_GROUP_CHAT_REMINDER = "group_chat.reminder";
  public static final String EVENT_GROUP_CHAT_CANCELLED = "group_chat.cancelled";

  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull ObjectMapper objectMapper;

  public void createOpenedNotifications(
      Long seriesId,
      Integer occurrenceIndex,
      LocalDateTime occurrenceStart,
      String roomRef,
      String callRoomId,
      Boolean isVideo,
      Collection<String> recipientUserIds) {
    createNotifications(
        LifecycleEvent.OPENED,
        seriesId,
        occurrenceIndex,
        occurrenceStart,
        roomRef,
        callRoomId,
        isVideo,
        recipientUserIds);
  }

  public void createReminderNotifications(
      Long seriesId,
      Integer occurrenceIndex,
      LocalDateTime occurrenceStart,
      String roomRef,
      String callRoomId,
      Boolean isVideo,
      Collection<String> recipientUserIds) {
    createNotifications(
        LifecycleEvent.REMINDER,
        seriesId,
        occurrenceIndex,
        occurrenceStart,
        roomRef,
        callRoomId,
        isVideo,
        recipientUserIds);
  }

  public void createCancelledNotifications(
      Long seriesId,
      Integer occurrenceIndex,
      LocalDateTime occurrenceStart,
      String roomRef,
      String callRoomId,
      Boolean isVideo,
      Collection<String> recipientUserIds) {
    createNotifications(
        LifecycleEvent.CANCELLED,
        seriesId,
        occurrenceIndex,
        occurrenceStart,
        roomRef,
        callRoomId,
        isVideo,
        recipientUserIds);
  }

  private void createNotifications(
      LifecycleEvent event,
      Long seriesId,
      Integer occurrenceIndex,
      LocalDateTime occurrenceStart,
      String roomRef,
      String callRoomId,
      Boolean isVideo,
      Collection<String> recipientUserIds) {
    if (seriesId == null || recipientUserIds == null || recipientUserIds.isEmpty()) {
      return;
    }

    String params =
        serializeParams(
            buildParams(seriesId, occurrenceIndex, occurrenceStart, roomRef, callRoomId, isVideo));
    String deduplicationKey =
        buildDeduplicationKey(event, seriesId, occurrenceIndex, occurrenceStart);
    recipientUserIds.stream()
        .filter(id -> id != null && !id.isBlank())
        .distinct()
        .forEach(
            recipientUserId ->
                createForRecipient(event, seriesId, params, deduplicationKey, recipientUserId));
  }

  private void createForRecipient(
      LifecycleEvent event,
      Long seriesId,
      String params,
      String deduplicationKey,
      String recipientUserId) {
    try {
      eventNotificationService.createEventOnce(
          deduplicationKey,
          recipientUserId,
          event.eventType,
          EventNotificationService.CATEGORY_SYSTEM,
          event.title,
          event.text,
          params,
          null,
          seriesId,
          null);
    } catch (RuntimeException ex) {
      // Each call crosses the EventNotificationService transaction boundary independently, so one
      // failed recipient cannot roll back notifications already written for other recipients.
      log.warn(
          "Could not persist {} notification for recipient {} (series {})",
          event.eventType,
          recipientUserId,
          seriesId,
          ex);
    }
  }

  private String buildDeduplicationKey(
      LifecycleEvent event, Long seriesId, Integer occurrenceIndex, LocalDateTime occurrenceStart) {
    String occurrenceKey =
        occurrenceIndex != null
            ? occurrenceIndex.toString()
            : occurrenceStart != null ? occurrenceStart.toString() : "series";
    return "group-chat:" + event.eventType + ":" + seriesId + ":" + occurrenceKey;
  }

  private Map<String, Object> buildParams(
      Long seriesId,
      Integer occurrenceIndex,
      LocalDateTime occurrenceStart,
      String roomRef,
      String callRoomId,
      Boolean isVideo) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("seriesId", seriesId);
    if (occurrenceIndex != null) {
      params.put("occurrenceIndex", occurrenceIndex);
    }
    if (occurrenceStart != null) {
      params.put("start", occurrenceStart.toString());
      params.put("occurrenceStart", occurrenceStart.toString());
    }
    putIfPresent(params, "roomRef", roomRef);
    putIfPresent(params, "callRoomId", callRoomId);
    if (isVideo != null) {
      params.put("isVideo", isVideo);
    }
    return params;
  }

  private void putIfPresent(Map<String, Object> params, String key, String value) {
    if (value != null && !value.isBlank()) {
      params.put(key, value);
    }
  }

  private String serializeParams(Map<String, Object> params) {
    try {
      return objectMapper.writeValueAsString(params);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Could not serialize group chat lifecycle parameters", ex);
    }
  }

  private enum LifecycleEvent {
    OPENED(EVENT_GROUP_CHAT_OPENED, "Group chat available", "A group chat is now available."),
    REMINDER(EVENT_GROUP_CHAT_REMINDER, "Group chat reminder", "A group chat is scheduled soon."),
    CANCELLED(
        EVENT_GROUP_CHAT_CANCELLED,
        "Group chat cancelled",
        "A group chat occurrence was cancelled.");

    private final String eventType;
    private final String title;
    private final String text;

    LifecycleEvent(String eventType, String title, String text) {
      this.eventType = eventType;
      this.title = title;
      this.text = text;
    }
  }
}
