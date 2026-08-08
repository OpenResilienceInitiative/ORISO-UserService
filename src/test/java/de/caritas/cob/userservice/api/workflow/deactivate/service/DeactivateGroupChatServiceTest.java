package de.caritas.cob.userservice.api.workflow.deactivate.service;

import static java.util.Collections.emptyList;
import static org.mockito.Mockito.atLeastOnce;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeactivateGroupChatServiceTest {

  private static final int DEACTIVATE_PERIOD_MINUTES = 180;

  @InjectMocks private DeactivateGroupChatService deactivateGroupChatService;

  @Mock private ChatRepository chatRepository;

  @Mock private ActionsRegistry actionsRegistry;

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
    when(this.chatRepository.findAllActiveExcludingConversationType(
            ConversationType.INTERNAL_GROUP))
        .thenReturn(emptyList());

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
    when(this.chatRepository.findAllActiveExcludingConversationType(
            ConversationType.INTERNAL_GROUP))
        .thenReturn(List.of(chat));

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
    when(this.chatRepository.findAllActiveExcludingConversationType(
            ConversationType.INTERNAL_GROUP))
        .thenReturn(List.of(chat));
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
    when(this.chatRepository.findAllActiveExcludingConversationType(
            ConversationType.INTERNAL_GROUP))
        .thenReturn(List.of(chat));
    when(this.actionsRegistry.buildContainerForType(Chat.class))
        .thenReturn(commandMockProvider.getActionContainer(Chat.class));

    this.deactivateGroupChatService.deactivateStaleGroupChats();

    verify(this.commandMockProvider.getActionMock(StopChatActionCommand.class)).execute(chat);
  }

  /**
   * Internal team chats are excluded in the query rather than afterwards, so that the pessimistic
   * write lock never reaches rows this sweep will not act on. The unit test can only prove that the
   * exclusion is asked for; that the SQL actually drops internal chats and keeps null conversation
   * types is proven against a database in {@code ChatRepositoryIT}.
   */
  @Test
  void deactivateStaleGroupChats_Should_excludeInternalTeamChatsFromTheSelection() {
    when(this.chatRepository.findAllActiveExcludingConversationType(
            ConversationType.INTERNAL_GROUP))
        .thenReturn(emptyList());

    this.deactivateGroupChatService.deactivateStaleGroupChats();

    verify(this.chatRepository)
        .findAllActiveExcludingConversationType(ConversationType.INTERNAL_GROUP);
    verify(this.chatRepository, never()).findAllByActiveIsTrue();
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
    when(this.chatRepository.findAllActiveExcludingConversationType(
            ConversationType.INTERNAL_GROUP))
        .thenReturn(List.of(chat));
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
    when(this.chatRepository.findAllActiveExcludingConversationType(
            ConversationType.INTERNAL_GROUP))
        .thenReturn(List.of(legacyChat));
    when(this.actionsRegistry.buildContainerForType(Chat.class))
        .thenReturn(commandMockProvider.getActionContainer(Chat.class));

    this.deactivateGroupChatService.deactivateStaleGroupChats();

    verify(this.commandMockProvider.getActionMock(StopChatActionCommand.class)).execute(legacyChat);
  }

  private static List<LocalDateTime> createOverdueUpdateDates() {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime oneDeletionPeriodAgo =
        now.minusMinutes(DEACTIVATE_PERIOD_MINUTES).minusMinutes(120);
    LocalDateTime timeLongInThePast = oneDeletionPeriodAgo.minusMinutes(10);

    return List.of(oneDeletionPeriodAgo, timeLongInThePast);
  }
}
