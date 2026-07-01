package de.caritas.cob.userservice.api.facade.assignsession;

import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;
import static de.caritas.cob.userservice.api.testHelper.AsyncVerification.verifyAsync;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT_WITH_AGENCY;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.LIST_GROUP_MEMBER_DTO;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_GROUP_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.ROCKETCHAT_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.ROCKET_CHAT_SYSTEM_USER_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.SESSION_WITHOUT_CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.U25_SESSION_WITHOUT_CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_WITH_RC_ID;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hibernate.validator.internal.util.CollectionHelper.asSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupMemberDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.facade.EmailNotificationFacade;
import de.caritas.cob.userservice.api.facade.RocketChatFacade;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.liveevents.LiveEventNotificationService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.List;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class AssignEnquiryFacadeTest {
  public static final long CURRENT_TENANT_ID = 1L;

  @InjectMocks AssignEnquiryFacade assignEnquiryFacade;
  @Mock SessionService sessionService;
  @Mock RocketChatFacade rocketChatFacade;

  @Mock
  @SuppressWarnings("unused")
  KeycloakService keycloakService;

  @SuppressWarnings("unused")
  @Mock
  ConsultingTypeManager consultingTypeManager;

  @Mock SessionToConsultantVerifier sessionToConsultantVerifier;
  @Mock UnauthorizedMembersProvider unauthorizedMembersProvider;
  @Mock TenantContextProvider tenantContextProvider;
  @Mock StatisticsService statisticsService;
  @Mock HttpServletRequest httpServletRequest;
  @Mock EmailNotificationFacade emailNotificationFacade;
  @Mock MatrixSynapseService matrixSynapseService;
  @Mock ConsultantRepository consultantRepository;
  @Mock AgencyMatrixCredentialClient agencyMatrixCredentialClient;
  @Mock LiveEventNotificationService liveEventNotificationService;
  @Mock EventNotificationService eventNotificationService;

  private LogbackCaptor logCaptor;

  private static final String USER_MATRIX_ID = "@user:matrix.example.com";
  private static final String CONSULTANT_MATRIX_ID = "@consultant:matrix.example.com";
  private static final String MATRIX_ROOM_ID = "!createdRoom:matrix.example.com";
  private static final String MATRIX_TOKEN = "syt_matrix_token";

  @BeforeEach
  public void setup() throws MatrixCreateRoomException {
    logCaptor = LogbackCaptor.attach(LogService.class);

    // dev's Matrix migration: assignEnquiry now provisions a Matrix room and reads
    // session.getUser().getMatrixUserId() / consultant.getMatrixUserId(). The shared
    // TestConstants do not set these, so populate them here (reset in tearDown) and stub the
    // MatrixSynapseService happy path so room creation succeeds for every assignment test.
    USER_WITH_RC_ID.setMatrixUserId(USER_MATRIX_ID);
    CONSULTANT_WITH_AGENCY.setMatrixUserId(CONSULTANT_MATRIX_ID);
    // Anonymous enquiry constant has no user wired; assignEnquiry now dereferences it.
    ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT.setUser(USER_WITH_RC_ID);

    givenMatrixRoomCreationSucceeds();
  }

  private void givenMatrixRoomCreationSucceeds() throws MatrixCreateRoomException {
    var roomResponse = new MatrixCreateRoomResponseDTO();
    roomResponse.setRoomId(MATRIX_ROOM_ID);
    lenient()
        .when(matrixSynapseService.createRoomAsMatrixUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.status(HttpStatus.OK).body(roomResponse));
    lenient()
        .when(matrixSynapseService.loginAsUserAccessToken(anyString()))
        .thenReturn(MATRIX_TOKEN);
    lenient().when(matrixSynapseService.joinRoom(anyString(), anyString())).thenReturn(true);
    lenient()
        .when(
            matrixSynapseService.setUserPowerLevel(anyString(), anyString(), anyInt(), anyString()))
        .thenReturn(true);
  }

  @AfterEach
  public void tearDown() {
    // Undo mutations of the shared TestConstants so other test classes are not affected.
    USER_WITH_RC_ID.setMatrixUserId(null);
    CONSULTANT_WITH_AGENCY.setMatrixUserId(null);
    ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT.setUser(null);

    logCaptor.detach();
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
  void assignEnquiry_Should_ReturnOKAndRemoveSystemMessagesFromGroup() {
    // given
    TenantContext.setCurrentTenant(CURRENT_TENANT_ID);
    when(rocketChatFacade.retrieveRocketChatMembers(anyString())).thenReturn(LIST_GROUP_MEMBER_DTO);

    // when
    assignEnquiryFacade.assignRegisteredEnquiry(SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);

    // then
    verifyConsultantAndSessionHaveBeenChecked(SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);
    verify(rocketChatFacade, times(0)).removeUserFromGroup(ROCKET_CHAT_SYSTEM_USER_ID, RC_GROUP_ID);
    verifyAsync(
        (a) ->
            verify(rocketChatFacade, times(1))
                .removeSystemMessagesFromRocketChatGroup(anyString()));
    verifyAsync(
        (a) -> verify(tenantContextProvider).setCurrentTenantContextIfMissing(CURRENT_TENANT_ID));
  }

  @Test
  void assignEnquiry_Should_LogError_When_RCRemoveGroupMembersFails() {
    doThrow(new InternalServerErrorException(""))
        .when(rocketChatFacade)
        .removeSystemMessagesFromRocketChatGroup(anyString());

    assignEnquiryFacade.assignRegisteredEnquiry(
        U25_SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);

    verifyConsultantAndSessionHaveBeenChecked(
        U25_SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);
    verify(sessionService, times(1))
        .updateConsultantAndStatusForSession(
            U25_SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY, SessionStatus.IN_PROGRESS);
    verifyAsync((a) -> assertEquals(1, logCaptor.countAtLevel(Level.ERROR)));
  }

  @Test
  void assignEnquiry_Should_LogError_WhenRemoveSystemMessagesFromGroupFails() {
    doThrow(new InternalServerErrorException("error"))
        .when(rocketChatFacade)
        .removeSystemMessagesFromRocketChatGroup(Mockito.any());

    assignEnquiryFacade.assignRegisteredEnquiry(
        U25_SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);

    verifyConsultantAndSessionHaveBeenChecked(
        U25_SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);
    verifyAsync((a) -> assertEquals(1, logCaptor.countAtLevel(Level.ERROR)));
    verify(sessionService, times(1))
        .updateConsultantAndStatusForSession(
            U25_SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY, IN_PROGRESS);
  }

  @Test
  void assignEnquiry_Should_removeAllUnauthorizedMembers_When_sessionIsNotATeamSession() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setTeamSession(false);
    session.setStatus(SessionStatus.NEW);
    session.setConsultant(null);
    session.getUser().setRcUserId("userRcId");
    session.setRegistrationType(RegistrationType.REGISTERED);
    session.setAgencyId(CURRENT_TENANT_ID);
    ConsultantAgency consultantAgency = new EasyRandom().nextObject(ConsultantAgency.class);
    consultantAgency.setAgencyId(CURRENT_TENANT_ID);
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setConsultantAgencies(asSet(consultantAgency));
    consultant.setRocketChatId("consultantRcId");
    when(this.rocketChatFacade.retrieveRocketChatMembers(anyString()))
        .thenReturn(
            asList(
                new GroupMemberDTO("userRcId", null, "name", null, null),
                new GroupMemberDTO("consultantRcId", null, "name", null, null),
                new GroupMemberDTO("otherRcId", null, "name", null, null)));
    Consultant consultantToRemove = new EasyRandom().nextObject(Consultant.class);
    consultantToRemove.setRocketChatId("otherRcId");
    when(unauthorizedMembersProvider.obtainConsultantsToRemove(any(), any(), any(), any()))
        .thenReturn(List.of(consultantToRemove));

    this.assignEnquiryFacade.assignRegisteredEnquiry(session, consultant);

    verifyConsultantAndSessionHaveBeenChecked(session, consultant);
    verifyAsync(
        (a) ->
            verify(this.rocketChatFacade, times(1))
                .removeUserFromGroupIgnoreGroupNotFound(
                    consultantToRemove.getRocketChatId(), session.getGroupId()));
  }

  @Test
  void assignEnquiry_ShouldNot_removeTeamMembers_When_sessionIsTeamSession() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setTeamSession(false);
    session.setStatus(SessionStatus.NEW);
    session.setConsultant(null);
    session.getUser().setRcUserId("userRcId");
    session.setRegistrationType(RegistrationType.REGISTERED);
    session.setAgencyId(CURRENT_TENANT_ID);
    ConsultantAgency consultantAgency = new EasyRandom().nextObject(ConsultantAgency.class);
    consultantAgency.setAgencyId(CURRENT_TENANT_ID);
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setConsultantAgencies(asSet(consultantAgency));
    consultant.setRocketChatId("newConsultantRcId");
    when(this.rocketChatFacade.retrieveRocketChatMembers(anyString()))
        .thenReturn(
            asList(
                new GroupMemberDTO("userRcId", null, "name", null, null),
                new GroupMemberDTO("newConsultantRcId", null, "name", null, null),
                new GroupMemberDTO("otherRcId", null, "name", null, null),
                new GroupMemberDTO("teamConsultantRcId", null, "name", null, null),
                new GroupMemberDTO("teamConsultantRcId2", null, "name", null, null)));
    Consultant consultantToRemove = new EasyRandom().nextObject(Consultant.class);
    consultantToRemove.setRocketChatId("otherRcId");
    when(unauthorizedMembersProvider.obtainConsultantsToRemove(any(), any(), any(), any()))
        .thenReturn(List.of(consultantToRemove));

    this.assignEnquiryFacade.assignRegisteredEnquiry(session, consultant);

    verifyConsultantAndSessionHaveBeenChecked(session, consultant);
    verifyAsync(
        (a) ->
            verify(this.rocketChatFacade, atLeastOnce())
                .removeUserFromGroupIgnoreGroupNotFound(
                    consultantToRemove.getRocketChatId(), session.getGroupId()));
    verifyAsync(
        (a) ->
            verify(this.rocketChatFacade, never())
                .removeUserFromGroup("teamConsultantRcId", session.getGroupId()));
    verify(this.rocketChatFacade, never())
        .removeUserFromGroup("teamConsultantRcId2", session.getGroupId());
  }

  @Test
  void assignAnonymousEnquiry_Should_AddConsultantToGroup_WhenSessionIsAnonymousConversation() {
    assignEnquiryFacade.assignAnonymousEnquiry(
        ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);

    verifyConsultantAndSessionHaveBeenChecked(
        ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);
    verify(rocketChatFacade, times(1))
        .addUserToRocketChatGroup(ROCKETCHAT_ID, ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT.getGroupId());
  }

  @Test
  void assignAnonymousEnquiry_Should_RemoveSystemMessagesFromGroup() {
    assignEnquiryFacade.assignAnonymousEnquiry(
        ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);

    verifyConsultantAndSessionHaveBeenChecked(
        ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);
    verify(rocketChatFacade, times(1)).removeSystemMessagesFromRocketChatGroup(anyString());
  }

  @Test
  void
      assignAnonymousEnquiry_Should_ReturnInternalServerErrorAndDoARollback_WhenAddConsultantToGroupFails() {
    doThrow(new InternalServerErrorException(""))
        .when(rocketChatFacade)
        .addUserToRocketChatGroup(ROCKETCHAT_ID, RC_GROUP_ID);

    assertThrows(
        InternalServerErrorException.class,
        () -> {
          assignEnquiryFacade.assignAnonymousEnquiry(
              ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);
        });

    verifyConsultantAndSessionHaveBeenChecked(
        ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY);
    verify(sessionService, times(1))
        .updateConsultantAndStatusForSession(
            ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT,
            ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT.getConsultant(),
            ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT.getStatus());
    verify(sessionService, times(1))
        .updateConsultantAndStatusForSession(ANONYMOUS_ENQUIRY_WITHOUT_CONSULTANT, null, NEW);
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
  // Matrix account auto-creation for consultant without matrixUserId
  // ---------------------------------------------------------------------------

  @Test
  void assignEnquiry_Should_CreateMatrixAccount_When_ConsultantHasNoMatrixUserId()
      throws Exception {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setMatrixUserId(null);
    Session session = sessionWithUser(USER_MATRIX_ID, null);

    var userResponse = new MatrixCreateUserResponseDTO();
    userResponse.setUserId("@new-consultant:matrix.example.com");
    lenient()
        .when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.status(HttpStatus.OK).body(userResponse));

    assignEnquiryFacade.assignRegisteredEnquiry(session, consultant);

    verify(matrixSynapseService).createUser(anyString(), anyString(), anyString());
    verify(consultantRepository).save(consultant);
    assertThat(consultant.getMatrixUserId()).isEqualTo("@new-consultant:matrix.example.com");
  }

  @Test
  void assignEnquiry_Should_SwallowException_When_ConsultantMatrixAccountCreationFails()
      throws Exception {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setMatrixUserId(null);
    Session session = sessionWithUser(USER_MATRIX_ID, null);

    when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("Matrix registration failed"));

    // consultant ends up with null matrixUserId → ISE from missing credentials check, not from
    // createUser — which proves the exception was swallowed correctly
    assertThrows(
        InternalServerErrorException.class,
        () -> assignEnquiryFacade.assignRegisteredEnquiry(session, consultant));

    verify(matrixSynapseService).createUser(anyString(), anyString(), anyString());
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
        .when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.status(HttpStatus.OK).body(null));

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

    when(matrixSynapseService.createRoomAsMatrixUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.status(HttpStatus.OK).body(null));

    assertThrows(
        InternalServerErrorException.class,
        () -> assignEnquiryFacade.assignRegisteredEnquiry(session, consultant));
  }

  @Test
  void assignEnquiry_Should_ThrowISE_When_MatrixCreateRoomReturnsNullRoomId() throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, null);
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    var emptyRoom = new MatrixCreateRoomResponseDTO();
    emptyRoom.setRoomId(null);
    when(matrixSynapseService.createRoomAsMatrixUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.status(HttpStatus.OK).body(emptyRoom));

    assertThrows(
        InternalServerErrorException.class,
        () -> assignEnquiryFacade.assignRegisteredEnquiry(session, consultant));
  }

  @Test
  void assignEnquiry_Should_ThrowISE_When_ConsultantTokenIsBlankAfterRoomCreation()
      throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, null);
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MATRIX_ID)).thenReturn("");

    assertThrows(
        InternalServerErrorException.class,
        () -> assignEnquiryFacade.assignRegisteredEnquiry(session, consultant));
  }

  @Test
  void assignEnquiry_Should_ThrowISE_When_UserJoinRoomReturnsFalse() throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, null);
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    when(matrixSynapseService.loginAsUserAccessToken(USER_MATRIX_ID)).thenReturn(MATRIX_TOKEN);
    when(matrixSynapseService.joinRoom(eq(MATRIX_ROOM_ID), eq(MATRIX_TOKEN))).thenReturn(false);

    assertThrows(
        InternalServerErrorException.class,
        () -> assignEnquiryFacade.assignRegisteredEnquiry(session, consultant));
  }

  @Test
  void assignEnquiry_Should_ThrowISE_When_MatrixCreateRoomThrowsException() throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, null);
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    when(matrixSynapseService.createRoomAsMatrixUser(anyString(), anyString(), anyString()))
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
    when(matrixSynapseService.loginUser(anyString(), anyString())).thenReturn("agency-token");

    assignEnquiryFacade.assignRegisteredEnquiry(session, consultant);

    verify(matrixSynapseService, never()).createRoomAsMatrixUser(any(), any(), any());
    verify(matrixSynapseService)
        .inviteUserToRoom(
            eq("!existing-room:matrix.example.com"), eq(CONSULTANT_MATRIX_ID), eq("agency-token"));
  }

  @Test
  void assignEnquiry_Should_FallBackToNewRoom_When_AgencyCredentialsAbsent() throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, "!existing-room:matrix.example.com");
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    when(agencyMatrixCredentialClient.fetchMatrixCredentials(any())).thenReturn(Optional.empty());

    assignEnquiryFacade.assignRegisteredEnquiry(session, consultant);

    verify(matrixSynapseService).createRoomAsMatrixUser(any(), any(), any());
  }

  @Test
  void assignEnquiry_Should_FallBackToNewRoom_When_AgencyCredentialsIncomplete() throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, "!existing-room:matrix.example.com");
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    AgencyMatrixCredentialsDTO creds = agencyCredentials("@agency:matrix.example.com", "");
    when(agencyMatrixCredentialClient.fetchMatrixCredentials(any())).thenReturn(Optional.of(creds));

    assignEnquiryFacade.assignRegisteredEnquiry(session, consultant);

    verify(matrixSynapseService).createRoomAsMatrixUser(any(), any(), any());
  }

  @Test
  void assignEnquiry_Should_FallBackToNewRoom_When_AgencyTokenLoginFails() throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, "!existing-room:matrix.example.com");
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    AgencyMatrixCredentialsDTO creds =
        agencyCredentials("@agency:matrix.example.com", "agencyPass");
    when(agencyMatrixCredentialClient.fetchMatrixCredentials(any())).thenReturn(Optional.of(creds));
    when(matrixSynapseService.loginUser(anyString(), anyString())).thenReturn("");

    assignEnquiryFacade.assignRegisteredEnquiry(session, consultant);

    verify(matrixSynapseService).createRoomAsMatrixUser(any(), any(), any());
  }

  @Test
  void assignEnquiry_Should_FallBackToNewRoom_When_ConsultantJoinExistingRoomFails()
      throws Exception {
    Session session = sessionWithUser(USER_MATRIX_ID, "!existing-room:matrix.example.com");
    Consultant consultant = consultantWithMatrixId(CONSULTANT_MATRIX_ID);

    AgencyMatrixCredentialsDTO creds =
        agencyCredentials("@agency:matrix.example.com", "agencyPass");
    when(agencyMatrixCredentialClient.fetchMatrixCredentials(any())).thenReturn(Optional.of(creds));
    when(matrixSynapseService.loginUser(anyString(), anyString())).thenReturn("agency-token");
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MATRIX_ID))
        .thenReturn(MATRIX_TOKEN);
    when(matrixSynapseService.joinRoom(eq("!existing-room:matrix.example.com"), any()))
        .thenReturn(false);

    assignEnquiryFacade.assignRegisteredEnquiry(session, consultant);

    verify(matrixSynapseService).createRoomAsMatrixUser(any(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // updateRocketChatRooms (public method) — exception is re-thrown
  // ---------------------------------------------------------------------------

  @Test
  void updateRocketChatRooms_Should_LogAndRethrow_When_MembersFetchFails() {
    when(rocketChatFacade.retrieveRocketChatMembers(anyString()))
        .thenThrow(new RuntimeException("RC unavailable"));

    assertThrows(
        RuntimeException.class,
        () ->
            assignEnquiryFacade.updateRocketChatRooms(
                RC_GROUP_ID, SESSION_WITHOUT_CONSULTANT, CONSULTANT_WITH_AGENCY));
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
