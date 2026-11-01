package de.caritas.cob.userservice.api.workflow.deactivate.service;

import static java.util.Collections.emptyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.actions.ActionCommandMockProvider;
import de.caritas.cob.userservice.api.actions.chat.StopChatActionCommand;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class DeactivateGroupChatServiceTest {

  private static final int DEACTIVATE_PERIOD_MINUTES = 180;

  @InjectMocks private DeactivateGroupChatService deactivateGroupChatService;

  @Mock private ChatRepository chatRepository;

  @Mock private ActionsRegistry actionsRegistry;

  @Mock private PlatformTransactionManager transactionManager;

  private final AtomicLong nextId = new AtomicLong(100);

  private final ActionCommandMockProvider commandMockProvider = new ActionCommandMockProvider();

  @BeforeEach
  public void setUp() {
    setField(deactivateGroupChatService, "deactivatePeriodMinutes", DEACTIVATE_PERIOD_MINUTES);
  }

  @Test
  void deactivateStaleGroupChats_Should_notUseServices_When_noChatIsAvailable() {
    this.deactivateGroupChatService.deactivateStaleGroupChats();

    verifyNoMoreInteractions(this.actionsRegistry);
  }

  @Test
  void deactivateStaleGroupChats_Should_notPerformAnyDeactivation_When_noChatIsActive() {
    when(this.chatRepository.findAllByActiveIsTrue()).thenReturn(emptyList());

    this.deactivateGroupChatService.deactivateStaleGroupChats();

    verifyNoMoreInteractions(this.actionsRegistry);
  }

  @ParameterizedTest
  @MethodSource("createUpdateDatesWithinDeactivationPeriod")
  void
      deactivateStaleGroupChats_Should_notPerformAnyDeactivation_When_chatsAreActiveWithinDeactivatePeriod(
          LocalDateTime updateDate) {
    var chat = new Chat();
    chat.setDuration(120);
    chat.setActive(true);
    chat.setUpdateDate(updateDate);
    givenActiveChats(chat);

    this.deactivateGroupChatService.deactivateStaleGroupChats();

    verifyNoMoreInteractions(
        this.actionsRegistry, this.commandMockProvider.getActionMock(StopChatActionCommand.class));
  }

  private static List<LocalDateTime> createUpdateDatesWithinDeactivationPeriod() {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime oneSecondWithinDeletionPeriod =
        now.minusMinutes(DEACTIVATE_PERIOD_MINUTES).plusSeconds(10);
    LocalDateTime timeInTheFuture = now.plusSeconds(20);

    return List.of(now, oneSecondWithinDeletionPeriod, timeInTheFuture);
  }

  @ParameterizedTest
  @MethodSource("createOverdueUpdateDates")
  void deactivateStaleGroupChats_Should_callStopChatAction_When_chatIsActiveTooLong(
      LocalDateTime overdueUpdateDate) {
    var chat = new Chat();
    chat.setDuration(120);
    chat.setActive(true);
    chat.setUpdateDate(overdueUpdateDate);
    givenActiveChats(chat);
    when(this.actionsRegistry.buildContainerForType(Chat.class))
        .thenReturn(commandMockProvider.getActionContainer(Chat.class));

    this.deactivateGroupChatService.deactivateStaleGroupChats();

    verify(this.actionsRegistry, atLeastOnce()).buildContainerForType(Chat.class);
    verify(this.commandMockProvider.getActionMock(StopChatActionCommand.class), times(1))
        .execute(chat);
  }

  @Test
  void deactivateShouldUseThePlannedEndEvenWhenTheChatWasUpdatedRecently() {
    setField(deactivateGroupChatService, "deactivatePeriodMinutes", 0L);
    var chat = new Chat();
    chat.setDuration(60);
    chat.setActive(true);
    chat.setStartDate(LocalDateTime.now().minusMinutes(61));
    chat.setUpdateDate(LocalDateTime.now());
    givenActiveChats(chat);
    when(this.actionsRegistry.buildContainerForType(Chat.class))
        .thenReturn(commandMockProvider.getActionContainer(Chat.class));

    this.deactivateGroupChatService.deactivateStaleGroupChats();

    verify(this.commandMockProvider.getActionMock(StopChatActionCommand.class)).execute(chat);
  }

  @Test
  void deactivateStaleGroupChats_Should_leaveInternalTeamChatsAlone_When_theyAreLongOverdue() {
    var internalChat = new Chat();
    internalChat.setDuration(60);
    internalChat.setActive(true);
    internalChat.setUpdateDate(LocalDateTime.now().minusDays(30));
    internalChat.setConversationType(ConversationType.INTERNAL_GROUP);
    givenActiveChats(internalChat);

    this.deactivateGroupChatService.deactivateStaleGroupChats();

    verifyNoMoreInteractions(
        this.actionsRegistry, this.commandMockProvider.getActionMock(StopChatActionCommand.class));
  }

  @ParameterizedTest
  @EnumSource(
      value = ConversationType.class,
      names = {"INTERNAL_GROUP"},
      mode = EnumSource.Mode.EXCLUDE)
  void deactivateStaleGroupChats_Should_stillStopEveryOtherModality(
      ConversationType conversationType) {
    var chat = new Chat();
    chat.setDuration(60);
    chat.setActive(true);
    chat.setUpdateDate(LocalDateTime.now().minusDays(30));
    chat.setConversationType(conversationType);
    givenActiveChats(chat);
    when(this.actionsRegistry.buildContainerForType(Chat.class))
        .thenReturn(commandMockProvider.getActionContainer(Chat.class));

    this.deactivateGroupChatService.deactivateStaleGroupChats();

    verify(this.commandMockProvider.getActionMock(StopChatActionCommand.class)).execute(chat);
  }

  /**
   * Rows written before the modality column existed carry a null conversation type. They must keep
   * the previous behaviour rather than silently become undeletable.
   */
  @Test
  void deactivateStaleGroupChats_Should_stillStopChats_When_theConversationTypeIsUnset() {
    var legacyChat = new Chat();
    legacyChat.setDuration(60);
    legacyChat.setActive(true);
    legacyChat.setUpdateDate(LocalDateTime.now().minusDays(30));
    givenActiveChats(legacyChat);
    when(this.actionsRegistry.buildContainerForType(Chat.class))
        .thenReturn(commandMockProvider.getActionContainer(Chat.class));

    this.deactivateGroupChatService.deactivateStaleGroupChats();

    verify(this.commandMockProvider.getActionMock(StopChatActionCommand.class)).execute(legacyChat);
  }

  @Test
  void deactivateStaleGroupChats_Should_goOnWithTheOtherChats_When_oneRoomCannotBeShutDown() {
    var stuck = overdueChat(1L);
    var other = overdueChat(2L);
    givenActiveChats(stuck, other);
    when(this.actionsRegistry.buildContainerForType(Chat.class))
        .thenReturn(commandMockProvider.getActionContainer(Chat.class));
    var stop = this.commandMockProvider.getActionMock(StopChatActionCommand.class);
    doThrow(new IllegalStateException("Matrix unavailable")).when(stop).execute(stuck);

    this.deactivateGroupChatService.deactivateStaleGroupChats();

    verify(stop).execute(other);
  }

  /** Stubs the candidate read and the per-chat re-lock with the same rows. */
  private void givenActiveChats(Chat... chats) {
    for (var chat : chats) {
      if (chat.getId() == null) {
        chat.setId(nextId.incrementAndGet());
      }
      lenient()
          .when(this.chatRepository.findByIdForUpdate(chat.getId()))
          .thenReturn(Optional.of(chat));
    }
    when(this.chatRepository.findAllByActiveIsTrue()).thenReturn(List.of(chats));
  }

  @Test
  void deactivateStaleGroupChats_Should_rollBackOnlyTheStuckChat_And_CommitTheOthers() {
    var stuck = overdueChat(1L);
    var other = overdueChat(2L);
    givenActiveChats(stuck, other);
    when(this.actionsRegistry.buildContainerForType(Chat.class))
        .thenReturn(commandMockProvider.getActionContainer(Chat.class));
    var stop = this.commandMockProvider.getActionMock(StopChatActionCommand.class);
    doThrow(new IllegalStateException("Matrix unavailable")).when(stop).execute(stuck);

    this.deactivateGroupChatService.deactivateStaleGroupChats();

    // One rollback for the stuck chat; commits for the candidate read and the other chat.
    verify(this.transactionManager, times(1)).rollback(any());
    verify(this.transactionManager, times(2)).commit(any());
    verify(stop).execute(other);
  }

  @Test
  void deactivateStaleGroupChats_Should_skipAChat_When_anotherReplicaStoppedItMeanwhile() {
    var chat = overdueChat(3L);
    givenActiveChats(chat);
    var stoppedMeanwhile = overdueChat(3L);
    stoppedMeanwhile.setActive(false);
    when(this.chatRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(stoppedMeanwhile));

    this.deactivateGroupChatService.deactivateStaleGroupChats();

    verify(this.commandMockProvider.getActionMock(StopChatActionCommand.class), never())
        .execute(any());
    verifyNoMoreInteractions(this.actionsRegistry);
  }

  @Test
  void deactivateStaleGroupChats_Should_skipAChat_When_itWasRescheduledMeanwhile() {
    var chat = overdueChat(5L);
    givenActiveChats(chat);
    var rescheduled = overdueChat(5L);
    rescheduled.setStartDate(LocalDateTime.now().plusDays(1));
    when(this.chatRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(rescheduled));

    this.deactivateGroupChatService.deactivateStaleGroupChats();

    verifyNoMoreInteractions(this.actionsRegistry);
  }

  @Test
  void deactivateStaleGroupChats_Should_skipAChat_When_itWasDeletedMeanwhile() {
    var chat = overdueChat(4L);
    givenActiveChats(chat);
    when(this.chatRepository.findByIdForUpdate(4L)).thenReturn(Optional.empty());

    this.deactivateGroupChatService.deactivateStaleGroupChats();

    verifyNoMoreInteractions(this.actionsRegistry);
  }

  private static Chat overdueChat(Long id) {
    var chat = new Chat();
    chat.setId(id);
    chat.setDuration(60);
    chat.setActive(true);
    chat.setUpdateDate(LocalDateTime.now().minusDays(30));
    return chat;
  }

  private static List<LocalDateTime> createOverdueUpdateDates() {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime oneDeletionPeriodAgo =
        now.minusMinutes(DEACTIVATE_PERIOD_MINUTES).minusMinutes(120);
    LocalDateTime timeLongInThePast = oneDeletionPeriodAgo.minusMinutes(10);

    return List.of(oneDeletionPeriodAgo, timeLongInThePast);
  }
}
