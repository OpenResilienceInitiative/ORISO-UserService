package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixFeedUpdateSignalService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("testing")
@TestPropertySource(properties = "multitenancy.enabled=true")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class EventNotificationServiceReplicaIT {

  private static final String RECIPIENT = "replica-proof-recipient";
  private static final String DEDUPLICATION_KEY = "group-chat:group_chat.reminder:42:0";

  @Autowired private EventNotificationRepository eventNotificationRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private IdentityTombstoneService identityTombstoneService;
  @Autowired private EventNotificationDeduplicationWriter deduplicationWriter;
  @Autowired private MatrixFeedUpdateSignalService feedUpdateSignalService;
  @Autowired private EventNotificationService notifications;
  @Autowired private GroupChatReminderService reminders;
  @Autowired private GroupChatNotificationRecipientService recipients;
  @Autowired private GroupChatLifecycleNotificationService lifecycleNotifications;
  @Autowired private GroupChatParticipantRepository participants;
  @Autowired private ChatRepository chats;
  @Autowired private UserChatRepository userChats;
  @Autowired private AccountInactivityService accountLifecycle;
  @Autowired private JdbcTemplate jdbc;
  @MockitoBean private AccountInactivityEffects accountEffects;

  private Chat proofSeries;
  private Consultant proofOwner;
  private Consultant invitedConsultant;
  private Consultant unrelatedConsultant;
  private final List<User> proofUsers = new ArrayList<>();

  @BeforeEach
  void selectTechnicalFixtureContext() {
    TenantContext.setCurrentTenant(0L);
  }

  @AfterEach
  void deleteReplicaProofNotification() {
    try {
      var notifications =
          eventNotificationRepository.findByRecipientUserIdOrderByCreateDateDescIdDesc(
              RECIPIENT, Pageable.unpaged());
      eventNotificationRepository.deleteAll(notifications);
      for (var user : proofUsers) {
        this.notifications.clearFeed(user.getUserId());
        jdbc.update("DELETE FROM account_inactivity_journal WHERE identity_id=?", user.getUserId());
        jdbc.update("DELETE FROM account_inactivity WHERE identity_id=?", user.getUserId());
      }
      if (proofSeries != null) {
        userChats.deleteAll(userChats.findByChat(proofSeries));
        participants.deleteAll(participants.findBySeriesId(proofSeries.getId()));
        chats.delete(proofSeries);
      }
      userRepository.deleteAll(proofUsers);
      if (proofOwner != null) {
        this.notifications.clearFeed(proofOwner.getId());
        consultantRepository.delete(proofOwner);
      }
      if (invitedConsultant != null) {
        this.notifications.clearFeed(invitedConsultant.getId());
        jdbc.update(
            "DELETE FROM account_inactivity_journal WHERE identity_id=?",
            invitedConsultant.getId());
        jdbc.update(
            "DELETE FROM account_inactivity WHERE identity_id=?", invitedConsultant.getId());
        consultantRepository.delete(invitedConsultant);
      }
      if (unrelatedConsultant != null) {
        this.notifications.clearFeed(unrelatedConsultant.getId());
        consultantRepository.delete(unrelatedConsultant);
      }
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void actualReminderCollectorDoesNotPersistFeedForASuspendedCurrentMember() {
    var occurrenceStart = LocalDateTime.of(2090, 1, 1, 10, 0);
    createProofSeries(occurrenceStart);
    var suspended = addProofMember("suspended");
    var active = addProofMember("active");
    for (var member : proofUsers) {
      accountLifecycle.assignAtCreation(member.getUserId(), 5L, 12, 1L, Instant.now());
    }
    when(accountEffects.suspend(suspended.getUserId())).thenReturn(true);
    assertThat(accountLifecycle.suspend(suspended.getUserId())).isTrue();
    assertThat(accountLifecycle.snapshot(suspended.getUserId()))
        .hasValueSatisfying(
            state ->
                assertThat(state.status()).isEqualTo(AccountInactivityService.Status.SUSPENDED));
    assertThat(userRepository.findById(suspended.getUserId()).orElseThrow().getDeleteDate())
        .isNull();
    assertThat(userChats.findByChat(proofSeries)).hasSize(2);

    reminders.publishUpcomingReminders(occurrenceStart.minusMinutes(60));
    reminders.publishUpcomingReminders(occurrenceStart.minusMinutes(60));

    assertThat(notifications.getFeed(active.getUserId(), 0, 100).getItems())
        .singleElement()
        .extracting(item -> item.getEventType())
        .isEqualTo(GroupChatLifecycleNotificationService.EVENT_GROUP_CHAT_REMINDER);
    assertThat(notifications.getFeed(suspended.getUserId(), 0, 100).getItems()).isEmpty();
  }

  @Test
  void actualReminderCollectorDoesNotPersistFeedForSoftDeletedCurrentMembers() {
    var start = LocalDateTime.of(2090, 3, 1, 10, 0);
    createProofSeries(start);
    var deleted = addProofMember("deleted");
    var active = addProofMember("active");
    deleted.setDeleteDate(LocalDateTime.now());
    userRepository.save(deleted);
    proofOwner.setDeleteDate(LocalDateTime.now());
    consultantRepository.save(proofOwner);
    participants.save(
        GroupChatParticipant.builder()
            .chatId(proofSeries.getId())
            .seriesId(proofSeries.getId())
            .consultantId(proofOwner.getId())
            .role(GroupChatParticipant.ParticipantRole.OWNER)
            .build());
    assertThat(userChats.findByChat(proofSeries)).hasSize(2);
    assertThat(participants.findBySeriesId(proofSeries.getId())).hasSize(1);

    reminders.publishUpcomingReminders(start.minusMinutes(60));

    assertThat(notifications.getFeed(active.getUserId(), 0, 100).getItems()).hasSize(1);
    assertThat(
            List.of(
                notifications.getFeed(deleted.getUserId(), 0, 100).getItems().size(),
                notifications.getFeed(proofOwner.getId(), 0, 100).getItems().size()))
        .as("soft-deleted asker and consultant feed sizes")
        .containsExactly(0, 0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"group_chat.opened", "group_chat.cancelled"})
  void actualCollectorAndStorageRetainEligibleCurrentMembersForOpeningAndCancellation(
      String eventType) {
    var start = LocalDateTime.of(2090, 2, 1, 10, 0);
    createProofSeries(start);
    var suspended = addProofMember("suspended");
    var active = addProofMember("active");
    var legacy = addProofMember("legacy");
    var removed = addProofMember("removed");
    accountLifecycle.assignAtCreation(suspended.getUserId(), 5L, 12, 1L, Instant.now());
    accountLifecycle.assignAtCreation(active.getUserId(), 5L, 12, 1L, Instant.now());
    when(accountEffects.suspend(suspended.getUserId())).thenReturn(true);
    assertThat(accountLifecycle.suspend(suspended.getUserId())).isTrue();
    userChats.deleteAll(
        userChats.findByChat(proofSeries).stream()
            .filter(relation -> relation.getUser().getUserId().equals(removed.getUserId()))
            .toList());
    participants.save(
        GroupChatParticipant.builder()
            .chatId(proofSeries.getId())
            .seriesId(proofSeries.getId())
            .consultantId(proofOwner.getId())
            .role(GroupChatParticipant.ParticipantRole.OWNER)
            .build());
    invitedConsultant = createProofConsultant("invited", 99L);
    unrelatedConsultant = createProofConsultant("unrelated", 99L);
    participants.save(
        GroupChatParticipant.builder()
            .chatId(proofSeries.getId())
            .seriesId(proofSeries.getId())
            .consultantId(invitedConsultant.getId())
            .build());
    accountLifecycle.assignAtCreation(invitedConsultant.getId(), 99L, 12, 1L, Instant.now());
    assertThat(accountLifecycle.snapshot(legacy.getUserId())).isEmpty();
    assertThat(proofSeries.getChatOwner().getTenantId()).isEqualTo(5L);
    assertThat(invitedConsultant.getTenantId()).isEqualTo(99L);

    for (int replay = 0; replay < 2; replay++) {
      TenantContext.runIn(
          5L,
          () -> {
            var currentRecipients = recipients.resolveRecipientIds(proofSeries);
            if (eventType.equals(GroupChatLifecycleNotificationService.EVENT_GROUP_CHAT_OPENED)) {
              lifecycleNotifications.createOpenedNotifications(
                  proofSeries.getId(),
                  0,
                  start,
                  proofSeries.getMatrixRoomId(),
                  null,
                  false,
                  currentRecipients);
            } else {
              lifecycleNotifications.createCancelledNotifications(
                  proofSeries.getId(),
                  0,
                  start,
                  proofSeries.getMatrixRoomId(),
                  null,
                  false,
                  currentRecipients);
            }
            assertThat(TenantContext.getCurrentTenant()).isEqualTo(5L);
          });
      assertThat(TenantContext.getCurrentTenant()).isEqualTo(0L);
    }

    for (var identityId :
        List.of(
            active.getUserId(),
            legacy.getUserId(),
            proofOwner.getId(),
            invitedConsultant.getId())) {
      assertThat(notifications.getFeed(identityId, 0, 100).getItems())
          .singleElement()
          .satisfies(
              item -> {
                assertThat(item.getEventType()).isEqualTo(eventType);
                assertThat(item.getSourceSessionId()).isEqualTo(proofSeries.getId());
                assertThat(item.getParams()).contains("\"occurrenceIndex\":0", start.toString());
              });
    }
    assertThat(notifications.getFeed(suspended.getUserId(), 0, 100).getItems()).isEmpty();
    assertThat(notifications.getFeed(removed.getUserId(), 0, 100).getItems()).isEmpty();
    assertThat(notifications.getFeed(unrelatedConsultant.getId(), 0, 100).getItems()).isEmpty();
  }

  private Consultant createProofConsultant(String label, Long tenantId) {
    return consultantRepository.save(
        Consultant.builder()
            .id(UUID.randomUUID().toString())
            .username("group-feed-" + label + "-" + UUID.randomUUID())
            .firstName(label)
            .lastName("Fixture")
            .email(label + "@example.test")
            .encourage2fa(false)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(false)
            .notifyNewChatMessageFromAdviceSeeker(false)
            .status(ConsultantStatus.CREATED)
            .tenantId(tenantId)
            .languageCode(LanguageCode.fr)
            .build());
  }

  private void createProofSeries(LocalDateTime start) {
    proofOwner =
        consultantRepository.save(
            Consultant.builder()
                .id(UUID.randomUUID().toString())
                .username("group-feed-owner-" + UUID.randomUUID())
                .firstName("Local")
                .lastName("Fixture")
                .email("owner@example.test")
                .encourage2fa(false)
                .magicLinkLoginEnabled(false)
                .notifyEnquiriesRepeating(false)
                .notifyNewChatMessageFromAdviceSeeker(false)
                .status(ConsultantStatus.CREATED)
                .tenantId(5L)
                .languageCode(LanguageCode.en)
                .build());
    proofSeries =
        chats.save(
            Chat.builder()
                .topic("Local lifecycle feed fixture")
                .consultingTypeId(1)
                .initialStartDate(start)
                .startDate(start)
                .duration(60)
                .repeatCount(1)
                .conversationType(ConversationType.SELF_HELP)
                .matrixRoomId("!group-feed-proof:example.test")
                .chatOwner(proofOwner)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build());
  }

  private User addProofMember(String label) {
    var member =
        userRepository.save(
            User.builder()
                .userId(UUID.randomUUID().toString())
                .username("group-feed-" + label + "-" + UUID.randomUUID())
                .email(label + "@example.test")
                .encourage2fa(false)
                .magicLinkLoginEnabled(false)
                .languageCode(LanguageCode.en)
                .tenantId(5L)
                .notificationsEnabled(false)
                .notificationsSettings("{\"appointmentNotificationEnabled\":false}")
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build());
    proofUsers.add(member);
    userChats.save(UserChat.builder().chat(proofSeries).user(member).build());
    return member;
  }

  @Test
  void twoServiceInstancesPublishOneReminder() throws Exception {
    var firstInstance = newServiceInstance();
    var secondInstance = newServiceInstance();
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () -> TenantContext.runIn(0L, () -> publishReminder(firstInstance, ready, start)));
      var second =
          executor.submit(
              () -> TenantContext.runIn(0L, () -> publishReminder(secondInstance, ready, start)));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    var notifications =
        eventNotificationRepository.findByRecipientUserIdOrderByCreateDateDescIdDesc(
            RECIPIENT, Pageable.unpaged());
    assertThat(notifications)
        .singleElement()
        .extracting(notification -> notification.getDeduplicationKey())
        .isEqualTo(DEDUPLICATION_KEY);
  }

  private EventNotificationService newServiceInstance() {
    return new EventNotificationService(
        eventNotificationRepository,
        sessionRepository,
        userRepository,
        consultantRepository,
        identityTombstoneService,
        deduplicationWriter,
        feedUpdateSignalService,
        new ConsultantDisplayNameResolver());
  }

  private void publishReminder(
      EventNotificationService service, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    service.createEventOnce(
        DEDUPLICATION_KEY,
        RECIPIENT,
        "group_chat.reminder",
        EventNotificationService.CATEGORY_SYSTEM,
        "Reminder",
        "Soon",
        "{\"seriesId\":42}",
        null,
        42L,
        null);
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent replica proof");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
