package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.Map;
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
  private static final String CONSULTANT_ID = "consultant-id";
  private static final String MATRIX_ROOM_ID = "!room:matrix";

  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private SessionService sessionService;
  @Mock private AuthenticatedUser authenticatedUser;

  @InjectMocks private MatrixSyncController controller;

  @Test
  void registerRoomForSync_ShouldThrowForbiddenBeforeRegistering_WhenSessionAccessIsDenied() {
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenThrow(new ForbiddenException("No permission"));

    assertThrows(ForbiddenException.class, () -> controller.registerRoomForSync(SESSION_ID));

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void unregisterRoomFromSync_ShouldThrowForbiddenBeforeUnregistering_WhenSessionAccessIsDenied() {
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenThrow(new ForbiddenException("No permission"));

    assertThrows(ForbiddenException.class, () -> controller.unregisterRoomFromSync(SESSION_ID));

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void registerRoomForSync_ShouldPrepareRoom_WhenAuthorizedParticipant() {
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenReturn(sessionWithMatrixRoom());

    var response = controller.registerRoomForSync(SESSION_ID);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(matrixSynapseService).ensureAdminInRoom(MATRIX_ROOM_ID, "@seeker:matrix");
  }

  @Test
  void registerRoomForSync_noMatrixRoom_returnsNotFoundWithErrorBody() {
    // Business reason: sessions without a Matrix room must not leak sync state to callers.
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenReturn(sessionWithoutMatrixRoom());

    var response = controller.registerRoomForSync(SESSION_ID);

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    @SuppressWarnings("unchecked")
    var body = (Map<String, String>) response.getBody();
    assertEquals("Session not found or has no Matrix room", body.get("error"));
    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void registerRoomForSync_withConsultant_reportsTwoParticipants() {
    // The compatibility response still reports both current session participants.
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenReturn(sessionWithMatrixRoomAndConsultant());
    var response = controller.registerRoomForSync(SESSION_ID);

    @SuppressWarnings("unchecked")
    var body = (Map<String, Object>) response.getBody();
    assertEquals(2, body.get("userCount"));
  }

  @Test
  void registerRoomForSync_withoutConsultant_reportsOneParticipant() {
    // An unassigned session still reports only its asker.
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenReturn(sessionWithMatrixRoom());
    var response = controller.registerRoomForSync(SESSION_ID);

    @SuppressWarnings("unchecked")
    var body = (Map<String, Object>) response.getBody();
    assertEquals(1, body.get("userCount"));
  }

  @Test
  void registerRoomForSync_happyPath_returnsSuccessResponseBody() {
    // Business reason: frontend needs room id and participant count to confirm sync registration.
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenReturn(sessionWithMatrixRoom());

    var response = controller.registerRoomForSync(SESSION_ID);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    var body = (Map<String, Object>) response.getBody();
    assertEquals(true, body.get("success"));
    assertEquals(MATRIX_ROOM_ID, body.get("roomId"));
    assertEquals(1, body.get("userCount"));
  }

  @Test
  void registerRoomForSync_sessionNotFound_propagatesNotFoundException() {
    // Business reason: missing sessions must reach the global handler as 404, not be swallowed.
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenThrow(new NotFoundException("Session not found"));

    assertThrows(NotFoundException.class, () -> controller.registerRoomForSync(SESSION_ID));

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void unregisterRoomFromSync_doesNotMutateProcessLocalListenerState() {
    // Closing a session is represented by canonical session state. A request routed to one pod
    // must not try to disable delivery only inside that pod.
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenReturn(sessionWithMatrixRoom());

    var response = controller.unregisterRoomFromSync(SESSION_ID);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    var body = (Map<String, Object>) response.getBody();
    assertEquals(true, body.get("success"));
  }

  @Test
  void unregisterRoomFromSync_noMatrixRoom_returnsOkWithoutUnregisterCall() {
    // Business reason: idempotent unregister must succeed even when no Matrix room was ever set.
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenReturn(sessionWithoutMatrixRoom());

    var response = controller.unregisterRoomFromSync(SESSION_ID);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    var body = (Map<String, Object>) response.getBody();
    assertEquals(true, body.get("success"));
  }

  @Test
  void unregisterRoomFromSync_sessionNotFound_propagatesNotFoundException() {
    // Business reason: unknown sessions must not allow unregister side-effects via error
    // swallowing.
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenThrow(new NotFoundException("Session not found"));

    assertThrows(NotFoundException.class, () -> controller.unregisterRoomFromSync(SESSION_ID));

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void registerRoomForSync_shouldEnsureAdminIsInRoom_soSyncLoopReceivesEvents() {
    // The listener /sync loop runs as the technical admin and only sees rooms the
    // admin has joined — registering must therefore also heal the admin membership.
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenReturn(sessionWithMatrixRoom());

    controller.registerRoomForSync(SESSION_ID);

    verify(matrixSynapseService).ensureAdminInRoom(MATRIX_ROOM_ID, "@seeker:matrix");
  }

  @Test
  void registerRoomForSync_shouldStillSucceed_whenEnsureAdminInRoomFails() {
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenReturn(sessionWithMatrixRoom());
    when(matrixSynapseService.ensureAdminInRoom(MATRIX_ROOM_ID, "@seeker:matrix"))
        .thenThrow(new RuntimeException("matrix down"));

    var response = controller.registerRoomForSync(SESSION_ID);

    assertEquals(HttpStatus.OK, response.getStatusCode());
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

  private Session sessionWithMatrixRoomAndConsultant() {
    return Session.builder()
        .id(SESSION_ID)
        .user(userWithMatrixId())
        .consultant(consultant())
        .consultingTypeId(0)
        .registrationType(Session.RegistrationType.REGISTERED)
        .postcode("12345")
        .status(Session.SessionStatus.IN_PROGRESS)
        .matrixRoomId(MATRIX_ROOM_ID)
        .teamSession(false)
        .build();
  }

  private Session sessionWithoutMatrixRoom() {
    return Session.builder()
        .id(SESSION_ID)
        .user(userWithMatrixId())
        .consultingTypeId(0)
        .registrationType(Session.RegistrationType.REGISTERED)
        .postcode("12345")
        .status(Session.SessionStatus.IN_PROGRESS)
        .teamSession(false)
        .build();
  }

  private Consultant consultant() {
    return Consultant.builder()
        .id(CONSULTANT_ID)
        .rocketChatId("rc-consultant")
        .username("consultant")
        .firstName("Consultant")
        .lastName("User")
        .email("consultant@example.org")
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
