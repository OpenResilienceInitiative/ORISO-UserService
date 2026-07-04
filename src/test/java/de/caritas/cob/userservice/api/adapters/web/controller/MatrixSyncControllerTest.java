package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.matrix.MatrixEventListenerService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class MatrixSyncControllerTest {

  private static final Long SESSION_ID = 42L;
  private static final String USER_ID = "user-id";
  private static final String MATRIX_ROOM_ID = "!room:matrix";

  @Mock private MatrixEventListenerService matrixEventListenerService;
  @Mock private SessionService sessionService;
  @Mock private AuthenticatedUser authenticatedUser;

  @InjectMocks private MatrixSyncController controller;

  @Test
  void registerRoomForSync_ShouldThrowForbiddenBeforeRegistering_WhenSessionAccessIsDenied() {
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenThrow(new ForbiddenException("No permission"));

    assertThrows(ForbiddenException.class, () -> controller.registerRoomForSync(SESSION_ID));

    // IDOR is closed: no room is registered and no Matrix room id / user count is leaked.
    verifyNoInteractions(matrixEventListenerService);
  }

  @Test
  void unregisterRoomFromSync_ShouldThrowForbiddenBeforeUnregistering_WhenSessionAccessIsDenied() {
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenThrow(new ForbiddenException("No permission"));

    assertThrows(ForbiddenException.class, () -> controller.unregisterRoomFromSync(SESSION_ID));

    // Denial-of-function is closed: an outsider cannot unregister another session's room.
    verifyNoInteractions(matrixEventListenerService);
  }

  @Test
  void registerRoomForSync_ShouldRegisterRoom_WhenAuthorizedParticipant() {
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenReturn(sessionWithMatrixRoom());

    var response = controller.registerRoomForSync(SESSION_ID);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(matrixEventListenerService).registerRoom(eq(SESSION_ID), eq(MATRIX_ROOM_ID), anySet());
  }

  private Session sessionWithMatrixRoom() {
    return Session.builder()
        .id(SESSION_ID)
        .user(userWithMatrixId())
        .consultingTypeId(0)
        .registrationType(Session.RegistrationType.REGISTERED)
        .postcode("12345")
        .status(Session.SessionStatus.IN_PROGRESS)
        .matrixRoomId(MATRIX_ROOM_ID)
        .teamSession(false)
        .build();
  }

  private User userWithMatrixId() {
    return User.builder()
        .userId(USER_ID)
        .username("seeker")
        .email("seeker@example.org")
        .matrixUserId("@seeker:matrix")
        .languageFormal(false)
        .build();
  }
}
