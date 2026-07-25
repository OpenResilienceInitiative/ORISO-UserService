package de.caritas.cob.userservice.api.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSessionRoomGateway;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.EnquiryData;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.consultingtype.TopicConsultantRoutingService;
import de.caritas.cob.userservice.api.service.session.AgencyPreAssignmentRoomService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * End-to-end Matrix-only (RC-off) coverage of the enquiry room-provisioning chain through {@link
 * CreateEnquiryMessageFacade}, driving a REAL {@link AgencyPreAssignmentRoomService} (rather than a
 * mock) so the full orchestration actually runs:
 *
 * <pre>
 *   createEnquiryMessage (rocket-chat.enabled=false, session has no Matrix room)
 *     -> ensureMatrixRoomForEnquiry
 *        -> AgencyPreAssignmentRoomService.ensureHoldingRoom
 *           -> Matrix room created, enquiry user invited + joined (membership)
 *           -> session.matrixRoomId persisted
 *     -> Matrix enquiry message sent
 *     -> session persisted (status NEW, groupId = matrix room id)
 * </pre>
 *
 * and asserts {@code verifyNoInteractions(rocketChatService)} for the whole flow.
 *
 * <p>This is the enquiry-facade end-to-end path that PR #300 explicitly deferred ("enquiry-facade
 * coverage stays at the adapter-contract level rather than driving CreateEnquiryMessageFacade
 * end-to-end"). The prior {@code CreateEnquiryMessageFacadeTest} Matrix cases all pre-seed {@code
 * session.matrixRoomId} and mock the room service, so the create/invite/membership/persist chain
 * was never exercised via the facade. Test Quality Audit 2026-07-04 — RC teardown phase 2 + C2.
 */
@ExtendWith(MockitoExtension.class)
class CreateEnquiryMessageFacadeMatrixRoomProvisioningTest {

  private static final Long SESSION_ID = 1234L;
  private static final Long AGENCY_ID = 55L;
  private static final String MESSAGE = "Hello, I need help.";
  private static final String USER_ID = "user-abc";
  private static final String USER_MATRIX_ID = "@asker:oriso.org";
  private static final String AGENCY_MATRIX_ID = "@agency-svc:oriso.org";
  private static final String AGENCY_MATRIX_LOCALPART = "agency-svc";
  private static final String AGENCY_MATRIX_PASSWORD = "s3cret";
  private static final String AGENCY_TOKEN = "agency-token";
  private static final String USER_TOKEN = "user-token";
  private static final String NEW_ROOM_ID = "!provisioned:oriso.org";
  private static final String MATRIX_EVENT_ID = "$event-1";

  @InjectMocks private CreateEnquiryMessageFacade createEnquiryMessageFacade;

  @Mock private SessionService sessionService;
  @Mock private RocketChatService rocketChatService;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private MatrixConfig matrixConfig;
  @Mock private ConsultantAgencyService consultantAgencyService;
  @Mock private ConsultingTypeManager consultingTypeManager;
  @Mock private TopicConsultantRoutingService topicConsultantRoutingService;

  @Mock
  private de.caritas.cob.userservice.api.facade.EmailNotificationFacade emailNotificationFacade;

  @Mock
  private de.caritas.cob.userservice.api.service.message.MessageServiceProvider
      messageServiceProvider;

  @Mock private de.caritas.cob.userservice.api.helper.UserHelper userHelper;
  @Mock private de.caritas.cob.userservice.api.service.user.UserService userService;

  @Mock
  private de.caritas.cob.userservice.api.service.liveevents.LiveEventNotificationService
      liveEventNotificationService;

  @Mock
  private de.caritas.cob.userservice.api.service.notification.EventNotificationService
      eventNotificationService;

  // Satisfies @InjectMocks construction (facade uses @NonNull constructor injection); the real
  // service is swapped in via setField in @BeforeEach so the orchestration actually runs.
  @Mock private AgencyPreAssignmentRoomService injectedRoomServicePlaceholder;

  // Room-provisioning collaborators of the REAL AgencyPreAssignmentRoomService.
  @Mock private AgencyMatrixCredentialClient matrixCredentialClient;

  private Session session;
  private User user;

