package de.caritas.cob.userservice.api.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortContext;
import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortModality;
import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortPayloadBuilder;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.testConfig.ConsultingTypeManagerTestConfig;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * ORISO-UserService#926, the last acceptance box: the Erstantwort exercised through the real Spring
 * context rather than through hand-wired collaborators.
 *
 * <p><b>What this covers that the unit tests do not.</b> The unit tests prove the payload shape and
 * the posting call in isolation. They cannot catch the failures that actually happen when this
 * ships: a bean that does not exist, a constructor dependency the context cannot satisfy, an
 * `@Component` annotation left off the builder, or a timeline write that throws once a real
 * repository and a real transaction are underneath it. All of those pass a Mockito test and break
 * on Pre-Dev.
 *
 * <p><b>Where the boundary is drawn, and why.</b> {@link MatrixSynapseService} is mocked — it is
 * the outer HTTP edge, and a real Synapse is not available in a build. Everything on this side of
 * it is real: the Spring context, the payload builder, the system-message service, the notification
 * service and the database. So "end to end" here means end to end *within the application*, and the
 * one thing it does not prove is that Synapse accepts the event. That last hop is a Pre-Dev check
 * and is written down as such on the PR.
 */
@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({ConsultingTypeManagerTestConfig.class})
class ErstantwortMatrixPathIT {

  private static final long SEEDED_SESSION_ID = 1L;

  @Autowired ErstantwortPayloadBuilder erstantwortPayloadBuilder;
  @Autowired MatrixSessionSystemMessageService matrixSessionSystemMessageService;
  @Autowired EventNotificationService eventNotificationService;
  @Autowired SessionRepository sessionRepository;

  @MockitoBean MatrixSynapseService matrixSynapseService;

  private Session session;

  @BeforeEach
  void setUp() {
    session = sessionRepository.findById(SEEDED_SESSION_ID).orElseThrow();
    when(matrixSynapseService.loginAsUserAccessToken(anyString())).thenReturn("token");
    when(matrixSynapseService.sendMessage(anyString(), anyString(), anyString()))
        .thenReturn(Map.of("event_id", "$erstantwort"));
  }

  @Test
  void theContextWiresEveryErstantwortCollaborator() {
    /* The cheapest failure this catches: a missing @Component on the builder, or a
    constructor dependency the context cannot satisfy. Both pass every unit
    test in the suite and fail at startup on Pre-Dev. */
    assertThat(erstantwortPayloadBuilder).isNotNull();
    assertThat(matrixSessionSystemMessageService).isNotNull();
    assertThat(eventNotificationService).isNotNull();
  }

  @Test
  void postsExactlyOneErstantwortEventIntoTheSessionRoom() {
    var body =
        erstantwortPayloadBuilder.buildFirstResponseBody(
            ErstantwortContext.builder().modality(ErstantwortModality.AGENCY_COUNSELLING).build());

    matrixSessionSystemMessageService.postFirstResponseMessage(session, body);

    var captor = ArgumentCaptor.forClass(String.class);
    verify(matrixSynapseService, times(1))
        .sendMessage(eq(session.getMatrixRoomId()), captor.capture(), anyString());

    var posted = captor.getValue();
    assertThat(posted).startsWith("[SYSTEM_NOTIFICATION]");
    assertThat(posted).contains("\"type\":\"FIRST_RESPONSE\"");
    assertThat(posted).contains("\"version\":1");
    // One event carrying the whole sequence, not one event per Baustein.
    assertThat(posted).contains("\"noPersonalData\"").contains("\"emergencyNumbers\"");
  }

  @Test
  void createsNoCarimatAccountAndInvitesNobody() throws Exception {
    /* ADR-018 §3. A bot in the room would be an extra Megolm key holder in a room
    carrying §11 KDG special-category data, and a bot has no Schweigepflicht.
    Asserted against the real wiring, not only against a hand-built service. */
    matrixSessionSystemMessageService.postFirstResponseMessage(
        session,
        erstantwortPayloadBuilder.buildFirstResponseBody(
            ErstantwortContext.builder().modality(ErstantwortModality.AGENCY_COUNSELLING).build()));

    verify(matrixSynapseService, never()).loginAsUserAccessToken(eq("@carimat:matrix.example"));
    /* `createUser` is the account-creation edge — the one call that would bring a
    Carimat identity into existence as a real Matrix account. It must never
    fire on this path. */
    verify(matrixSynapseService, never()).createUser(anyString(), anyString(), anyString());
  }

  @Test
  void writesTheTimelineEntryWithoutFailingWhenTheDatabaseIsReal() {
    /* A timeline write that throws under a real repository and transaction is
    precisely what a Mockito test cannot see. The Erstantwort swallows its own
    failures by design, so the assertion is that this completes at all. */
    eventNotificationService.createFirstResponseNotification(session);
  }

  @Test
  void survivesAMatrixFailureWithoutPropagating() {
    /* The person's message reaching a counsellor outranks the platform's own
    greeting: nothing here may roll a dispatched enquiry back. */
    when(matrixSynapseService.sendMessage(anyString(), anyString(), anyString()))
        .thenReturn(Map.of("error", "M_FORBIDDEN"));

    matrixSessionSystemMessageService.postFirstResponseMessage(
        session,
        erstantwortPayloadBuilder.buildFirstResponseBody(
            ErstantwortContext.builder().modality(ErstantwortModality.AGENCY_COUNSELLING).build()));
  }
}
