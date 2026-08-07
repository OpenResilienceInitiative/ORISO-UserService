package de.caritas.cob.userservice.api.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Matrix-only integration coverage for the agency pre-assignment (holding) room provisioning path —
 * the room-creation / invite / membership orchestration that runs when an enquiry is created with
 * no existing Matrix room.
 *
 * <p>This class-under-test had ZERO test coverage. It is the enquiry-time room provisioner reached
 * via {@code CreateEnquiryMessageFacade.ensureMatrixRoomForEnquiry(...)}. These tests assert the
 * production orchestration deterministically (Mockito, no live Synapse): the agency service account
 * logs in, a room is created, the enquiry user is invited AND joins (membership), and the room id
 * is persisted on the session.
 *
 * <p>Complements PR #300 (which proved branch selection at the chat/adapter level and explicitly
 * deferred driving the enquiry room-provisioning path end-to-end). Test Quality Audit 2026-07-04 —
 * Matrix room provisioning phase 2 + C2.
 */
@ExtendWith(MockitoExtension.class)
class AgencyPreAssignmentRoomServiceTest {

  private static final Long AGENCY_ID = 4711L;
  private static final Long SESSION_ID = 99L;
  private static final String USER_MATRIX_ID = "@asker:oriso.org";
  private static final String AGENCY_MATRIX_ID = "@agency-svc:oriso.org";
  private static final String AGENCY_MATRIX_LOCALPART = "agency-svc";
  private static final String AGENCY_MATRIX_PASSWORD = "s3cret";
  private static final String AGENCY_TOKEN = "agency-access-token";
  private static final String USER_TOKEN = "user-access-token";
  private static final String NEW_ROOM_ID = "!newRoom:oriso.org";

  @Mock private AgencyMatrixCredentialClient matrixCredentialClient;
  @Mock private SessionRoomGateway sessionRoomGateway;
  @Mock private SessionService sessionService;
  @Mock private AgencySilentMembershipService agencySilentMembershipService;

  @InjectMocks private AgencyPreAssignmentRoomService underTest;

  private Session session;
  private User user;

  @BeforeEach
  void setUp() {
    user = new User();
    user.setUserId("user-1");
    user.setMatrixUserId(USER_MATRIX_ID);

    session = new Session();
    session.setId(SESSION_ID);
    session.setAgencyId(AGENCY_ID);
    session.setMatrixRoomId(null);
  }

  private AgencyMatrixCredentialsDTO validCredentials() {
    var creds = new AgencyMatrixCredentialsDTO();
    creds.setMatrixUserId(AGENCY_MATRIX_ID);
    creds.setMatrixPassword(AGENCY_MATRIX_PASSWORD);
    return creds;
  }

