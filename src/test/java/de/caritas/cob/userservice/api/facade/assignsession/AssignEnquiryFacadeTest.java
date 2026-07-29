package de.caritas.cob.userservice.api.facade.assignsession;

import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT_WITH_AGENCY;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.SESSION_WITHOUT_CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USERNAME;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_WITH_MATRIX_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.facade.EmailNotificationFacade;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssignEnquiryFacadeTest {
  public static final long CURRENT_TENANT_ID = 1L;

  @InjectMocks AssignEnquiryFacade assignEnquiryFacade;
  @Mock SessionService sessionService;

  @Mock
  @SuppressWarnings("unused")
  KeycloakService keycloakService;

  @Mock SessionToConsultantVerifier sessionToConsultantVerifier;
  @Mock StatisticsService statisticsService;
  @Mock HttpServletRequest httpServletRequest;
  @Mock EmailNotificationFacade emailNotificationFacade;
  @Mock SessionRoomGateway sessionRoomGateway;
  @Mock ConsultantRepository consultantRepository;
  @Mock UserRepository userRepository;
  @Mock UserHelper userHelper;
  @Mock UsernameTranscoder usernameTranscoder;
  @Mock AgencyMatrixCredentialClient agencyMatrixCredentialClient;
  @Mock EventNotificationService eventNotificationService;
  @Mock de.caritas.cob.userservice.api.facade.SessionSupervisorFacade sessionSupervisorFacade;
  @Mock de.caritas.cob.userservice.api.facade.TeamDiscussionFacade teamDiscussionFacade;

  private static final String USER_MATRIX_ID = "@user:matrix.example.com";
  private static final String CONSULTANT_MATRIX_ID = "@consultant:matrix.example.com";
  private static final String MATRIX_ROOM_ID = "!createdRoom:matrix.example.com";
  private static final String MATRIX_TOKEN = "syt_matrix_token";

  @BeforeEach
  public void setup() throws MatrixCreateRoomException {
    // dev's Matrix migration: assignEnquiry now provisions a Matrix room and reads
    // session.getUser().getMatrixUserId() / consultant.getMatrixUserId(). The shared
    // TestConstants do not set these, so populate them here (reset in tearDown) and stub the
    // MatrixSynapseService happy path so room creation succeeds for every assignment test.
    USER_WITH_MATRIX_ID.setMatrixUserId(USER_MATRIX_ID);
    CONSULTANT_WITH_AGENCY.setMatrixUserId(CONSULTANT_MATRIX_ID);
    // Anonymous enquiry constant has no user wired; assignEnquiry now dereferences it.
    ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT.setUser(USER_WITH_MATRIX_ID);

    lenient()
        .when(usernameTranscoder.decodeUsername(anyString()))
        .thenAnswer(i -> i.getArgument(0));
    lenient().when(userHelper.getRandomPassword()).thenReturn("random-password");
    lenient()
        .when(sessionRoomGateway.userIdFor(anyString()))
        .thenAnswer(invocation -> "@" + invocation.getArgument(0) + ":matrix.example.com");

    givenMatrixRoomCreationSucceeds();
  }

  private void givenMatrixRoomCreationSucceeds() throws MatrixCreateRoomException {
    lenient()
        .when(sessionRoomGateway.createRoomAsUser(anyString(), anyString(), anyString()))
        .thenReturn(MATRIX_ROOM_ID);
    lenient().when(sessionRoomGateway.loginAsUser(anyString())).thenReturn(MATRIX_TOKEN);
    lenient().when(sessionRoomGateway.joinRoom(anyString(), anyString())).thenReturn(true);
    lenient()
        .when(sessionRoomGateway.setUserPowerLevel(anyString(), anyString(), anyInt(), anyString()))
        .thenReturn(true);
  }

  @org.junit.jupiter.api.AfterEach
  public void tearDown() {
    // Undo mutations of the shared TestConstants so other test classes are not affected.
    USER_WITH_MATRIX_ID.setMatrixUserId(null);
    USER_WITH_MATRIX_ID.setUsername(USERNAME);
    CONSULTANT_WITH_AGENCY.setMatrixUserId(null);
    ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT.setUser(null);

    TenantContext.clear();
  }

  private void verifyConsultantAndSessionHaveBeenChecked(Session session, Consultant consultant) {
    verify(sessionToConsultantVerifier, times(1))
        .verifySessionIsNotInProgress(
            argThat(
                consultantSessionDTO ->
                    consultantSessionDTO.getConsultant().equals(consultant)
                        && consultantSessionDTO.getSession().equals(session)));
    verify(sessionToConsultantVerifier, times(1))
        .verifyPreconditionsForAssignment(
            argThat(
                consultantSessionDTO ->
                    consultantSessionDTO.getConsultant().equals(consultant)
                        && consultantSessionDTO.getSession().equals(session)),
            Mockito.eq(false));
  }

  @Test
  void assignEnquiry_Should_ProvisionMissingUserMatrixAccountBeforeRoomCreation()
      throws MatrixCreateUserException {
    TenantContext.setCurrentTenant(CURRENT_TENANT_ID);
    USER_WITH_MATRIX_ID.setMatrixUserId(null);
    when(sessionRoomGateway.createUser(anyString(), anyString(), anyString()))
        .thenReturn(USER_MATRIX_ID);

    assignEnquiryFacade.assignRegisteredEnquiry(SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);

    verify(userRepository).save(USER_WITH_MATRIX_ID);
    assertEquals(USER_MATRIX_ID, USER_WITH_MATRIX_ID.getMatrixUserId());
  }

  @Test
  void assignEnquiry_Should_EnsureAdminMembershipInNewMatrixRoom() {
    // The notification listener syncs as the technical admin; the freshly created
    // session room must contain the admin or message notifications never fire.
    TenantContext.setCurrentTenant(CURRENT_TENANT_ID);
    CONSULTANT_WITH_AGENCY.setMatrixUserId("@consultant:matrix.example.com");

    assignEnquiryFacade.assignRegisteredEnquiry(SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);

    verify(sessionRoomGateway).ensureAdminInRoom(MATRIX_ROOM_ID, "@consultant:matrix.example.com");
  }

  @Test
  void assignEnquiry_Should_ResolveExistingUserMatrixAccount_WhenCreateFails()
      throws MatrixCreateUserException {
    TenantContext.setCurrentTenant(CURRENT_TENANT_ID);
    USER_WITH_MATRIX_ID.setMatrixUserId(null);
    USER_WITH_MATRIX_ID.setUsername("asker");
    when(sessionRoomGateway.createUser(eq("asker"), anyString(), eq("asker")))
        .thenThrow(new MatrixCreateUserException("User ID already taken"));
    when(sessionRoomGateway.loginAsUser("@asker:matrix.example.com")).thenReturn(MATRIX_TOKEN);

    assignEnquiryFacade.assignRegisteredEnquiry(SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);

    verify(userRepository).save(USER_WITH_MATRIX_ID);
    assertEquals("@asker:matrix.example.com", USER_WITH_MATRIX_ID.getMatrixUserId());
    verify(sessionRoomGateway, times(1)).createUser(eq("asker"), anyString(), eq("asker"));
  }

  @Test
  void
      assignEnquiry_Should_DeriveConstructedMxidServerPartFromConfiguredServerName_SoConfigChangesTheMxid()
          throws MatrixCreateUserException {
    // ADR-005 / DB-M04: AssignEnquiryFacade is the ONE place the service constructs a Matrix user
    // ID. Its server part must come from the injected MatrixConfig, never a literal (and certainly
    // never a bare IP). Inject a distinctive server name that production code could not plausibly
    // hardcode and prove it flows verbatim into the constructed MXID — i.e. change the config,
    // change the MXID.
    TenantContext.setCurrentTenant(CURRENT_TENANT_ID);
    when(sessionRoomGateway.userIdFor("probeuser")).thenReturn("@probeuser:test.example.org");
    USER_WITH_MATRIX_ID.setMatrixUserId(null);
    USER_WITH_MATRIX_ID.setUsername("probeuser");
    // Force the create path to fail so the MXID-construction fallback branch runs.
    when(sessionRoomGateway.createUser(eq("probeuser"), anyString(), eq("probeuser")))
        .thenThrow(new MatrixCreateUserException("User ID already taken"));

    assignEnquiryFacade.assignRegisteredEnquiry(SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);

    // The constructed candidate MXID must use the configured server name verbatim.
    assertThat(USER_WITH_MATRIX_ID.getMatrixUserId()).isEqualTo("@probeuser:test.example.org");
    verify(sessionRoomGateway, atLeastOnce()).loginAsUser("@probeuser:test.example.org");
  }

  @Test
  void assignAnonymousEnquiry_Should_ProvisionMatrixRoom_WhenSessionIsAnonymousConversation()
      throws Exception {
    assignEnquiryFacade.assignAnonymousEnquiry(
        ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);

    verifyConsultantAndSessionHaveBeenChecked(
        ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);
    verify(sessionRoomGateway).createRoomAsUser(any(), any(), any());
    verify(eventNotificationService)
        .createInquiryAcceptedNotification(
            ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);
  }

  // ---------------------------------------------------------------------------
  // assignRegisteredEnquiry — skip flag
  // ---------------------------------------------------------------------------

  @Test
  void assignRegisteredEnquiry_Should_SkipInProgressCheck_When_SkipFlagIsTrue() throws Exception {
    assignEnquiryFacade.assignRegisteredEnquiry(
        SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY, true);

    verify(sessionToConsultantVerifier, never()).verifySessionIsNotInProgress(any());
    verify(sessionToConsultantVerifier, times(1)).verifyPreconditionsForAssignment(any(), eq(true));
  }

  // ---------------------------------------------------------------------------
  // Supervision (auto-assigned) — grill 2026-07-13
  // ---------------------------------------------------------------------------

  @Test
  void assignRegisteredEnquiry_Should_attachStandingSupervisor_afterTheCaseIsAccepted() {
    assignEnquiryFacade.assignRegisteredEnquiry(SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);

    // Accepting an Agency Counselling case hands off to the supervision module, which attaches the
    // counsellor's standing supervisor (or does nothing if they have none).
    verify(sessionSupervisorFacade)
        .attachStandingSupervisorIfAssigned(
            SESSION_WITHOUT_CONSULTANT.getId(), CONSULTANT_WITH_AGENCY);
    verify(eventNotificationService)
        .createInquiryAcceptedNotification(SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);
  }

  // ---------------------------------------------------------------------------
  // Matrix account auto-creation for consultant without matrixUserId
  // ---------------------------------------------------------------------------

  @Test
  void assignEnquiry_Should_CreateMatrixAccount_When_ConsultantHasNoMatrixUserId()
      throws Exception {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setMatrixUserId(null);
    Session session = sessionWithUser(USER_MATRIX_ID, null);

    lenient()
        .when(sessionRoomGateway.createUser(anyString(), anyString(), anyString()))
        .thenReturn("@new-consultant:matrix.example.com");

    assignEnquiryFacade.assignRegisteredEnquiry(session, consultant);

    verify(sessionRoomGateway).createUser(anyString(), anyString(), anyString());
    verify(consultantRepository).save(consultant);
    assertThat(consultant.getMatrixUserId()).isEqualTo("@new-consultant:matrix.example.com");
  }

  @Test
  void assignEnquiry_Should_SwallowException_When_ConsultantMatrixAccountCreationFails()
      throws Exception {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setMatrixUserId(null);
    Session session = sessionWithUser(USER_MATRIX_ID, null);

    when(sessionRoomGateway.createUser(anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("Matrix registration failed"));
    lenient().when(sessionRoomGateway.loginAsUser(anyString())).thenReturn(null);

    // consultant ends up with null matrixUserId → ISE from missing credentials check, not from
    // createUser — which proves the exception was swallowed correctly
    assertThrows(
        InternalServerErrorException.class,
        () -> assignEnquiryFacade.assignRegisteredEnquiry(session, consultant));

    verify(sessionRoomGateway).createUser(anyString(), anyString(), anyString());
    verify(consultantRepository, never()).save(any());
  }

  // ---------------------------------------------------------------------------
  // assignEnquiry — missing Matrix credentials → ISE + rollback
  // ---------------------------------------------------------------------------

  @Test
  void assignEnquiry_Should_ThrowISEAndRollback_When_UserMatrixUserIdIsNull() throws Exception {
    Session session = sessionWithUser(null, null);
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    assertThrows(
        InternalServerErrorException.class,
        () -> assignEnquiryFacade.assignRegisteredEnquiry(session, consultant));

    verify(sessionService).updateConsultantAndStatusForSession(session, null, NEW);
  }

  @Test
  void
      assignEnquiry_Should_ThrowISEAndRollback_When_ConsultantMatrixUserIdIsNullAndCreationBodyNull()
          throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, null);
    Consultant consultant = consultantWithMatrixId(null);
    lenient()
        .when(sessionRoomGateway.createUser(anyString(), anyString(), anyString()))
        .thenReturn(null);
    lenient().when(sessionRoomGateway.loginAsUser(anyString())).thenReturn(null);

    assertThrows(
        InternalServerErrorException.class,
        () -> assignEnquiryFacade.assignRegisteredEnquiry(session, consultant));

    verify(sessionService).updateConsultantAndStatusForSession(session, null, NEW);
  }

  // ---------------------------------------------------------------------------
  // createNewMatrixRoom failure paths → ISE
  // ---------------------------------------------------------------------------

  @Test
  void assignEnquiry_Should_ThrowISE_When_MatrixCreateRoomReturnsNullBody() throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, null);
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    when(sessionRoomGateway.createRoomAsUser(anyString(), anyString(), anyString()))
        .thenReturn(null);

    assertThrows(
        InternalServerErrorException.class,
        () -> assignEnquiryFacade.assignRegisteredEnquiry(session, consultant));
  }

  @Test
  void assignEnquiry_Should_ThrowISE_When_MatrixCreateRoomReturnsNullRoomId() throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, null);
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    when(sessionRoomGateway.createRoomAsUser(anyString(), anyString(), anyString()))
        .thenReturn(null);

    assertThrows(
        InternalServerErrorException.class,
        () -> assignEnquiryFacade.assignRegisteredEnquiry(session, consultant));
  }

  @Test
  void assignEnquiry_Should_ThrowISE_When_ConsultantTokenIsBlankAfterRoomCreation()
      throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, null);
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    when(sessionRoomGateway.loginAsUser(CONSULTANT_MATRIX_ID)).thenReturn("");

    assertThrows(
        InternalServerErrorException.class,
        () -> assignEnquiryFacade.assignRegisteredEnquiry(session, consultant));
  }

  @Test
  void assignEnquiry_Should_ThrowISE_When_UserJoinRoomReturnsFalse() throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, null);
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    when(sessionRoomGateway.loginAsUser(USER_MATRIX_ID)).thenReturn(MATRIX_TOKEN);
    when(sessionRoomGateway.joinRoom(eq(MATRIX_ROOM_ID), eq(MATRIX_TOKEN))).thenReturn(false);

    assertThrows(
        InternalServerErrorException.class,
        () -> assignEnquiryFacade.assignRegisteredEnquiry(session, consultant));
  }

  @Test
  void assignEnquiry_Should_ThrowISE_When_MatrixCreateRoomThrowsException() throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, null);
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    when(sessionRoomGateway.createRoomAsUser(anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("Matrix unavailable"));

    assertThrows(
        InternalServerErrorException.class,
        () -> assignEnquiryFacade.assignRegisteredEnquiry(session, consultant));

    verify(sessionService).updateConsultantAndStatusForSession(session, null, NEW);
  }

  // ---------------------------------------------------------------------------
  // Existing room (session has matrixRoomId) — reuse path
  // ---------------------------------------------------------------------------

  @Test
  void assignEnquiry_Should_ReuseExistingRoom_When_AgencyCredentialsPresent() throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, "!existing-room:matrix.example.com");
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    AgencyMatrixCredentialsDTO creds =
        agencyCredentials("@agency:matrix.example.com", "agencyPass");
    when(agencyMatrixCredentialClient.fetchMatrixCredentials(any())).thenReturn(Optional.of(creds));
    when(sessionRoomGateway.loginUser(anyString(), anyString())).thenReturn("agency-token");

    assignEnquiryFacade.assignRegisteredEnquiry(session, consultant);

    verify(sessionRoomGateway, never()).createRoomAsUser(any(), any(), any());
    verify(sessionRoomGateway)
        .inviteUser(
            eq("!existing-room:matrix.example.com"), eq(CONSULTANT_MATRIX_ID), eq("agency-token"));
  }

  @Test
  void assignEnquiry_Should_FallBackToNewRoom_When_AgencyCredentialsAbsent() throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, "!existing-room:matrix.example.com");
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    when(agencyMatrixCredentialClient.fetchMatrixCredentials(any())).thenReturn(Optional.empty());

    assignEnquiryFacade.assignRegisteredEnquiry(session, consultant);

    verify(sessionRoomGateway).createRoomAsUser(any(), any(), any());
  }

  @Test
  void assignEnquiry_Should_FallBackToNewRoom_When_AgencyCredentialsIncomplete() throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, "!existing-room:matrix.example.com");
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    AgencyMatrixCredentialsDTO creds = agencyCredentials("@agency:matrix.example.com", "");
    when(agencyMatrixCredentialClient.fetchMatrixCredentials(any())).thenReturn(Optional.of(creds));

    assignEnquiryFacade.assignRegisteredEnquiry(session, consultant);

    verify(sessionRoomGateway).createRoomAsUser(any(), any(), any());
  }

  @Test
  void assignEnquiry_Should_FallBackToNewRoom_When_AgencyTokenLoginFails() throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, "!existing-room:matrix.example.com");
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    AgencyMatrixCredentialsDTO creds =
        agencyCredentials("@agency:matrix.example.com", "agencyPass");
    when(agencyMatrixCredentialClient.fetchMatrixCredentials(any())).thenReturn(Optional.of(creds));
    when(sessionRoomGateway.loginUser(anyString(), anyString())).thenReturn("");

    assignEnquiryFacade.assignRegisteredEnquiry(session, consultant);

    verify(sessionRoomGateway).createRoomAsUser(any(), any(), any());
  }

  @Test
  void assignEnquiry_Should_FallBackToNewRoom_When_ConsultantJoinExistingRoomFails()
      throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, "!existing-room:matrix.example.com");
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    AgencyMatrixCredentialsDTO creds =
        agencyCredentials("@agency:matrix.example.com", "agencyPass");
    when(agencyMatrixCredentialClient.fetchMatrixCredentials(any())).thenReturn(Optional.of(creds));
    when(sessionRoomGateway.loginUser(anyString(), anyString())).thenReturn("agency-token");
    when(sessionRoomGateway.loginAsUser(CONSULTANT_MATRIX_ID)).thenReturn(MATRIX_TOKEN);
    when(sessionRoomGateway.joinRoom(eq("!existing-room:matrix.example.com"), any()))
        .thenReturn(false);

    assignEnquiryFacade.assignRegisteredEnquiry(session, consultant);

    verify(sessionRoomGateway).createRoomAsUser(any(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private Session sessionWithUser(String userMatrixId, String matrixRoomId) {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setStatus(SessionStatus.NEW);
    session.setConsultant(null);
    session.setMatrixRoomId(matrixRoomId);
    session.setRegistrationType(RegistrationType.REGISTERED);
    User user = mock(User.class);
    // lenient: not all paths reach getUserId() / getUsername() (e.g. early-exit failure tests)
    lenient().when(user.getMatrixUserId()).thenReturn(userMatrixId);
    lenient().when(user.getUserId()).thenReturn("user-123");
    lenient().when(user.getUsername()).thenReturn("testuser");
    session.setUser(user);
    return session;
  }

  private Consultant consultantWithMatrixId(String matrixUserId) {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setMatrixUserId(matrixUserId);
    return consultant;
  }

  private AgencyMatrixCredentialsDTO agencyCredentials(String userId, String password) {
    AgencyMatrixCredentialsDTO dto = new AgencyMatrixCredentialsDTO();
    dto.setMatrixUserId(userId);
    dto.setMatrixPassword(password);
    return dto;
  }
}
