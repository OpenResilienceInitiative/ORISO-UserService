package de.caritas.cob.userservice.api.admin.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.RoomPurgeOutcome;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.chat.GroupChatMatrixCleanupService;
import de.caritas.cob.userservice.api.service.matrixgroup.ChatMatrixRoomHistory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class AgencyGroupChatDeleterTest {

  private static final Long AGENCY = 7L;
  private static final String CURRENT_ROOM = "!current:oriso";
  private static final String PREPARED_ROOM = "!prepared:oriso";

  @Mock private ChatRepository chatRepository;
  @Mock private ChatService chatService;
  @Mock private ChatMatrixRoomHistory roomHistory;
  @Mock private GroupChatMatrixCleanupService groupLocks;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private PlatformTransactionManager transactionManager;

  private AgencyGroupChatDeleter deleter;

  @BeforeEach
  void setUp() {
    deleter =
        new AgencyGroupChatDeleter(
            chatRepository,
            chatService,
            roomHistory,
            groupLocks,
            matrixSynapseService,
            transactionManager);
  }

  @Test
  void deleteChatsOf_Should_deleteAnInactiveScheduledChat_andPurgeItsRoom() {
    var scheduled = chat(1L, false, false);
    scheduled.setStartDate(LocalDateTime.now().plusDays(3));
    given(scheduled);
    when(roomHistory.preparedNextRoom(scheduled)).thenReturn(Optional.empty());
    when(matrixSynapseService.purgeRoomOrConfirmGone(CURRENT_ROOM))
        .thenReturn(RoomPurgeOutcome.PURGED);

    var deleted = deleter.deleteChatsOf(AGENCY);

    assertThat(deleted.count()).isEqualTo(1);
    assertThat(deleted.failedChatIds()).isEmpty();
    var order = inOrder(groupLocks, chatService, matrixSynapseService, transactionManager);
    order.verify(groupLocks).lockOwner(scheduled.getChatOwner());
    order.verify(chatService).deleteChatRecords(scheduled);
    order.verify(matrixSynapseService).purgeRoomOrConfirmGone(CURRENT_ROOM);
    order.verify(transactionManager).commit(any());
  }

  @Test
  void deleteChatsOf_Should_endAnActiveRecurringSeries_withoutCreatingItsNextOccurrence() {
    var series = chat(2L, true, true);
    series.setRepeatCount(12);
    series.setChatInterval(ChatInterval.WEEKLY);
    series.setCurrentOccurrenceIndex(3);
    given(series);
    when(roomHistory.preparedNextRoom(series)).thenReturn(Optional.of(PREPARED_ROOM));
    when(matrixSynapseService.purgeRoomOrConfirmGone(any())).thenReturn(RoomPurgeOutcome.PURGED);

    var deleted = deleter.deleteChatsOf(AGENCY);

    assertThat(deleted.count()).isEqualTo(1);
    verify(chatService).deleteChatRecords(series);
    verify(matrixSynapseService).purgeRoomOrConfirmGone(CURRENT_ROOM);
    verify(matrixSynapseService).purgeRoomOrConfirmGone(PREPARED_ROOM);
    // No room for a next occurrence, no member carried, nothing saved as "next chat".
    verifyNoMoreInteractions(matrixSynapseService);
    verify(chatService, never()).saveChat(any());
  }

  @Test
  void deleteChatsOf_Should_keepAndReportTheChat_When_aRoomCannotBePurged_andDeleteItNextCall() {
    var series = chat(3L, true, true);
    given(series);
    when(roomHistory.preparedNextRoom(series)).thenReturn(Optional.of(PREPARED_ROOM));
    when(matrixSynapseService.purgeRoomOrConfirmGone(CURRENT_ROOM))
        .thenReturn(RoomPurgeOutcome.PURGED);
    when(matrixSynapseService.purgeRoomOrConfirmGone(PREPARED_ROOM))
        .thenReturn(RoomPurgeOutcome.FAILED, RoomPurgeOutcome.PURGED);

    var first = deleter.deleteChatsOf(AGENCY);

    assertThat(first.count()).isZero();
    assertThat(first.failedChatIds()).containsExactly(3L);
    // The record deletion is rolled back, so the chat is still there to be found again.
    verify(transactionManager).rollback(any());
    verify(transactionManager, never()).commit(any());

    var second = deleter.deleteChatsOf(AGENCY);

    assertThat(second.count()).isEqualTo(1);
    assertThat(second.failedChatIds()).isEmpty();
    verify(transactionManager).commit(any());
  }

  @Test
  void deleteChatsOf_Should_reportTheChatAsFailed_When_thePurgeThrows() {
    var chat = chat(4L, true, false);
    given(chat);
    when(roomHistory.preparedNextRoom(chat)).thenReturn(Optional.empty());
    when(matrixSynapseService.purgeRoomOrConfirmGone(CURRENT_ROOM))
        .thenThrow(new IllegalStateException("Synapse down"));

    var deleted = deleter.deleteChatsOf(AGENCY);

    assertThat(deleted.failedChatIds()).containsExactly(4L);
    verify(transactionManager).rollback(any());
  }

  @Test
  void deleteChatsOf_Should_skipAChat_When_itWasDeletedSinceItWasListed() {
    var chat = chat(5L, false, false);
    when(chatRepository.findByAgencyIds(Set.of(AGENCY))).thenReturn(List.of(chat));
    when(chatRepository.findByIdForUpdate(5L)).thenReturn(Optional.empty());
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

    var deleted = deleter.deleteChatsOf(AGENCY);

    assertThat(deleted.count()).isZero();
    assertThat(deleted.failedChatIds()).isEmpty();
    verify(chatService, never()).deleteChatRecords(any());
    verifyNoMoreInteractions(matrixSynapseService);
  }

  @Test
  void countChatsOf_Should_countInactiveChatsToo() {
    when(chatRepository.findByAgencyIds(Set.of(AGENCY)))
        .thenReturn(List.of(chat(1L, false, false), chat(2L, true, true)));

    assertThat(deleter.countChatsOf(AGENCY)).isEqualTo(2L);
  }

  private void given(Chat chat) {
    when(chatRepository.findByAgencyIds(Set.of(AGENCY))).thenReturn(List.of(chat));
    when(chatRepository.findByIdForUpdate(chat.getId())).thenReturn(Optional.of(chat));
    when(transactionManager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
  }

  private static Chat chat(Long id, boolean active, boolean repetitive) {
    var owner = new Consultant();
    owner.setId("owner-" + id);
    owner.setTenantId(1L);
    var chat = new Chat();
    chat.setId(id);
    chat.setChatOwner(owner);
    chat.setMatrixRoomId(CURRENT_ROOM);
    chat.setActive(active);
    chat.setRepetitive(repetitive);
    chat.setStartDate(LocalDateTime.now());
    chat.setDuration(60);
    return chat;
  }
}
