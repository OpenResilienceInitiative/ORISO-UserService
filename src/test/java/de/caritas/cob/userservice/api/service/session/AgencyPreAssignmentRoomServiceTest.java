package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomRateLimitedException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixJoinRateLimitedException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixJoinTemporarilyUnavailableException;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomAttempt.RequesterJoinPlan;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.session.AgencyEnquiryRoomAttempts.RequesterJoinClaim;
import de.caritas.cob.userservice.api.service.session.AgencyEnquiryRoomAttempts.RequesterJoinRecovery;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService.InitialRoomContact;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

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
  private static final String USER_MATRIX_ID = "@asker:example.org";
  private static final String AGENCY_MATRIX_ID = "@agency-svc:example.org";
  private static final String AGENCY_TOKEN = "agency-access-token";
  private static final String USER_TOKEN = "user-access-token";
  private static final String NEW_ROOM_ID = "!newRoom:example.org";

  @Mock private AgencyMatrixCredentialClient matrixCredentialClient;
  @Mock private SessionRoomGateway sessionRoomGateway;
  @Mock private SessionService sessionService;
  @Mock private AgencyEnquiryRoomPublication publication;
  @Mock private AgencySilentMembershipService agencySilentMembershipService;
  @Mock private AgencyDepartmentMembershipBackfill departmentMembershipBackfill;
  @Mock private MatrixRateLimitPause rateLimitPause;
  @Mock private AgencyEnquiryRoomAttempts roomAttempts;

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
    lenient()
        .when(roomAttempts.recordConfirmedInvitation(any(), any()))
        .thenAnswer(
            call ->
                new RequesterJoinClaim(
                    call.getArgument(0), NEW_ROOM_ID, "first-claim", call.getArgument(1)));
    lenient()
        .when(roomAttempts.claimRequesterJoin(session, user))
        .thenReturn(new RequesterJoinRecovery(false, Optional.empty()));
  }

  private AgencyMatrixCredentialsDTO validCredentials() {
    var creds = new AgencyMatrixCredentialsDTO();
    creds.setMatrixUserId(AGENCY_MATRIX_ID);
    return creds;
  }

  private void stubHappyPathUntilRoomCreation() throws MatrixCreateRoomException {
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(validCredentials()));
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(AGENCY_TOKEN);
    when(sessionRoomGateway.createRoom(anyString(), anyString(), eq(AGENCY_TOKEN)))
        .thenReturn(NEW_ROOM_ID);
  }

  private void stubPublishedRoom(List<InitialRoomContact> contacts) {
    when(publication.publishPrepared(eq(session), any(), eq(contacts)))
        .thenReturn(Optional.of(NEW_ROOM_ID));
  }

  @Test
  void ensureHoldingRoomSupportsIdentityOnlyAgencyResponse() throws Exception {
    var identity = new AgencyMatrixCredentialsDTO();
    identity.setMatrixUserId(AGENCY_MATRIX_ID);
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(identity));
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(AGENCY_TOKEN);
    when(sessionRoomGateway.createRoom(anyString(), anyString(), eq(AGENCY_TOKEN)))
        .thenReturn(NEW_ROOM_ID);
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoomOnce(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);

    stubPublishedRoom(List.of());

    underTest.ensureHoldingRoom(session, user);

    assertEquals(NEW_ROOM_ID, session.getMatrixRoomId());
    verify(publication).publishPrepared(eq(session), any(), eq(List.of()));
    verify(sessionRoomGateway, never()).loginUser(anyString(), anyString());
  }

  @Test
  @DisplayName(
      "ensureHoldingRoom provisions the room end-to-end: create -> invite -> join -> persist")
  void ensureHoldingRoom_provisionsRoomInviteMembershipAndPersists() throws Exception {
    stubHappyPathUntilRoomCreation();
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoomOnce(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);

    stubPublishedRoom(List.of());

    underTest.ensureHoldingRoom(session, user);

    // Agency identity is impersonated without a reusable password.
    verify(sessionRoomGateway).loginAsUser(AGENCY_MATRIX_ID);

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
    verify(sessionRoomGateway).joinRoomOnce(NEW_ROOM_ID, USER_TOKEN);

    // room id persisted on the session
    verify(publication).publishPrepared(eq(session), any(), eq(List.of()));
    assertEquals(NEW_ROOM_ID, session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom is a no-op when the session already has a Matrix room")
  void ensureHoldingRoom_noOp_whenRoomAlreadyPresent() {
    session.setMatrixRoomId("!existing:example.org");

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(matrixCredentialClient, sessionRoomGateway, publication);
    assertEquals("!existing:example.org", session.getMatrixRoomId());
  }

  @Test
  void ensureHoldingRoomAdoptsARoomBoundSinceTheCallerReadTheSession() throws Exception {
    when(roomAttempts.claimRequesterJoin(session, user))
        .thenReturn(
            new RequesterJoinRecovery(
                true, Optional.empty(), Optional.of("!bound-meanwhile:example.org")));

    underTest.ensureHoldingRoom(session, user);

    assertEquals("!bound-meanwhile:example.org", session.getMatrixRoomId());
    verifyNoInteractions(matrixCredentialClient, publication);
    verify(sessionRoomGateway, never()).createRoom(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("ensureHoldingRoom skips provisioning when the session has no agency")
  void ensureHoldingRoom_skips_whenNoAgency() {
    session.setAgencyId(null);

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(matrixCredentialClient, sessionRoomGateway, publication);
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom skips provisioning when the enquiry user has no Matrix id")
  void ensureHoldingRoom_skips_whenUserHasNoMatrixId() {
    user.setMatrixUserId("  ");

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(matrixCredentialClient, sessionRoomGateway, publication);
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom skips provisioning when the agency has no Matrix service account")
  void ensureHoldingRoom_skips_whenNoAgencyCredentials() {
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID)).thenReturn(Optional.empty());

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(sessionRoomGateway, publication);
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom aborts before login when agency credentials are incomplete")
  void ensureHoldingRoom_aborts_whenCredentialsIncomplete() {
    var creds = new AgencyMatrixCredentialsDTO();
    creds.setMatrixUserId("  ");
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID)).thenReturn(Optional.of(creds));

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(sessionRoomGateway, publication);
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom does not persist a room when the agency login fails")
  void ensureHoldingRoom_doesNotPersist_whenAgencyLoginFails() {
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(validCredentials()));
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn("  ");

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(publication);
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom does not persist a room when create returns an empty body")
  void ensureHoldingRoom_doesNotPersist_whenCreateRoomReturnsEmptyBody() throws Exception {
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(validCredentials()));
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(AGENCY_TOKEN);
    when(sessionRoomGateway.createRoom(anyString(), anyString(), eq(AGENCY_TOKEN)))
        .thenReturn("  ");

    underTest.ensureHoldingRoom(session, user);

    verify(sessionRoomGateway, never()).inviteUser(anyString(), anyString(), anyString());
    verifyNoInteractions(publication);
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom swallows a MatrixCreateRoomException without persisting")
  void ensureHoldingRoom_swallowsCreateRoomException() throws Exception {
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(validCredentials()));
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(AGENCY_TOKEN);
    when(sessionRoomGateway.createRoom(anyString(), anyString(), eq(AGENCY_TOKEN)))
        .thenThrow(new MatrixCreateRoomException("boom"));

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(publication);
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName(
      "US#1737: a rate-limited room creation is retried after the server's delay, then persisted")
  void ensureHoldingRoom_retriesRateLimitedRoomCreation() throws Exception {
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(validCredentials()));
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(AGENCY_TOKEN);
    when(sessionRoomGateway.createRoom(anyString(), anyString(), eq(AGENCY_TOKEN)))
        .thenThrow(new MatrixCreateRoomRateLimitedException("limited", 3_256L))
        .thenReturn(NEW_ROOM_ID);
    when(rateLimitPause.pause(3_256L)).thenReturn(true);
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoomOnce(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);
    stubPublishedRoom(List.of());

    underTest.ensureHoldingRoom(session, user);

    verify(rateLimitPause).pause(3_256L);
    verify(sessionRoomGateway, times(2)).createRoom(anyString(), anyString(), eq(AGENCY_TOKEN));
    assertEquals(NEW_ROOM_ID, session.getMatrixRoomId());
    verify(publication).publishPrepared(eq(session), any(), eq(List.of()));
  }

  @Test
  @DisplayName("US#1737: the wait before retrying room creation is capped")
  void ensureHoldingRoom_capsTheWaitBeforeRetryingRoomCreation() throws Exception {
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(validCredentials()));
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(AGENCY_TOKEN);
    when(sessionRoomGateway.createRoom(anyString(), anyString(), eq(AGENCY_TOKEN)))
        .thenThrow(new MatrixCreateRoomRateLimitedException("limited", 600_000L));
    when(rateLimitPause.pause(AgencyPreAssignmentRoomService.MAX_WAIT_BEFORE_CREATE_ROOM_RETRY_MS))
        .thenReturn(true);

    underTest.ensureHoldingRoom(session, user);

    verify(sessionRoomGateway, times(AgencyPreAssignmentRoomService.CREATE_ROOM_MAX_ATTEMPTS))
        .createRoom(anyString(), anyString(), eq(AGENCY_TOKEN));
    verify(rateLimitPause, times(AgencyPreAssignmentRoomService.CREATE_ROOM_MAX_ATTEMPTS - 1))
        .pause(AgencyPreAssignmentRoomService.MAX_WAIT_BEFORE_CREATE_ROOM_RETRY_MS);
    verifyNoInteractions(publication);
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName("US#1737: the repair provisions the missing room of an open enquiry")
  void repairHoldingRoom_provisionsTheMissingRoom() throws Exception {
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(new Session()));
    stubHappyPathUntilRoomCreation();
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoomOnce(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);
    stubPublishedRoom(List.of());

    underTest.repairHoldingRoom(session, user);

    assertEquals(NEW_ROOM_ID, session.getMatrixRoomId());
    verify(publication).publishPrepared(eq(session), any(), eq(List.of()));
  }

  @Test
  @DisplayName("US#1737: the repair adopts a room provisioned meanwhile instead of a second one")
  void repairHoldingRoom_adoptsARoomProvisionedMeanwhile() throws Exception {
    var current = new Session();
    current.setMatrixRoomId("!already:matrix");
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(current));

    underTest.repairHoldingRoom(session, user);

    assertEquals("!already:matrix", session.getMatrixRoomId());
    verify(sessionRoomGateway, never()).createRoom(anyString(), anyString(), anyString());
    verifyNoInteractions(publication);
  }

  @Test
  @DisplayName("US#1737: a second repair of the same session within the cooldown does nothing")
  void repairHoldingRoom_isNotRepeatedWithinTheCooldown() throws Exception {
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(new Session()));
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(validCredentials()));
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(AGENCY_TOKEN);
    when(sessionRoomGateway.createRoom(anyString(), anyString(), eq(AGENCY_TOKEN)))
        .thenThrow(new MatrixCreateRoomException("refused"));
    underTest.clock =
        java.time.Clock.fixed(
            java.time.Instant.parse("2026-11-21T10:00:00Z"), java.time.ZoneOffset.UTC);

    underTest.repairHoldingRoom(session, user);
    underTest.repairHoldingRoom(session, user);

    verify(sessionRoomGateway, times(1)).createRoom(anyString(), anyString(), eq(AGENCY_TOKEN));

    underTest.clock =
        java.time.Clock.fixed(
            java.time.Instant.parse("2026-11-21T10:00:00Z")
                .plus(AgencyPreAssignmentRoomService.REPAIR_COOLDOWN)
                .plusSeconds(1),
            java.time.ZoneOffset.UTC);
    underTest.repairHoldingRoom(session, user);

    verify(sessionRoomGateway, times(2)).createRoom(anyString(), anyString(), eq(AGENCY_TOKEN));
  }

  @Test
  @DisplayName("US#1737: an interrupted wait stops the retries and provisions nothing")
  void ensureHoldingRoom_stopsRetryingWhenTheWaitIsInterrupted() throws Exception {
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(validCredentials()));
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(AGENCY_TOKEN);
    when(sessionRoomGateway.createRoom(anyString(), anyString(), eq(AGENCY_TOKEN)))
        .thenThrow(new MatrixCreateRoomRateLimitedException("limited", 3_256L))
        .thenReturn(NEW_ROOM_ID);
    when(rateLimitPause.pause(3_256L)).thenReturn(false);

    underTest.ensureHoldingRoom(session, user);

    verify(sessionRoomGateway, times(1)).createRoom(anyString(), anyString(), eq(AGENCY_TOKEN));
    verifyNoInteractions(publication);
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName("US#1737: a repair already running for the session is not started a second time")
  void repairHoldingRoom_skipsASecondRepairWhileOneIsRunning() throws Exception {
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(new Session()));
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(validCredentials()));
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(AGENCY_TOKEN);
    var concurrent = new Session();
    concurrent.setId(SESSION_ID);
    concurrent.setAgencyId(AGENCY_ID);
    // While the first repair is creating the room, a second request for the same session arrives.
    when(sessionRoomGateway.createRoom(anyString(), anyString(), eq(AGENCY_TOKEN)))
        .thenAnswer(
            invocation -> {
              underTest.repairHoldingRoom(concurrent, user);
              throw new MatrixCreateRoomException("refused");
            });

    underTest.repairHoldingRoom(session, user);

    verify(sessionRoomGateway, times(1)).createRoom(anyString(), anyString(), eq(AGENCY_TOKEN));
  }

  @Test
  @DisplayName("US#1737: a refused room creation that is not a rate limit is not retried")
  void ensureHoldingRoom_doesNotRetryARefusedRoomCreation() throws Exception {
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(validCredentials()));
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_ID)).thenReturn(AGENCY_TOKEN);
    when(sessionRoomGateway.createRoom(anyString(), anyString(), eq(AGENCY_TOKEN)))
        .thenThrow(new MatrixCreateRoomException("forbidden"));

    underTest.ensureHoldingRoom(session, user);

    verify(sessionRoomGateway, times(1)).createRoom(anyString(), anyString(), eq(AGENCY_TOKEN));
    verifyNoInteractions(rateLimitPause);
  }

  @Test
  @DisplayName(
      "FE#811/#199: the agency's consultants join the fresh room BEFORE the asker is invited")
  void ensureHoldingRoom_joinsAgencyConsultantsBeforeAsker() throws Exception {
    stubHappyPathUntilRoomCreation();
    var initialMember = new AgencySilentMembershipService.InitialRoomContact("c", "@c:example.org");
    var contacts = java.util.List.of(initialMember);
    when(agencySilentMembershipService.joinAgencyConsultantsWithoutWaiting(
            eq(AGENCY_ID), eq(NEW_ROOM_ID), eq(AGENCY_TOKEN), any()))
        .thenReturn(
            new AgencySilentMembershipService.DepartmentFanOut(
                1, java.util.List.of(), 0L, contacts));
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoomOnce(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);

    stubPublishedRoom(contacts);

    underTest.ensureHoldingRoom(session, user);

    // Membership must be established while the room is still empty: a consultant joined after the
    // first message holds no Megolm key for it (FE#811 / ADR-002 §1). The asker's client can see
    // the room via /sync from the moment of their invite, so the department must already be in by
    // then — consultants first, asker second, persist last.
    var inOrder =
        org.mockito.Mockito.inOrder(sessionRoomGateway, agencySilentMembershipService, publication);
    inOrder.verify(sessionRoomGateway).createRoom(anyString(), anyString(), eq(AGENCY_TOKEN));
    inOrder
        .verify(agencySilentMembershipService)
        .joinAgencyConsultantsWithoutWaiting(
            eq(AGENCY_ID), eq(NEW_ROOM_ID), eq(AGENCY_TOKEN), any());
    inOrder.verify(sessionRoomGateway).inviteUser(NEW_ROOM_ID, USER_MATRIX_ID, AGENCY_TOKEN);
    inOrder.verify(sessionRoomGateway).joinRoomOnce(NEW_ROOM_ID, USER_TOKEN);
    inOrder.verify(publication).publishPrepared(eq(session), any(), eq(contacts));
  }

  @Test
  @DisplayName("a department without a single joinable consultant still gets the asker their room")
  void ensureHoldingRoom_provisionsRoomForAsker_whenNoConsultantJoined() throws Exception {
    stubHappyPathUntilRoomCreation();
    // AgencySilentMembershipService reports "nobody joined" (no consultants, or none usable).
    when(agencySilentMembershipService.joinAgencyConsultantsWithoutWaiting(
            eq(AGENCY_ID), eq(NEW_ROOM_ID), eq(AGENCY_TOKEN), any()))
        .thenReturn(AgencySilentMembershipService.DepartmentFanOut.NONE);
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoomOnce(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);

    stubPublishedRoom(List.of());

    underTest.ensureHoldingRoom(session, user);

    verify(sessionRoomGateway).inviteUser(NEW_ROOM_ID, USER_MATRIX_ID, AGENCY_TOKEN);
    verify(sessionRoomGateway).joinRoomOnce(NEW_ROOM_ID, USER_TOKEN);
    verify(publication).publishPrepared(eq(session), any(), eq(List.of()));
    assertEquals(NEW_ROOM_ID, session.getMatrixRoomId());
  }

  @Test
  @DisplayName("an unexpected department-membership failure never costs the asker their enquiry")
  void ensureHoldingRoom_provisionsRoomForAsker_whenSilentMembershipThrows() throws Exception {
    stubHappyPathUntilRoomCreation();
    // Department membership runs BEFORE the asker's invite now, so a blow-up here (e.g. the
    // consultant lookup) must be contained — it is a side effect, not a precondition.
    when(agencySilentMembershipService.joinAgencyConsultantsWithoutWaiting(
            eq(AGENCY_ID), eq(NEW_ROOM_ID), eq(AGENCY_TOKEN), any()))
        .thenThrow(new RuntimeException("consultant lookup failed"));
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoomOnce(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);

    stubPublishedRoom(List.of());

    underTest.ensureHoldingRoom(session, user);

    verify(sessionRoomGateway).inviteUser(NEW_ROOM_ID, USER_MATRIX_ID, AGENCY_TOKEN);
    verify(sessionRoomGateway).joinRoomOnce(NEW_ROOM_ID, USER_TOKEN);
    verify(publication).publishPrepared(eq(session), any(), eq(List.of()));
    assertEquals(NEW_ROOM_ID, session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom does not persist when the asker invite fails")
  void ensureHoldingRoom_doesNotPersist_whenAskerInviteFails() throws Exception {
    stubHappyPathUntilRoomCreation();
    org.mockito.Mockito.doThrow(new MatrixInviteUserException("rate limited"))
        .when(sessionRoomGateway)
        .inviteUser(NEW_ROOM_ID, USER_MATRIX_ID, AGENCY_TOKEN);

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(publication);
    assertNull(session.getMatrixRoomId());
  }

  @Test
  @DisplayName("ensureHoldingRoom does not persist when the asker cannot join after invite")
  void ensureHoldingRoom_doesNotPersist_whenAskerJoinFails() throws Exception {
    stubHappyPathUntilRoomCreation();
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoomOnce(NEW_ROOM_ID, USER_TOKEN)).thenReturn(false);

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(publication);
    assertNull(session.getMatrixRoomId());
  }

  @Test
  void shortJoinThrottleWaitsInFullThenJoinsTheSameRoom() throws Exception {
    stubHappyPathUntilRoomCreation();
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoomOnce(NEW_ROOM_ID, USER_TOKEN))
        .thenThrow(new MatrixJoinRateLimitedException("fixture", 1800L))
        .thenReturn(true);
    var claim = recoveryClaim();
    when(roomAttempts.recordCreated(session, NEW_ROOM_ID)).thenReturn(claim.attemptId());
    var initialClaim =
        new RequesterJoinClaim(claim.attemptId(), claim.roomId(), "first-claim", claim.plan());
    when(roomAttempts.claimRequesterJoin(session, user))
        .thenReturn(
            new RequesterJoinRecovery(false, Optional.empty()),
            new RequesterJoinRecovery(true, Optional.of(claim)));
    when(rateLimitPause.pause(1800L)).thenReturn(true);
    stubPublishedRoom(List.of());

    underTest.ensureHoldingRoom(session, user);

    var order =
        org.mockito.Mockito.inOrder(sessionRoomGateway, roomAttempts, rateLimitPause, publication);
    order.verify(sessionRoomGateway).joinRoomOnce(NEW_ROOM_ID, USER_TOKEN);
    order.verify(roomAttempts).deferRequesterJoin(initialClaim, 1800L);
    order.verify(rateLimitPause).pause(1800L);
    order.verify(roomAttempts).claimRequesterJoin(session, user);
    order.verify(sessionRoomGateway).joinRoomOnce(NEW_ROOM_ID, USER_TOKEN);
    order.verify(publication).publishPrepared(session, claim, List.of());
    verify(sessionRoomGateway).createRoom(anyString(), anyString(), eq(AGENCY_TOKEN));
    verify(sessionRoomGateway).inviteUser(NEW_ROOM_ID, USER_MATRIX_ID, AGENCY_TOKEN);
  }

  @Test
  void longJoinThrottleDefersAndReopenResumesTheOriginalRoom() throws Exception {
    stubHappyPathUntilRoomCreation();
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoomOnce(NEW_ROOM_ID, USER_TOKEN))
        .thenThrow(new MatrixJoinRateLimitedException("fixture", 19_000L))
        .thenReturn(true);
    var claim = recoveryClaim();
    when(roomAttempts.recordCreated(session, NEW_ROOM_ID)).thenReturn(claim.attemptId());
    var initialClaim =
        new RequesterJoinClaim(claim.attemptId(), claim.roomId(), "first-claim", claim.plan());
    when(roomAttempts.claimRequesterJoin(session, user))
        .thenReturn(
            new RequesterJoinRecovery(false, Optional.empty()),
            new RequesterJoinRecovery(true, Optional.empty()),
            new RequesterJoinRecovery(true, Optional.of(claim)));
    stubPublishedRoom(List.of());

    underTest.ensureHoldingRoom(session, user);
    underTest.repairHoldingRoom(session, user);

    verify(roomAttempts).deferRequesterJoin(initialClaim, 19_000L);
    verify(sessionRoomGateway, times(1)).joinRoomOnce(NEW_ROOM_ID, USER_TOKEN);
    verifyNoInteractions(publication, rateLimitPause);
    assertNull(session.getMatrixRoomId());

    underTest.repairHoldingRoom(session, user);

    verify(publication).publishPrepared(session, claim, List.of());
    verify(sessionRoomGateway, times(2)).joinRoomOnce(NEW_ROOM_ID, USER_TOKEN);
    verify(sessionRoomGateway).createRoom(anyString(), anyString(), eq(AGENCY_TOKEN));
    verify(sessionRoomGateway).inviteUser(NEW_ROOM_ID, USER_MATRIX_ID, AGENCY_TOKEN);
    assertEquals(NEW_ROOM_ID, session.getMatrixRoomId());
  }

  @Test
  void temporaryFailureOfResumedJoinKeepsTheExactRoomAndClaim() throws Exception {
    var claim = recoveryClaim();
    var nextClaim =
        new RequesterJoinClaim(claim.attemptId(), claim.roomId(), "next-claim", claim.plan());
    when(roomAttempts.claimRequesterJoin(session, user))
        .thenReturn(
            new RequesterJoinRecovery(true, Optional.of(claim)),
            new RequesterJoinRecovery(true, Optional.of(nextClaim)));
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoomOnce(NEW_ROOM_ID, USER_TOKEN))
        .thenThrow(new MatrixJoinTemporarilyUnavailableException())
        .thenReturn(true);
    stubPublishedRoom(List.of());

    underTest.ensureHoldingRoom(session, user);

    verify(roomAttempts)
        .deferRequesterJoin(claim, AgencyPreAssignmentRoomService.REPAIR_COOLDOWN.toMillis());
    verify(roomAttempts, never()).finishRequesterJoin(any());
    verifyNoInteractions(matrixCredentialClient, publication, rateLimitPause);
    assertNull(session.getMatrixRoomId());

    underTest.ensureHoldingRoom(session, user);

    verify(publication).publishPrepared(session, nextClaim, List.of());
    verify(sessionRoomGateway, times(2)).joinRoomOnce(NEW_ROOM_ID, USER_TOKEN);
    verifyNoInteractions(matrixCredentialClient);
    assertEquals(NEW_ROOM_ID, session.getMatrixRoomId());
  }

  private RequesterJoinClaim recoveryClaim() {
    return new RequesterJoinClaim(
        81L,
        NEW_ROOM_ID,
        "retry-claim",
        new RequesterJoinPlan(
            "user-1", USER_MATRIX_ID, AGENCY_MATRIX_ID, false, List.of(), List.of()));
  }

  @Test
  @DisplayName("a directly addressed enquiry is not fanned out to the whole department")
  void ensureHoldingRoom_skipsDepartment_whenConsultantDirectlySet() throws Exception {
    stubHappyPathUntilRoomCreation();
    session.setIsConsultantDirectlySet(true);
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoomOnce(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);

    stubPublishedRoom(List.of());

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

    verifyNoInteractions(matrixCredentialClient, sessionRoomGateway, publication);
  }

  @Test
  @DisplayName(
      "registration does not wait for a rate-limited department: the rest is queued after the asker")
  void ensureHoldingRoom_defersThrottledDepartmentToBackground() throws Exception {
    stubHappyPathUntilRoomCreation();
    var deferred =
        java.util.stream.IntStream.range(0, 54)
            .mapToObj(
                i -> {
                  var consultant = new de.caritas.cob.userservice.api.model.Consultant();
                  consultant.setId("consultant-" + i);
                  return consultant;
                })
            .toList();
    var contacts =
        List.of(
            new AgencySilentMembershipService.InitialRoomContact("c-initial", "@c:example.org"));
    when(agencySilentMembershipService.joinAgencyConsultantsWithoutWaiting(
            AGENCY_ID,
            NEW_ROOM_ID,
            AGENCY_TOKEN,
            AgencyPreAssignmentRoomService.DEPARTMENT_IN_REQUEST_BUDGET))
        .thenReturn(
            new AgencySilentMembershipService.DepartmentFanOut(10, deferred, 3_300L, contacts));
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoomOnce(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);

    stubPublishedRoom(contacts);

    underTest.ensureHoldingRoom(session, user);

    // The request waits once, bounded, so the asker's invite is not refused by the same limit — and
    // never once per counsellor.
    verify(rateLimitPause).pause(3_300L);
    var inOrder =
        org.mockito.Mockito.inOrder(sessionRoomGateway, publication, departmentMembershipBackfill);
    inOrder.verify(sessionRoomGateway).inviteUser(NEW_ROOM_ID, USER_MATRIX_ID, AGENCY_TOKEN);
    inOrder.verify(publication).publishPrepared(eq(session), any(), eq(contacts));
    inOrder
        .verify(departmentMembershipBackfill)
        .scheduleConsultantIds(
            AGENCY_ID,
            SESSION_ID,
            NEW_ROOM_ID,
            AGENCY_MATRIX_ID,
            deferred.stream().map(de.caritas.cob.userservice.api.model.Consultant::getId).toList());
    assertEquals(NEW_ROOM_ID, session.getMatrixRoomId());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "!winning-room:example.org")
  @ExtendWith(OutputCaptureExtension.class)
  void recordsUnboundRoomWithoutSchedulingMembershipBackfill(
      String confirmedRoomId, CapturedOutput output) throws Exception {
    stubHappyPathUntilRoomCreation();
    when(roomAttempts.recordCreated(session, NEW_ROOM_ID)).thenReturn(81L);
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoomOnce(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);
    when(publication.publishPrepared(eq(session), any(), eq(List.of())))
        .thenReturn(Optional.ofNullable(confirmedRoomId));

    underTest.ensureHoldingRoom(session, user);

    assertEquals(confirmedRoomId, session.getMatrixRoomId());
    var order =
        org.mockito.Mockito.inOrder(
            roomAttempts, agencySilentMembershipService, sessionRoomGateway, publication);
    order.verify(roomAttempts).recordCreated(session, NEW_ROOM_ID);
    order
        .verify(agencySilentMembershipService)
        .joinAgencyConsultantsWithoutWaiting(
            AGENCY_ID,
            NEW_ROOM_ID,
            AGENCY_TOKEN,
            AgencyPreAssignmentRoomService.DEPARTMENT_IN_REQUEST_BUDGET);
    order.verify(roomAttempts).markRequesterInvitationStarted(81L);
    order.verify(sessionRoomGateway).inviteUser(NEW_ROOM_ID, USER_MATRIX_ID, AGENCY_TOKEN);
    order.verify(roomAttempts).recordConfirmedInvitation(eq(81L), any());
    order.verify(sessionRoomGateway).joinRoomOnce(NEW_ROOM_ID, USER_TOKEN);
    order.verify(publication).publishPrepared(eq(session), any(), eq(List.of()));
    verifyNoInteractions(departmentMembershipBackfill);
    assertThat(output)
        .contains(
            (confirmedRoomId == null
                    ? "Publication of the prepared holding room was refused for session "
                    : "An existing room binding superseded the prepared room for session ")
                + SESSION_ID)
        .doesNotContain(NEW_ROOM_ID);
    if (confirmedRoomId != null) {
      assertThat(output).doesNotContain(confirmedRoomId);
    }
  }

  @Test
  @DisplayName("the wait before the asker's invite is capped, whatever delay Synapse asks for")
  void ensureHoldingRoom_capsWaitBeforeAskerInvite() throws Exception {
    stubHappyPathUntilRoomCreation();
    var deferred = new de.caritas.cob.userservice.api.model.Consultant();
    deferred.setId("deferred-consultant");
    when(agencySilentMembershipService.joinAgencyConsultantsWithoutWaiting(
            eq(AGENCY_ID), eq(NEW_ROOM_ID), eq(AGENCY_TOKEN), any()))
        .thenReturn(
            new AgencySilentMembershipService.DepartmentFanOut(
                0, List.of(deferred), 300_000L, java.util.List.of()));
    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(sessionRoomGateway.joinRoomOnce(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);

    stubPublishedRoom(List.of());

    underTest.ensureHoldingRoom(session, user);

    verify(rateLimitPause).pause(AgencyPreAssignmentRoomService.MAX_WAIT_BEFORE_ASKER_INVITE_MS);
  }

  @Test
  @DisplayName("a room that could not be given to the asker queues no department backfill")
  void ensureHoldingRoom_queuesNoBackfill_whenAskerInviteFails() throws Exception {
    stubHappyPathUntilRoomCreation();
    when(agencySilentMembershipService.joinAgencyConsultantsWithoutWaiting(
            eq(AGENCY_ID), eq(NEW_ROOM_ID), eq(AGENCY_TOKEN), any()))
        .thenReturn(
            new AgencySilentMembershipService.DepartmentFanOut(
                0,
                java.util.List.of(new de.caritas.cob.userservice.api.model.Consultant()),
                0L,
                java.util.List.of()));
    org.mockito.Mockito.doThrow(new MatrixInviteUserException("refused"))
        .when(sessionRoomGateway)
        .inviteUser(NEW_ROOM_ID, USER_MATRIX_ID, AGENCY_TOKEN);

    underTest.ensureHoldingRoom(session, user);

    verifyNoInteractions(departmentMembershipBackfill, rateLimitPause);
    assertNull(session.getMatrixRoomId());
  }
}
