package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectSessionMatrixRoomServiceTest {

  private static final String ROOM_ID = "!room:oriso";
  private static final String CONSULTANT_MXID = "@con:oriso";
  private static final String USER_MXID = "@user:oriso";

  @InjectMocks private DirectSessionMatrixRoomService service;

  @Mock private SessionRoomGateway sessionRoomGateway;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private SessionService sessionService;
  @Mock private UserHelper userHelper;
  @Mock private ConsultantDisplayNameResolver consultantDisplayNameResolver;

  private Session session;
  private Consultant consultant;
  private User user;

  @BeforeEach
  void setUp() throws Exception {
    user = new User();
    user.setUserId("user-1");
    user.setUsername("asker");
    user.setMatrixUserId(USER_MXID);

    consultant = new Consultant();
    consultant.setId("con-1");
    consultant.setUsername("consultant");
    consultant.setFirstName("First");
    consultant.setLastName("Last");
    consultant.setMatrixUserId(CONSULTANT_MXID);

    session = new Session();
    session.setId(1L);
    session.setUser(user);

    when(sessionRoomGateway.createRoomAsUser(any(), any(), eq(CONSULTANT_MXID)))
        .thenReturn(ROOM_ID);
    when(sessionRoomGateway.loginAsUser(CONSULTANT_MXID)).thenReturn("con-tok");
    when(sessionRoomGateway.loginAsUser(USER_MXID)).thenReturn("user-tok");
    when(sessionRoomGateway.joinRoom(any(), any())).thenReturn(true);
  }

  // ---------------------------------------------------------------------------
  // provisionRoomForDirectSession — guard clauses
  // ---------------------------------------------------------------------------

  @Test
  void provisionRoomForDirectSession_Should_doNothing_When_sessionNull() throws Exception {
    service.provisionRoomForDirectSession(null, consultant);

    verify(sessionRoomGateway, never()).createRoomAsUser(any(), any(), any());
  }

  @Test
  void provisionRoomForDirectSession_Should_doNothing_When_consultantNull() throws Exception {
    service.provisionRoomForDirectSession(session, null);

    verify(sessionRoomGateway, never()).createRoomAsUser(any(), any(), any());
  }

  @Test
  void provisionRoomForDirectSession_Should_skip_When_sessionAlreadyHasRoom() throws Exception {
    session.setMatrixRoomId(ROOM_ID);

    service.provisionRoomForDirectSession(session, consultant);

    verify(sessionRoomGateway, never()).createRoomAsUser(any(), any(), any());
  }

  @Test
  void provisionRoomForDirectSession_Should_notSkip_When_matrixRoomIdBlank() throws Exception {
    session.setMatrixRoomId("   ");

    service.provisionRoomForDirectSession(session, consultant);

    verify(sessionRoomGateway).createRoomAsUser(any(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // ensureConsultantMatrixAccount (private, exercised via provisionRoomForDirectSession)
  // ---------------------------------------------------------------------------

  @Test
  void provisionRoomForDirectSession_Should_skipAccountCreation_When_consultantAlreadyHasMatrixId()
      throws Exception {
    service.provisionRoomForDirectSession(session, consultant);

    verify(sessionRoomGateway, never()).createUser(any(), any(), any());
  }

  @Test
  void provisionRoomForDirectSession_Should_createMatrixAccount_When_consultantHasNone()
      throws Exception {
    consultant.setMatrixUserId(null);
    when(userHelper.getRandomPassword()).thenReturn("pw");
    // ADR-002 §2: provisioned with the pseudonym, never the real name.
    when(consultantDisplayNameResolver.resolveMatrixDisplayName(consultant))
        .thenReturn("pseudonym");
    when(sessionRoomGateway.createUser("consultant", "pw", "pseudonym"))
        .thenReturn(CONSULTANT_MXID);
    when(sessionRoomGateway.createRoomAsUser(any(), any(), eq(CONSULTANT_MXID)))
        .thenReturn(ROOM_ID);

    service.provisionRoomForDirectSession(session, consultant);

    assertThat(consultant.getMatrixUserId()).isEqualTo(CONSULTANT_MXID);
    verify(consultantRepository).save(consultant);
  }

  @Test
  void provisionRoomForDirectSession_Should_continueSilently_When_createUserReturnsNoBody()
      throws Exception {
    consultant.setMatrixUserId(null);
    when(userHelper.getRandomPassword()).thenReturn("pw");
    when(sessionRoomGateway.createUser(any(), any(), any())).thenReturn(null);

    service.provisionRoomForDirectSession(session, consultant);

    // consultant still has no matrix id -> the "consultant has no Matrix account" guard fires
    verify(sessionService, never()).saveSession(any());
  }

  @Test
  void provisionRoomForDirectSession_Should_continueSilently_When_createUserThrows()
      throws Exception {
    consultant.setMatrixUserId(null);
    when(userHelper.getRandomPassword()).thenReturn("pw");
    when(sessionRoomGateway.createUser(any(), any(), any()))
        .thenThrow(new MatrixCreateUserException("boom"));

    service.provisionRoomForDirectSession(session, consultant);

    verify(sessionService, never()).saveSession(any());
  }

  // ---------------------------------------------------------------------------
  // Post-account guards
  // ---------------------------------------------------------------------------

  @Test
  void provisionRoomForDirectSession_Should_warnAndReturn_When_userIsNull() throws Exception {
    session.setUser(null);

    service.provisionRoomForDirectSession(session, consultant);

    verify(sessionRoomGateway, never()).createRoomAsUser(any(), any(), any());
  }

  @Test
  void provisionRoomForDirectSession_Should_warnAndReturn_When_userHasNoMatrixId()
      throws Exception {
    user.setMatrixUserId(null);

    service.provisionRoomForDirectSession(session, consultant);

    verify(sessionRoomGateway, never()).createRoomAsUser(any(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // Room creation guards
  // ---------------------------------------------------------------------------

  @Test
  void provisionRoomForDirectSession_Should_logErrorAndReturn_When_createRoomResponseNull()
      throws Exception {
    when(sessionRoomGateway.createRoomAsUser(any(), any(), eq(CONSULTANT_MXID))).thenReturn(null);

    service.provisionRoomForDirectSession(session, consultant);

    verify(sessionService, never()).saveSession(any());
  }

  @Test
  void provisionRoomForDirectSession_Should_logErrorAndReturn_When_createRoomBodyNull()
      throws Exception {
    when(sessionRoomGateway.createRoomAsUser(any(), any(), eq(CONSULTANT_MXID))).thenReturn(null);

    service.provisionRoomForDirectSession(session, consultant);

    verify(sessionService, never()).saveSession(any());
  }

  @Test
  void provisionRoomForDirectSession_Should_logErrorAndReturn_When_roomIdNull() throws Exception {
    when(sessionRoomGateway.createRoomAsUser(any(), any(), eq(CONSULTANT_MXID))).thenReturn(null);

    service.provisionRoomForDirectSession(session, consultant);

    verify(sessionService, never()).saveSession(any());
  }

  // ---------------------------------------------------------------------------
  // Happy path + token/invite/join branches
  // ---------------------------------------------------------------------------

  @Test
  void provisionRoomForDirectSession_Should_saveSessionAndInviteBoth_When_happyPath()
      throws Exception {
    service.provisionRoomForDirectSession(session, consultant);

    assertThat(session.getMatrixRoomId()).isEqualTo(ROOM_ID);
    verify(sessionService).saveSession(session);
    verify(sessionRoomGateway).inviteUser(ROOM_ID, USER_MXID, "con-tok");
    verify(sessionRoomGateway).joinRoom(ROOM_ID, "user-tok");
    verify(sessionRoomGateway).joinRoom(ROOM_ID, "con-tok");
  }

  @Test
  void provisionRoomForDirectSession_Should_ensureAdminMembership_When_roomCreated()
      throws Exception {
    // The notification listener syncs as the technical admin; without membership in the
    // freshly created room, message notifications for this session can never fire.
    service.provisionRoomForDirectSession(session, consultant);

    verify(sessionRoomGateway).ensureAdminInRoom(ROOM_ID, CONSULTANT_MXID);
  }

  @Test
  void provisionRoomForDirectSession_Should_logErrorAndReturn_When_consultantTokenNull()
      throws Exception {
    when(sessionRoomGateway.loginAsUser(CONSULTANT_MXID)).thenReturn(null);

    service.provisionRoomForDirectSession(session, consultant);

    verify(sessionRoomGateway, never()).inviteUser(any(), any(), any());
  }

  @Test
  void provisionRoomForDirectSession_Should_continue_When_inviteUserThrows() throws Exception {
    org.mockito.Mockito.doThrow(new MatrixInviteUserException("already in room"))
        .when(sessionRoomGateway)
        .inviteUser(eq(ROOM_ID), eq(USER_MXID), any());

    service.provisionRoomForDirectSession(session, consultant);

    // invite failure is swallowed — join attempts still proceed
    verify(sessionRoomGateway).joinRoom(ROOM_ID, "user-tok");
  }

  @Test
  void provisionRoomForDirectSession_Should_skipUserJoin_When_userTokenNull() throws Exception {
    when(sessionRoomGateway.loginAsUser(USER_MXID)).thenReturn(null);

    service.provisionRoomForDirectSession(session, consultant);

    verify(sessionRoomGateway, never()).joinRoom(eq(ROOM_ID), eq(null));
    verify(sessionRoomGateway).joinRoom(ROOM_ID, "con-tok");
  }

  @Test
  void provisionRoomForDirectSession_Should_logWarn_When_userJoinReturnsFalse() throws Exception {
    when(sessionRoomGateway.joinRoom(ROOM_ID, "user-tok")).thenReturn(false);

    service.provisionRoomForDirectSession(session, consultant);

    assertThat(session.getMatrixRoomId()).isEqualTo(ROOM_ID);
  }

  @Test
  void provisionRoomForDirectSession_Should_notLog_When_consultantJoinReturnsFalse()
      throws Exception {
    when(sessionRoomGateway.joinRoom(ROOM_ID, "con-tok")).thenReturn(false);

    service.provisionRoomForDirectSession(session, consultant);

    assertThat(session.getMatrixRoomId()).isEqualTo(ROOM_ID);
  }

  // ---------------------------------------------------------------------------
  // Outer catch-all
  // ---------------------------------------------------------------------------

  @Test
  void provisionRoomForDirectSession_Should_swallowUnexpectedException_When_createRoomThrows()
      throws Exception {
    when(sessionRoomGateway.createRoomAsUser(any(), any(), eq(CONSULTANT_MXID)))
        .thenThrow(new MatrixCreateRoomException("matrix down"));

    // must not propagate — the whole point is registration keeps working
    service.provisionRoomForDirectSession(session, consultant);

    assertThat(session.getMatrixRoomId()).isNull();
  }

  @Test
  void provisionRoomForDirectSession_Should_swallowUnexpectedException_When_saveSessionThrows()
      throws Exception {
    when(sessionService.saveSession(any())).thenThrow(new RuntimeException("db down"));

    service.provisionRoomForDirectSession(session, consultant);
    // no assertion beyond "did not throw" — the outer catch is the contract under test
  }
}
