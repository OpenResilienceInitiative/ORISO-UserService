package de.caritas.cob.userservice.api.actions.chat;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.service.ChatService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StopChatActionCommandTest {

  private static final String MATRIX_ROOM_ID = "!group:matrix.oriso.org";

  @Mock private ChatService chatService;
  @Mock private ChatReCreator chatReCreator;
  @Mock private MatrixChatShutdownService matrixChatShutdownService;

  private StopChatActionCommand command;

  @BeforeEach
  void setUp() {
    command = new StopChatActionCommand(chatService, chatReCreator, matrixChatShutdownService);
  }

  @Test
  void stopChatShouldRejectInactiveChat() {
    Chat chat = mock(Chat.class);

    assertThrows(ConflictException.class, () -> command.execute(chat));
  }

  @Test
  void stopChatShouldRequireMatrixRoomId() {
    Chat chat = activeChat();

    assertThrows(InternalServerErrorException.class, () -> command.execute(chat));
  }

  @Test
  void stopChatShouldRejectFiniteSeriesWithoutInterval() {
    Chat chat = activeMatrixChat();
    when(chat.isRepetitive()).thenReturn(true);

    assertThrows(InternalServerErrorException.class, () -> command.execute(chat));

    verify(matrixChatShutdownService, never()).shutdownRoom(chat);
  }

  @Test
  void stopChatShouldDeleteSingleChatAndShutdownMatrixRoom() {
    Chat chat = activeMatrixChat();

    command.execute(chat);

    verify(chatService).deleteChat(chat);
    verify(matrixChatShutdownService).shutdownRoom(chat);
  }

  @Test
  void stopChatShouldCreateNextOccurrenceForRepetitiveChat() {
    Chat chat = activeMatrixChat();
    when(chat.isRepetitive()).thenReturn(true);
    when(chat.getChatInterval()).thenReturn(Chat.ChatInterval.WEEKLY);
    when(chat.nextStart()).thenReturn(LocalDateTime.parse("2026-08-04T10:00:00"));
    when(chatReCreator.recreateMessengerChat(chat)).thenReturn("!next:matrix.oriso.org");

    command.execute(chat);

    verify(chatReCreator).updateAsNextChat(chat, "!next:matrix.oriso.org");
    verify(chatService, never()).deleteChat(chat);
  }

  @Test
  void stopChatShouldCompleteFinalOccurrenceOfRepetitiveChat() {
    Chat chat = activeMatrixChat();
    when(chat.isRepetitive()).thenReturn(true);
    when(chat.getChatInterval()).thenReturn(Chat.ChatInterval.WEEKLY);
    when(chat.nextStart()).thenReturn(null);

    command.execute(chat);

    verify(matrixChatShutdownService).shutdownRoom(chat);
    verify(chat).setActive(false);
    verify(chatService).saveChat(chat);
  }

  private Chat activeChat() {
    Chat chat = mock(Chat.class);
    when(chat.isActive()).thenReturn(true);
    return chat;
  }

  private Chat activeMatrixChat() {
    Chat chat = activeChat();
    when(chat.getMatrixRoomId()).thenReturn(MATRIX_ROOM_ID);
    return chat;
  }
}
