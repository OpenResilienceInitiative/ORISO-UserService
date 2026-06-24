package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.matrix.RedisMessageMirrorService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class MatrixMessageControllerTest {

  private static final Long SESSION_ID = 42L;
  private static final String USER_ID = "user-id";
  private static final String USERNAME = "seeker";
  private static final String MATRIX_ROOM_ID = "!room:matrix";
  private static final String MATRIX_USER_ID = "@seeker:matrix";

  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private SessionService sessionService;
  @Mock private ChatService chatService;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private ConsultantService consultantService;
  @Mock private UserService userService;
  @Mock private AgencyMatrixCredentialClient matrixCredentialClient;
  @Mock private ChatPermissionVerifier chatPermissionVerifier;

  private MatrixMessageController controller;

  @BeforeEach
  void setUp() {
    controller =
        new MatrixMessageController(
            matrixSynapseService,
            sessionService,
            chatService,
            authenticatedUser,
            consultantService,
            userService,
            matrixCredentialClient,
            chatPermissionVerifier,
            Optional.<RedisMessageMirrorService>empty());
  }

  @Test
  void sendMessage_ShouldThrowForbiddenBeforeMatrixCall_WhenSessionAccessIsDenied() {
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenThrow(new ForbiddenException("No permission"));

    assertThrows(
        ForbiddenException.class,
        () -> controller.sendMessage(SESSION_ID, Map.of("message", "blocked")));

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void getMessages_ShouldThrowForbiddenBeforeMatrixCall_WhenSessionAccessIsDenied() {
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(sessionWithMatrixRoom()));
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenThrow(new ForbiddenException("No permission"));

    assertThrows(ForbiddenException.class, () -> controller.getMessages(SESSION_ID));

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void getMessages_ShouldThrowForbiddenBeforeMatrixCall_WhenChatAccessIsDenied() {
    var chat = chatWithMatrixRoom();
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.empty());
    when(chatService.getChat(SESSION_ID)).thenReturn(Optional.of(chat));
    doThrow(new ForbiddenException("No permission"))
        .when(chatPermissionVerifier)
        .verifyPermissionForChat(chat);

    assertThrows(ForbiddenException.class, () -> controller.getMessages(SESSION_ID));

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void sendMessage_ShouldSendMatrixMessage_WhenSessionAccessIsAllowed() {
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenReturn(sessionWithMatrixRoom());
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(authenticatedUser.getRoles()).thenReturn(Set.of("user"));
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(userService.getUser(USER_ID)).thenReturn(Optional.of(userWithMatrixId()));
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn("matrix-token");
    when(matrixSynapseService.sendMessage(MATRIX_ROOM_ID, "hello", "matrix-token"))
        .thenReturn(Map.of("event_id", "$event"));

    var response = controller.sendMessage(SESSION_ID, Map.of("message", "hello"));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(matrixSynapseService).sendMessage(MATRIX_ROOM_ID, "hello", "matrix-token");
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

  private Chat chatWithMatrixRoom() {
    return Chat.builder()
        .id(SESSION_ID)
        .topic("group")
        .initialStartDate(LocalDateTime.now())
        .startDate(LocalDateTime.now())
        .duration(60)
        .matrixRoomId(MATRIX_ROOM_ID)
        .build();
  }

  private User userWithMatrixId() {
    return User.builder()
        .userId(USER_ID)
        .username(USERNAME)
        .email("seeker@example.org")
        .matrixUserId(MATRIX_USER_ID)
        .languageFormal(false)
        .build();
  }
}
