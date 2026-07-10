package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.service.notification.GroupChatLifecycleNotificationService.EVENT_GROUP_CHAT_CANCELLED;
import static de.caritas.cob.userservice.api.service.notification.GroupChatLifecycleNotificationService.EVENT_GROUP_CHAT_OPENED;
import static de.caritas.cob.userservice.api.service.notification.GroupChatLifecycleNotificationService.EVENT_GROUP_CHAT_REMINDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupChatLifecycleNotificationServiceTest {

  @Mock private EventNotificationService eventNotificationService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private GroupChatLifecycleNotificationService service;

  @BeforeEach
  void setUp() {
    service = new GroupChatLifecycleNotificationService(eventNotificationService, objectMapper);
  }

  @Test
  void opened_fansOutDistinctRecipientsWithMetadataOnlyParams() throws Exception {
    LocalDateTime occurrenceStart = LocalDateTime.of(2026, 7, 10, 18, 30);

    service.createOpenedNotifications(
        42L,
        3,
        occurrenceStart,
        "!room:matrix.example",
        "!call:matrix.example",
        true,
        Arrays.asList("user-a", "user-a", " ", null, "user-b"));

    ArgumentCaptor<String> paramsCaptor = ArgumentCaptor.forClass(String.class);
    verify(eventNotificationService)
        .createEventOnce(
            eq("group-chat:group_chat.opened:42:3"),
            eq("user-a"),
            eq(EVENT_GROUP_CHAT_OPENED),
            eq(EventNotificationService.CATEGORY_SYSTEM),
            eq("Group chat available"),
            eq("A group chat is now available."),
            paramsCaptor.capture(),
            isNull(),
            eq(42L),
            isNull());
    verify(eventNotificationService)
        .createEventOnce(
            eq("group-chat:group_chat.opened:42:3"),
            eq("user-b"),
            eq(EVENT_GROUP_CHAT_OPENED),
            eq(EventNotificationService.CATEGORY_SYSTEM),
            eq("Group chat available"),
            eq("A group chat is now available."),
            anyString(),
            isNull(),
            eq(42L),
            isNull());

    JsonNode params = objectMapper.readTree(paramsCaptor.getValue());
    assertThat(params.get("seriesId").asLong()).isEqualTo(42L);
    assertThat(params.get("occurrenceIndex").asInt()).isEqualTo(3);
    assertThat(params.get("start").asText()).isEqualTo(occurrenceStart.toString());
    assertThat(params.get("occurrenceStart").asText()).isEqualTo(occurrenceStart.toString());
    assertThat(params.get("roomRef").asText()).isEqualTo("!room:matrix.example");
    assertThat(params.get("callRoomId").asText()).isEqualTo("!call:matrix.example");
    assertThat(params.get("isVideo").asBoolean()).isTrue();
    assertThat(paramsCaptor.getValue()).doesNotContain("topic", "agency", "participant", "message");
  }

  @Test
  void reminderAndCancelled_useCanonicalTypesAndNeutralFallbackText() {
    LocalDateTime occurrenceStart = LocalDateTime.of(2026, 7, 11, 9, 0);

    service.createReminderNotifications(
        7L, 0, occurrenceStart, null, null, false, List.of("user-a"));
    service.createCancelledNotifications(
        7L, 0, occurrenceStart, null, null, false, List.of("user-a"));

    verify(eventNotificationService)
        .createEventOnce(
            eq("group-chat:group_chat.reminder:7:0"),
            eq("user-a"),
            eq(EVENT_GROUP_CHAT_REMINDER),
            eq(EventNotificationService.CATEGORY_SYSTEM),
            eq("Group chat reminder"),
            eq("A group chat is scheduled soon."),
            anyString(),
            isNull(),
            eq(7L),
            isNull());
    verify(eventNotificationService)
        .createEventOnce(
            eq("group-chat:group_chat.cancelled:7:0"),
            eq("user-a"),
            eq(EVENT_GROUP_CHAT_CANCELLED),
            eq(EventNotificationService.CATEGORY_SYSTEM),
            eq("Group chat cancelled"),
            eq("A group chat occurrence was cancelled."),
            anyString(),
            isNull(),
            eq(7L),
            isNull());
  }

  @Test
  void invalidSeriesOrRecipients_doesNothing() {
    service.createOpenedNotifications(
        null, 0, LocalDateTime.now(), "!room:matrix", null, null, List.of("user-a"));
    service.createReminderNotifications(
        1L, 0, LocalDateTime.now(), "!room:matrix", null, null, null);

    verify(eventNotificationService, never())
        .createEventOnce(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void fanoutContinuesWhenOnePersistenceCallFails() {
    doThrow(new RuntimeException("DB error"))
        .doNothing()
        .when(eventNotificationService)
        .createEventOnce(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

    service.createCancelledNotifications(
        9L, 1, LocalDateTime.now(), null, null, null, List.of("user-a", "user-b"));

    verify(eventNotificationService, times(2))
        .createEventOnce(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }
}
