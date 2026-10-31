package de.caritas.cob.userservice.api.actions.chat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Chat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class MatrixChatShutdownServiceTest {

  private static final String MATRIX_ROOM_ID = "!room:matrix.local";

  @InjectMocks private MatrixChatShutdownService matrixChatShutdownService;

  @Mock private MatrixSynapseService matrixSynapseService;

  @Test
  void shutdownRoomShouldPurgeMatrixRoomWhenChatHasMatrixRoomId() {
    var chat = chatWithMatrixRoomId(MATRIX_ROOM_ID);
    when(matrixSynapseService.purgeRoom(MATRIX_ROOM_ID)).thenReturn(true);

    matrixChatShutdownService.shutdownRoom(chat);

    verify(matrixSynapseService).purgeRoom(MATRIX_ROOM_ID);
  }

  @Test
  void shutdownRoomShouldNotCallMatrixWhenMatrixRoomIdIsNull() {
    matrixChatShutdownService.shutdownRoom(chatWithMatrixRoomId(null));

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void shutdownRoomShouldNotCallMatrixWhenMatrixRoomIdIsBlank() {
    matrixChatShutdownService.shutdownRoom(chatWithMatrixRoomId("  "));

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void shutdownRoomShouldNotThrowWhenPurgeReportsFailure() {
    var chat = chatWithMatrixRoomId(MATRIX_ROOM_ID);
    when(matrixSynapseService.purgeRoom(MATRIX_ROOM_ID)).thenReturn(false);

    assertThatCode(() -> matrixChatShutdownService.shutdownRoom(chat)).doesNotThrowAnyException();
  }

  @Test
  void shutdownRoomShouldNotThrowWhenMatrixCallThrows() {
    var chat = chatWithMatrixRoomId(MATRIX_ROOM_ID);
    when(matrixSynapseService.purgeRoom(MATRIX_ROOM_ID))
        .thenThrow(new RestClientException("Synapse unavailable"));

    assertThatCode(() -> matrixChatShutdownService.shutdownRoom(chat)).doesNotThrowAnyException();
  }

  @Test
  void shutdownRoomShouldFailForAMembersOnlyRoomThatCannotBePurged() {
    var chat = chatWithMatrixRoomId(MATRIX_ROOM_ID);
    chat.setMembersOnly(true);
    when(matrixSynapseService.purgeRoomOrConfirmGone(MATRIX_ROOM_ID))
        .thenReturn(MatrixSynapseService.RoomPurgeOutcome.FAILED);

    var refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            de.caritas.cob.userservice.api.exception.httpresponses
                .CustomValidationHttpStatusException.class,
            () -> matrixChatShutdownService.shutdownRoom(chat));

    org.junit.jupiter.api.Assertions.assertEquals(
        org.springframework.http.HttpStatus.BAD_GATEWAY, refused.getHttpStatus());
    org.junit.jupiter.api.Assertions.assertEquals(
        "INTERNAL_CHAT_MEMBERSHIP_FAILED", refused.getCustomHttpHeaders().getFirst("X-Reason"));
  }

  @Test
  void shutdownRoomShouldFailForAMembersOnlyRoomWhenSynapseCannotBeReached() {
    var chat = chatWithMatrixRoomId(MATRIX_ROOM_ID);
    chat.setMembersOnly(true);
    when(matrixSynapseService.purgeRoomOrConfirmGone(MATRIX_ROOM_ID))
        .thenThrow(new RestClientException("Synapse unavailable"));

    org.junit.jupiter.api.Assertions.assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException
            .class,
        () -> matrixChatShutdownService.shutdownRoom(chat));
  }

  @Test
  void shutdownRoomShouldAcceptAMembersOnlyRoomThatIsGoneAlready() {
    var chat = chatWithMatrixRoomId(MATRIX_ROOM_ID);
    chat.setMembersOnly(true);
    when(matrixSynapseService.purgeRoomOrConfirmGone(MATRIX_ROOM_ID))
        .thenReturn(MatrixSynapseService.RoomPurgeOutcome.ALREADY_GONE);

    assertThatCode(() -> matrixChatShutdownService.shutdownRoom(chat)).doesNotThrowAnyException();
  }

  @Test
  void shutdownRoomStrictlyShouldRefuseAnOrdinaryRoomThatCannotBePurged() {
    var chat = chatWithMatrixRoomId(MATRIX_ROOM_ID);
    when(matrixSynapseService.purgeRoomOrConfirmGone(MATRIX_ROOM_ID))
        .thenReturn(MatrixSynapseService.RoomPurgeOutcome.FAILED);

    var refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            de.caritas.cob.userservice.api.exception.httpresponses
                .CustomValidationHttpStatusException.class,
            () -> matrixChatShutdownService.shutdownRoomStrictly(chat));

    org.junit.jupiter.api.Assertions.assertEquals(
        org.springframework.http.HttpStatus.BAD_GATEWAY, refused.getHttpStatus());
  }

  @Test
  void shutdownRoomStrictlyShouldRefuseAnOrdinaryRoomWhenSynapseCannotBeReached() {
    var chat = chatWithMatrixRoomId(MATRIX_ROOM_ID);
    when(matrixSynapseService.purgeRoomOrConfirmGone(MATRIX_ROOM_ID))
        .thenThrow(new RestClientException("Synapse unavailable"));

    org.junit.jupiter.api.Assertions.assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException
            .class,
        () -> matrixChatShutdownService.shutdownRoomStrictly(chat));
  }

  @Test
  void shutdownRoomStrictlyShouldAcceptPurgedAndAlreadyGoneRooms() {
    var chat = chatWithMatrixRoomId(MATRIX_ROOM_ID);
    when(matrixSynapseService.purgeRoomOrConfirmGone(MATRIX_ROOM_ID))
        .thenReturn(
            MatrixSynapseService.RoomPurgeOutcome.PURGED,
            MatrixSynapseService.RoomPurgeOutcome.ALREADY_GONE);

    assertThatCode(() -> matrixChatShutdownService.shutdownRoomStrictly(chat))
        .doesNotThrowAnyException();
    assertThatCode(() -> matrixChatShutdownService.shutdownRoomStrictly(chat))
        .doesNotThrowAnyException();
  }

  @Test
  void shutdownRoomStrictlyShouldSkipAChatWithoutMatrixRoomId() {
    matrixChatShutdownService.shutdownRoomStrictly(chatWithMatrixRoomId(null));

    verifyNoInteractions(matrixSynapseService);
  }

  private Chat chatWithMatrixRoomId(String matrixRoomId) {
    var chat = new Chat();
    chat.setId(1L);
    chat.setMatrixRoomId(matrixRoomId);
    return chat;
  }
}
