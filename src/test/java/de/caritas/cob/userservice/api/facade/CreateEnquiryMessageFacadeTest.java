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
import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortPayloadBuilder;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
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

  /* ADR-018: the Erstantwort is posted at the end of a successful dispatch.
  Mocked, not stubbed — these tests are about the dispatch itself, and the
  Erstantwort has its own tests in service/erstantwort. */
  @Mock private ErstantwortPayloadBuilder erstantwortPayloadBuilder;
  @Mock private MatrixSessionSystemMessageService matrixSessionSystemMessageService;

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
  void finalizesEncryptedEnquiryThroughExistingMatrixRoomAndUpdatesSession() {
    givenExistingSession();
    enquiryData.setMatrixEventId(MATRIX_EVENT_ID);
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn(MATRIX_TOKEN);
    when(matrixSynapseService.getRoomEvent(MATRIX_ROOM_ID, MATRIX_EVENT_ID, MATRIX_TOKEN))
        .thenReturn(
            Optional.of(
                Map.of(
                    "event_id", MATRIX_EVENT_ID,
                    "sender", MATRIX_USER_ID,
                    "type", "m.room.encrypted")));

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
    verify(matrixSynapseService, never()).sendMessage(MATRIX_ROOM_ID, MESSAGE, MATRIX_TOKEN);
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
  void requiresMatrixAccessTokenBeforeValidatingEncryptedEvent() {
    givenExistingSession();
    enquiryData.setMatrixEventId(MATRIX_EVENT_ID);
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn("");

    assertThatThrownBy(() -> facade.createEnquiryMessage(enquiryData))
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessageContaining("Could not validate encrypted Matrix enquiry event");

    verify(matrixSynapseService, never()).sendMessage(MATRIX_ROOM_ID, MESSAGE, MATRIX_TOKEN);
  }

  @Test
  void rejectsMissingEncryptedMatrixEventReference() {
    givenExistingSession();

    assertThatThrownBy(() -> facade.createEnquiryMessage(enquiryData))
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessageContaining("requires an encrypted Matrix event");

    verify(sessionService, never()).saveSession(session);
    verify(matrixSynapseService, never()).sendMessage(MATRIX_ROOM_ID, MESSAGE, MATRIX_TOKEN);
  }

  @Test
  void rejectsPlaintextMatrixEvent() {
    givenExistingSession();
    enquiryData.setMatrixEventId(MATRIX_EVENT_ID);
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn(MATRIX_TOKEN);
    when(matrixSynapseService.getRoomEvent(MATRIX_ROOM_ID, MATRIX_EVENT_ID, MATRIX_TOKEN))
        .thenReturn(
            Optional.of(
                Map.of(
                    "event_id", MATRIX_EVENT_ID,
                    "sender", MATRIX_USER_ID,
                    "type", "m.room.message")));

    assertThatThrownBy(() -> facade.createEnquiryMessage(enquiryData))
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessageContaining("not an encrypted Matrix event");

    verify(sessionService, never()).saveSession(session);
  }

  @Test
  void rejectsEncryptedEventFromDifferentSender() {
    givenExistingSession();
    enquiryData.setMatrixEventId(MATRIX_EVENT_ID);
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn(MATRIX_TOKEN);
    when(matrixSynapseService.getRoomEvent(MATRIX_ROOM_ID, MATRIX_EVENT_ID, MATRIX_TOKEN))
        .thenReturn(
            Optional.of(
                Map.of(
                    "event_id", MATRIX_EVENT_ID,
                    "sender", "@attacker:oriso.test",
                    "type", "m.room.encrypted")));

    assertThatThrownBy(() -> facade.createEnquiryMessage(enquiryData))
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessageContaining("was not sent by enquiry user");

    verify(sessionService, never()).saveSession(session);
  }

  @Test
  void rejectsUnknownMatrixEvent() {
    givenExistingSession();
    enquiryData.setMatrixEventId(MATRIX_EVENT_ID);
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn(MATRIX_TOKEN);
    when(matrixSynapseService.getRoomEvent(MATRIX_ROOM_ID, MATRIX_EVENT_ID, MATRIX_TOKEN))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> facade.createEnquiryMessage(enquiryData))
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessageContaining("Could not read Matrix enquiry event");

    verify(sessionService, never()).saveSession(session);
  }

  @Test
  void rejectsMatrixResponseWithDifferentEventId() {
    givenExistingSession();
    enquiryData.setMatrixEventId(MATRIX_EVENT_ID);
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn(MATRIX_TOKEN);
    when(matrixSynapseService.getRoomEvent(MATRIX_ROOM_ID, MATRIX_EVENT_ID, MATRIX_TOKEN))
        .thenReturn(
            Optional.of(
                Map.of(
                    "event_id", "$different-event",
                    "sender", MATRIX_USER_ID,
                    "type", "m.room.encrypted")));

    assertThatThrownBy(() -> facade.createEnquiryMessage(enquiryData))
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessageContaining("did not match");

    verify(sessionService, never()).saveSession(session);
  }

  @Test
  void provisionsMissingMatrixRoomBeforeSending() {
    session.setMatrixRoomId(null);
    givenExistingSession();
    enquiryData.setMatrixEventId(MATRIX_EVENT_ID);
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn(MATRIX_TOKEN);
    when(matrixSynapseService.getRoomEvent(MATRIX_ROOM_ID, MATRIX_EVENT_ID, MATRIX_TOKEN))
        .thenReturn(
            Optional.of(
                Map.of(
                    "event_id", MATRIX_EVENT_ID,
                    "sender", MATRIX_USER_ID,
                    "type", "m.room.encrypted")));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              session.setMatrixRoomId(MATRIX_ROOM_ID);
              return null;
            })
        .when(agencyPreAssignmentRoomService)
        .ensureHoldingRoom(session, user);

    facade.createEnquiryMessage(enquiryData);

    verify(agencyPreAssignmentRoomService).ensureHoldingRoom(session, user);
    verify(matrixSynapseService).getRoomEvent(MATRIX_ROOM_ID, MATRIX_EVENT_ID, MATRIX_TOKEN);
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