  @BeforeEach
  void setUp() {
    // RC-off: the Matrix path is always taken.
    setField(createEnquiryMessageFacade, "rocketChatEnabled", false);

    // Wire a REAL room-provisioning service around the mocked Matrix collaborators so the
    // create/invite/join/persist orchestration actually executes when the facade calls it.
    var realRoomService =
        new AgencyPreAssignmentRoomService(
            matrixCredentialClient,
            new MatrixSessionRoomGateway(matrixSynapseService, matrixConfig),
            sessionService);
    setField(createEnquiryMessageFacade, "agencyPreAssignmentRoomService", realRoomService);

    user = new User();
    user.setUserId(USER_ID);
    user.setMatrixUserId(USER_MATRIX_ID);

    session = new Session();
    session.setId(SESSION_ID);
    session.setAgencyId(AGENCY_ID);
    session.setRegistrationType(RegistrationType.REGISTERED);
    session.setUser(user);
    session.setConsultingTypeId(0);
    session.setConsultant(null);
    session.setIsConsultantDirectlySet(false);
    session.setMatrixRoomId(null); // no room yet -> provisioning must run

    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(Mockito.mock(HttpServletRequest.class)));
  }

  private AgencyMatrixCredentialsDTO validCredentials() {
    var creds = new AgencyMatrixCredentialsDTO();
    creds.setMatrixUserId(AGENCY_MATRIX_ID);
    creds.setMatrixPassword(AGENCY_MATRIX_PASSWORD);
    return creds;
  }

  @Test
  @DisplayName(
      "RC-off enquiry with no existing room provisions Matrix room end-to-end and never touches"
          + " Rocket.Chat")
  void createEnquiryMessage_provisionsMatrixRoomEndToEnd_andNeverCallsRocketChat()
      throws Exception {
    var consultant = new Consultant();
    consultant.setId("consultant-1");
    var consultantAgency = new ConsultantAgency();
    consultantAgency.setConsultant(consultant);

    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(session));
    when(consultingTypeManager.getConsultingTypeSettings(0))
        .thenReturn(new ExtendedConsultingTypeResponseDTO());
    when(consultantAgencyService.findConsultantsByAgencyId(AGENCY_ID))
        .thenReturn(List.of(consultantAgency));

    // Room-provisioning collaborators (real AgencyPreAssignmentRoomService drives these).
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(validCredentials()));
    when(matrixSynapseService.loginUser(AGENCY_MATRIX_LOCALPART, AGENCY_MATRIX_PASSWORD))
        .thenReturn(AGENCY_TOKEN);
    var createBody = new MatrixCreateRoomResponseDTO();
    createBody.setRoomId(NEW_ROOM_ID);
    when(matrixSynapseService.createRoom(anyString(), anyString(), eq(AGENCY_TOKEN)))
        .thenReturn(ResponseEntity.ok(createBody));
    when(matrixSynapseService.inviteUserToRoom(NEW_ROOM_ID, USER_MATRIX_ID, AGENCY_TOKEN))
        .thenReturn(ResponseEntity.ok(new MatrixInviteUserResponseDTO()));

    // Enquiry-message post + membership use the user's own token.
    when(matrixSynapseService.loginAsUserAccessToken(USER_MATRIX_ID)).thenReturn(USER_TOKEN);
    when(matrixSynapseService.joinRoom(NEW_ROOM_ID, USER_TOKEN)).thenReturn(true);
    when(matrixSynapseService.sendMessage(NEW_ROOM_ID, MESSAGE, USER_TOKEN))
        .thenReturn(Map.of("event_id", MATRIX_EVENT_ID));

    var response =
        createEnquiryMessageFacade.createEnquiryMessage(
            new EnquiryData(
                user, SESSION_ID, MESSAGE, null, RocketChatCredentials.builder().build()));

    // Room provisioned: create -> invite -> membership.
    verify(matrixSynapseService).createRoom(anyString(), anyString(), eq(AGENCY_TOKEN));
    verify(matrixSynapseService).inviteUserToRoom(NEW_ROOM_ID, USER_MATRIX_ID, AGENCY_TOKEN);
    verify(matrixSynapseService).joinRoom(NEW_ROOM_ID, USER_TOKEN);

    // Enquiry message posted to the provisioned room.
    verify(matrixSynapseService).sendMessage(NEW_ROOM_ID, MESSAGE, USER_TOKEN);

    // Session persisted with the provisioned room id (once by the room service, once by the
    // facade).
    verify(sessionService, Mockito.atLeastOnce()).saveSession(session);
    assertEquals(NEW_ROOM_ID, session.getMatrixRoomId());
    assertEquals(NEW_ROOM_ID, session.getGroupId());
    assertEquals(SessionStatus.NEW, session.getStatus());

    // Response reflects the provisioned Matrix room + event.
    assertEquals(NEW_ROOM_ID, response.getRcGroupId());
    assertEquals(SESSION_ID, response.getSessionId());
    assertEquals(MATRIX_EVENT_ID, response.getT());

    // The whole enquiry flow never touched Rocket.Chat.
    verifyNoInteractions(rocketChatService);

    RequestContextHolder.resetRequestAttributes();
  }
}
