package de.caritas.cob.userservice.api.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.EnquiryData;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicConsultantRoutingService;
import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortContext;
import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortModality;
import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortPayloadBuilder;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.AgencyPreAssignmentRoomService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The orchestration half of ORISO-UserService#926, raised in review.
 *
 * <p><b>Why this file exists.</b> The other facade tests leave {@link ErstantwortPayloadBuilder}
 * unstubbed, so it returns {@code null} and {@code postErstantwort} exits before it posts anything.
 * Those tests therefore pass unchanged if the whole Erstantwort orchestration is deleted or
 * reordered — over-mocking that hides the defect rather than exposing it. Here the builder returns
 * a real body, so removing the call makes these fail.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateEnquiryMessageFacadeErstantwortTest {

  private static final String BODY = "[SYSTEM_NOTIFICATION]{\"type\":\"FIRST_RESPONSE\"}";
  private static final String ROOM_ID = "!room:matrix.test";
  private static final String EVENT_ID = "$enquiry";

  @Mock private SessionService sessionService;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private EmailNotificationFacade emailNotificationFacade;
  @Mock private ConsultantAgencyService consultantAgencyService;
  @Mock private TopicConsultantRoutingService topicConsultantRoutingService;
  @Mock private EventNotificationService eventNotificationService;
  @Mock private AgencyPreAssignmentRoomService agencyPreAssignmentRoomService;
  @Mock private ErstantwortPayloadBuilder erstantwortPayloadBuilder;
  @Mock private MatrixSessionSystemMessageService matrixSessionSystemMessageService;

  @InjectMocks private CreateEnquiryMessageFacade facade;

  private Session session;
  private User user;

  @BeforeEach
  void setUp() {
    user = new User();
    user.setUserId("asker-1");
    user.setMatrixUserId("@asker:matrix.test");
    user.setLanguageFormal(true);

    session = new Session();
    session.setId(42L);
    session.setUser(user);
    session.setAgencyId(1L);
    session.setMatrixRoomId(ROOM_ID);
    session.setStatus(SessionStatus.INITIAL);
    session.setRegistrationType(RegistrationType.REGISTERED);
    session.setIsConsultantDirectlySet(false);

    when(sessionService.getSession(42L)).thenReturn(Optional.of(session));
    when(matrixSynapseService.loginAsUserAccessToken(anyString())).thenReturn("token");
    when(matrixSynapseService.getRoomEvent(anyString(), anyString(), anyString()))
        .thenReturn(
            Optional.of(
                Map.of(
                    "event_id",
                    EVENT_ID,
                    "sender",
                    user.getMatrixUserId(),
                    "type",
                    "m.room.encrypted")));
    when(consultantAgencyService.findConsultantsByAgencyId(1L))
        .thenReturn(List.<ConsultantAgency>of());
    when(erstantwortPayloadBuilder.buildFirstResponseBody(any())).thenReturn(BODY);
  }

  private EnquiryData enquiryData() {
    var data = new EnquiryData(user, 42L, "Hallo", "de");
    data.setMatrixEventId(EVENT_ID);
    return data;
  }

  @Test
  void dispatchPostsTheErstantwortAndWritesOneTimelineEntry() {
    facade.createEnquiryMessage(enquiryData());

    verify(matrixSessionSystemMessageService).postFirstResponseMessage(eq(session), eq(BODY));
    verify(eventNotificationService).createFirstResponseNotification(session);
  }

  @Test
  void buildsTheContextForAgencyCounsellingWithTheAskerFormality() {
    facade.createEnquiryMessage(enquiryData());

    var captor = ArgumentCaptor.forClass(ErstantwortContext.class);
    verify(erstantwortPayloadBuilder).buildFirstResponseBody(captor.capture());

    assertThat(captor.getValue().getModality()).isEqualTo(ErstantwortModality.AGENCY_COUNSELLING);
    assertThat(captor.getValue().isInformal()).isFalse();
  }

  @Test
  void usesTheInformalVariantForAnInformalAsker() {
    /* Without this, every German wording fell back to the formal variant, so an
    informal Träger's advice seekers were addressed with "Sie" throughout the
    one message that is supposed to be the platform speaking to them. */
    user.setLanguageFormal(false);

    facade.createEnquiryMessage(enquiryData());

    var captor = ArgumentCaptor.forClass(ErstantwortContext.class);
    verify(erstantwortPayloadBuilder).buildFirstResponseBody(captor.capture());
    assertThat(captor.getValue().isInformal()).isTrue();
  }

  @Test
  void postsNothingWhenThePayloadCouldNotBeBuilt() {
    when(erstantwortPayloadBuilder.buildFirstResponseBody(any())).thenReturn(null);

    facade.createEnquiryMessage(enquiryData());

    verify(matrixSessionSystemMessageService, never()).postFirstResponseMessage(any(), anyString());
    verify(eventNotificationService, never()).createFirstResponseNotification(any());
  }

  @Test
  void anErstantwortFailureDoesNotFailTheEnquiryDispatch() {
    /* The person's message reaching a counsellor outranks the platform's own
    greeting. A thrown exception here would roll back a dispatched enquiry. */
    doThrow(new RuntimeException("matrix down"))
        .when(matrixSessionSystemMessageService)
        .postFirstResponseMessage(any(), anyString());

    var response = facade.createEnquiryMessage(enquiryData());

    assertThat(response).isNotNull();
    assertThat(response.getMatrixRoomId()).isEqualTo(ROOM_ID);
  }

  @Test
  void aTimelineFailureDoesNotFailTheEnquiryDispatchEither() {
    doThrow(new RuntimeException("db down"))
        .when(eventNotificationService)
        .createFirstResponseNotification(any());

    var response = facade.createEnquiryMessage(enquiryData());

    assertThat(response).isNotNull();
    verify(matrixSessionSystemMessageService).postFirstResponseMessage(eq(session), eq(BODY));
  }
}
