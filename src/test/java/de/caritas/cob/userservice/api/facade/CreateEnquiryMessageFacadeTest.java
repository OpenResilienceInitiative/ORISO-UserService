package de.caritas.cob.userservice.api.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.CreateEnquiryMessageException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.EnquiryData;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicConsultantRoutingService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.AgencyPreAssignmentRoomService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreateEnquiryMessageFacadeTest {

  private static final long SESSION_ID = 42L;
  private static final long AGENCY_ID = 7L;
  private static final String USER_ID = "user-1";
  private static final String MATRIX_USER_ID = "@user-1:oriso.test";
  private static final String MATRIX_ROOM_ID = "!room:oriso.test";
  private static final String MATRIX_TOKEN = "matrix-token";
  private static final String MATRIX_EVENT_ID = "$event";
  private static final String MESSAGE = "I need advice";

  @Mock private SessionService sessionService;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private EmailNotificationFacade emailNotificationFacade;
  @Mock private ConsultantAgencyService consultantAgencyService;
  @Mock private TopicConsultantRoutingService topicConsultantRoutingService;
  @Mock private EventNotificationService eventNotificationService;
  @Mock private AgencyPreAssignmentRoomService agencyPreAssignmentRoomService;
  @InjectMocks private CreateEnquiryMessageFacade facade;

  private User user;
  private Session session;
  private EnquiryData enquiryData;

  @BeforeEach
  void setUp() {
    user = new User();
    user.setUserId(USER_ID);
    user.setMatrixUserId(MATRIX_USER_ID);

    session = new Session();
    session.setId(SESSION_ID);
    session.setAgencyId(AGENCY_ID);
    session.setUser(user);
    session.setRegistrationType(RegistrationType.REGISTERED);
    session.setIsConsultantDirectlySet(false);
    session.setMatrixRoomId(MATRIX_ROOM_ID);

    enquiryData = new EnquiryData(user, SESSION_ID, MESSAGE, "de");
  }

  @Test
  void sendsEnquiryThroughExistingMatrixRoomAndUpdatesSession() {
    givenExistingSession();
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn(MATRIX_TOKEN);
    when(matrixSynapseService.sendMessage(MATRIX_ROOM_ID, MESSAGE, MATRIX_TOKEN))
        .thenReturn(Map.of("event_id", MATRIX_EVENT_ID));

    var response = facade.createEnquiryMessage(enquiryData);

    assertThat(response.getMatrixRoomId()).isEqualTo(MATRIX_ROOM_ID);
    assertThat(response.getSessionId()).isEqualTo(SESSION_ID);
    assertThat(response.getT()).isEqualTo(MATRIX_EVENT_ID);
    assertThat(session.getMatrixRoomId()).isEqualTo(MATRIX_ROOM_ID);
    assertThat(session.getStatus()).isEqualTo(SessionStatus.NEW);
    assertThat(session.getEnquiryMessageDate()).isNotNull();
    assertThat(session.getLanguageCode()).isEqualTo(LanguageCode.de);
    verify(sessionService).saveSession(session);
    verify(agencyPreAssignmentRoomService, never()).ensureHoldingRoom(session, user);
  }

  @Test
  void rejectsSessionThatDoesNotBelongToUser() {
    var otherUser = new User();
    otherUser.setUserId("other-user");
    session.setUser(otherUser);
    givenExistingSession();

    assertThatThrownBy(() -> facade.createEnquiryMessage(enquiryData))
        .isInstanceOf(CreateEnquiryMessageException.class)
        .hasMessageContaining("not found for user");

    verify(matrixSynapseService, never()).sendMessage(MATRIX_ROOM_ID, MESSAGE, MATRIX_TOKEN);
  }

  @Test
  void rejectsAnonymousSession() {
    session.setRegistrationType(RegistrationType.ANONYMOUS);
    givenExistingSession();

    assertThatThrownBy(() -> facade.createEnquiryMessage(enquiryData))
        .isInstanceOf(CreateEnquiryMessageException.class)
        .hasMessageContaining("is anonymous");
  }

  @Test
  void rejectsSecondEnquiryForSameSession() {
    session.setEnquiryMessageDate(LocalDateTime.now());
    givenExistingSession();

    assertThatThrownBy(() -> facade.createEnquiryMessage(enquiryData))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("already written");
  }

  @Test
  void requiresMatrixIdentityBeforeSending() {
    user.setMatrixUserId(null);
    givenExistingSession();

    assertThatThrownBy(() -> facade.createEnquiryMessage(enquiryData))
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessageContaining("has no Matrix account");

    verify(matrixSynapseService, never()).loginAsUserAccessToken(MATRIX_USER_ID);
  }

  @Test
  void requiresMatrixAccessTokenBeforeSending() {
    givenExistingSession();
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn("");

    assertThatThrownBy(() -> facade.createEnquiryMessage(enquiryData))
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessageContaining("Could not create Matrix token");

    verify(matrixSynapseService, never()).sendMessage(MATRIX_ROOM_ID, MESSAGE, MATRIX_TOKEN);
  }

  @Test
  void rejectsMatrixSendErrorResponse() {
    givenExistingSession();
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn(MATRIX_TOKEN);
    when(matrixSynapseService.sendMessage(MATRIX_ROOM_ID, MESSAGE, MATRIX_TOKEN))
        .thenReturn(Map.of("error", "room unavailable"));

    assertThatThrownBy(() -> facade.createEnquiryMessage(enquiryData))
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessageContaining("Could not post Matrix enquiry message");

    verify(sessionService, never()).saveSession(session);
  }

  @Test
  void provisionsMissingMatrixRoomBeforeSending() {
    session.setMatrixRoomId(null);
    givenExistingSession();
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn(MATRIX_TOKEN);
    when(matrixSynapseService.sendMessage(MATRIX_ROOM_ID, MESSAGE, MATRIX_TOKEN))
        .thenReturn(Map.of("event_id", MATRIX_EVENT_ID));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              session.setMatrixRoomId(MATRIX_ROOM_ID);
              return null;
            })
        .when(agencyPreAssignmentRoomService)
        .ensureHoldingRoom(session, user);

    facade.createEnquiryMessage(enquiryData);

    verify(agencyPreAssignmentRoomService).ensureHoldingRoom(session, user);
    verify(matrixSynapseService).sendMessage(MATRIX_ROOM_ID, MESSAGE, MATRIX_TOKEN);
  }

  @Test
  void appointmentEnquiryUpdatesSessionWithoutSendingMatrixMessage() {
    enquiryData.setConsultantEmail("consultant@example.test");
    givenExistingSession();

    var response = facade.createEnquiryMessage(enquiryData);

    assertThat(response.getMatrixRoomId()).isEqualTo(MATRIX_ROOM_ID);
    assertThat(response.getT()).isEmpty();
    verify(matrixSynapseService, never()).loginAsUserAccessToken(MATRIX_USER_ID);
    verify(matrixSynapseService, never()).sendMessage(MATRIX_ROOM_ID, MESSAGE, MATRIX_TOKEN);
    verify(sessionService).saveSession(session);
  }

  private void givenExistingSession() {
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(session));
  }
}