  private void stubHappyPathUntilRoomCreation() throws MatrixCreateRoomException {
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(validCredentials()));
    when(sessionRoomGateway.loginUser(AGENCY_MATRIX_LOCALPART, AGENCY_MATRIX_PASSWORD))
        .thenReturn(AGENCY_TOKEN);
    when(sessionRoomGateway.createRoom(anyString(), anyString(), eq(AGENCY_TOKEN)))
        .thenReturn(NEW_ROOM_ID);
  }

  @Test
  @DisplayName(
      "ensureHoldingRoom provisions the room end-to-end: create -> invite -> join -> persist")
  void ensureHoldingRoom_provisionsRoomInviteMembershipAndPersists() throws Exception {
    stubHappyPathUntilRoomCreation();
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoom(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);

    underTest.ensureHoldingRoom(session, user);

    // agency service account authenticated with the local part of its matrix id
    verify(sessionRoomGateway).loginUser(AGENCY_MATRIX_LOCALPART, AGENCY_MATRIX_PASSWORD);

    // room created with the agency token
    ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> aliasCaptor = ArgumentCaptor.forClass(String.class);
    verify(sessionRoomGateway)
        .createRoom(nameCaptor.capture(), aliasCaptor.capture(), eq(AGENCY_TOKEN));
    org.junit.jupiter.api.Assertions.assertTrue(
        nameCaptor.getValue().contains(String.valueOf(SESSION_ID)));
    org.junit.jupiter.api.Assertions.assertTrue(
        aliasCaptor.getValue().startsWith("agency_hold_" + SESSION_ID));

    // user invited to the new room and membership established via join
    verify(sessionRoomGateway).inviteUser(NEW_ROOM_ID, USER_MATRIX_ID, AGENCY_TOKEN);
    verify(sessionRoomGateway).loginAsUser(USER_MATRIX_ID);
    verify(sessionRoomGateway).joinRoom(NEW_ROOM_ID, USER_TOKEN);

    // room id persisted on the session
    verify(sessionService).saveSession(session);
    assertEquals(NEW_ROOM_ID, session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom is a no-op when the session already has a Matrix room")
  void ensureHoldingRoom_noOp_whenRoomAlreadyPresent() {
    session.setMatrixRoomId("!existing:oriso.org");

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(matrixCredentialClient, sessionRoomGateway, sessionService);
    assertEquals("!existing:oriso.org", session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom skips provisioning when the session has no agency")
  void ensureHoldingRoom_skips_whenNoAgency() {
    session.setAgencyId(null);

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(matrixCredentialClient, sessionRoomGateway, sessionService);
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom skips provisioning when the enquiry user has no Matrix id")
  void ensureHoldingRoom_skips_whenUserHasNoMatrixId() {
    user.setMatrixUserId("  ");

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(matrixCredentialClient, sessionRoomGateway, sessionService);
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom skips provisioning when the agency has no Matrix service account")
  void ensureHoldingRoom_skips_whenNoAgencyCredentials() {
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID)).thenReturn(Optional.empty());

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(sessionRoomGateway, sessionService);
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom aborts before login when agency credentials are incomplete")
  void ensureHoldingRoom_aborts_whenCredentialsIncomplete() {
    var creds = new AgencyMatrixCredentialsDTO();
    creds.setMatrixUserId(AGENCY_MATRIX_ID);
    creds.setMatrixPassword("  ");
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID)).thenReturn(Optional.of(creds));

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(sessionRoomGateway, sessionService);
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom does not persist a room when the agency login fails")
  void ensureHoldingRoom_doesNotPersist_whenAgencyLoginFails() {
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(validCredentials()));
    when(sessionRoomGateway.loginUser(AGENCY_MATRIX_LOCALPART, AGENCY_MATRIX_PASSWORD))
        .thenReturn("  ");

    underTest.ensureHoldingRoom(session, user);

    verify(sessionService, never()).saveSession(any());
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom does not persist a room when create returns an empty body")
  void ensureHoldingRoom_doesNotPersist_whenCreateRoomReturnsEmptyBody() throws Exception {
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(validCredentials()));
    when(sessionRoomGateway.loginUser(AGENCY_MATRIX_LOCALPART, AGENCY_MATRIX_PASSWORD))
        .thenReturn(AGENCY_TOKEN);
    when(sessionRoomGateway.createRoom(anyString(), anyString(), eq(AGENCY_TOKEN)))
        .thenReturn("  ");

    underTest.ensureHoldingRoom(session, user);

    verify(sessionRoomGateway, never()).inviteUser(anyString(), anyString(), anyString());
    verify(sessionService, never()).saveSession(any());
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom swallows a MatrixCreateRoomException without persisting")
  void ensureHoldingRoom_swallowsCreateRoomException() throws Exception {
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(validCredentials()));
    when(sessionRoomGateway.loginUser(AGENCY_MATRIX_LOCALPART, AGENCY_MATRIX_PASSWORD))
        .thenReturn(AGENCY_TOKEN);
    when(sessionRoomGateway.createRoom(anyString(), anyString(), eq(AGENCY_TOKEN)))
        .thenThrow(new MatrixCreateRoomException("boom"));

    underTest.ensureHoldingRoom(session, user);

    verify(sessionService, never()).saveSession(any());
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName(
      "FE#811/#199: the agency's consultants join the fresh room BEFORE the asker is invited")
  void ensureHoldingRoom_joinsAgencyConsultantsBeforeAsker() throws Exception {
    stubHappyPathUntilRoomCreation();
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoom(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);

    underTest.ensureHoldingRoom(session, user);

    // Membership must be established while the room is still empty: a consultant joined after the
    // first message holds no Megolm key for it (FE#811 / ADR-002 §1). The asker's client can see
    // the room via /sync from the moment of their invite, so the department must already be in by
    // then — consultants first, asker second, persist last.
    var inOrder =
        org.mockito.Mockito.inOrder(
            sessionRoomGateway, agencySilentMembershipService, sessionService);
    inOrder.verify(sessionRoomGateway).createRoom(anyString(), anyString(), eq(AGENCY_TOKEN));
    inOrder
        .verify(agencySilentMembershipService)
        .joinAgencyConsultants(AGENCY_ID, NEW_ROOM_ID, AGENCY_TOKEN);
    inOrder.verify(sessionRoomGateway).inviteUser(NEW_ROOM_ID, USER_MATRIX_ID, AGENCY_TOKEN);
    inOrder.verify(sessionRoomGateway).joinRoom(NEW_ROOM_ID, USER_TOKEN);
    inOrder.verify(sessionService).saveSession(session);
  }

  @Test
  @DisplayName("a department without a single joinable consultant still gets the asker their room")
  void ensureHoldingRoom_provisionsRoomForAsker_whenNoConsultantJoined() throws Exception {
    stubHappyPathUntilRoomCreation();
    // AgencySilentMembershipService reports "nobody joined" (no consultants, or none usable).
    when(agencySilentMembershipService.joinAgencyConsultants(AGENCY_ID, NEW_ROOM_ID, AGENCY_TOKEN))
        .thenReturn(0);
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoom(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);

    underTest.ensureHoldingRoom(session, user);

    verify(sessionRoomGateway).inviteUser(NEW_ROOM_ID, USER_MATRIX_ID, AGENCY_TOKEN);
    verify(sessionRoomGateway).joinRoom(NEW_ROOM_ID, USER_TOKEN);
    verify(sessionService).saveSession(session);
    assertEquals(NEW_ROOM_ID, session.getMatrixRoomId());
  }

  @Test
  @DisplayName("an unexpected department-membership failure never costs the asker their enquiry")
  void ensureHoldingRoom_provisionsRoomForAsker_whenSilentMembershipThrows() throws Exception {
    stubHappyPathUntilRoomCreation();
    // Department membership runs BEFORE the asker's invite now, so a blow-up here (e.g. the
    // consultant lookup) must be contained — it is a side effect, not a precondition.
    when(agencySilentMembershipService.joinAgencyConsultants(AGENCY_ID, NEW_ROOM_ID, AGENCY_TOKEN))
        .thenThrow(new RuntimeException("consultant lookup failed"));
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoom(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);

    underTest.ensureHoldingRoom(session, user);

    verify(sessionRoomGateway).inviteUser(NEW_ROOM_ID, USER_MATRIX_ID, AGENCY_TOKEN);
    verify(sessionRoomGateway).joinRoom(NEW_ROOM_ID, USER_TOKEN);
    verify(sessionService).saveSession(session);
    assertEquals(NEW_ROOM_ID, session.getMatrixRoomId());
  }

  @Test
  @DisplayName("a directly addressed enquiry is not fanned out to the whole department")
  void ensureHoldingRoom_skipsDepartment_whenConsultantDirectlySet() throws Exception {
    stubHappyPathUntilRoomCreation();
    session.setIsConsultantDirectlySet(true);

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(agencySilentMembershipService);
    // the room itself is still provisioned for the asker
    assertEquals(NEW_ROOM_ID, session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom is null-safe for null session or user")
  void ensureHoldingRoom_nullSafe() {
    underTest.ensureHoldingRoom(null, user);
    underTest.ensureHoldingRoom(session, null);

    verifyNoInteractions(matrixCredentialClient, sessionRoomGateway, sessionService);
  }
}
