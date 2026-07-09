package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class MatrixSessionSystemMessageServiceTest {

  private static final Long SESSION_ID = 42L;
  private static final Long AGENCY_ID = 7L;
  private static final String MATRIX_ROOM_ID = "!room:matrix.oriso.org";
  private static final String USER_MATRIX_ID = "@asker:matrix.oriso.org";
  private static final String CONSULTANT_ID = "consultant-1";
  private static final String CONSULTANT_MATRIX_ID = "@consultant:matrix.oriso.org";
  private static final String ACCESS_TOKEN = "matrix-access-token";

  @InjectMocks private MatrixSessionSystemMessageService matrixSessionSystemMessageService;

  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private AgencyMatrixCredentialClient agencyMatrixCredentialClient;
  @Mock private SessionService sessionService;
  @Mock private ConsultantService consultantService;

  private Logger logger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger(MatrixSessionSystemMessageService.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logAppender);
  }

  @Test
  void postUserLeftChatMessage_shouldPostSystemNotificationJson_onHappyPathViaUserCredentials() {
    // Participants must see a structured system message when the asker leaves, not silence.
    var session = sessionWithUserMatrixId(USER_MATRIX_ID, "asker.username");
    when(matrixSynapseService.loginAsUserAccessToken(USER_MATRIX_ID)).thenReturn(ACCESS_TOKEN);
    when(matrixSynapseService.sendMessage(eq(MATRIX_ROOM_ID), anyString(), eq(ACCESS_TOKEN)))
        .thenReturn(Map.of("event_id", "$evt"));

    matrixSessionSystemMessageService.postUserLeftChatMessage(session);

    var bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(matrixSynapseService)
        .sendMessage(eq(MATRIX_ROOM_ID), bodyCaptor.capture(), eq(ACCESS_TOKEN));
    var body = bodyCaptor.getValue();
    assertThat(body)
        .startsWith(MatrixSessionSystemMessageService.SYSTEM_NOTIFICATION_PREFIX)
        .contains("\"type\":\"" + MatrixSessionSystemMessageService.USER_LEFT_CHAT_TYPE + "\"")
        .contains("\"username\":\"asker.username\"");
  }

  @Test
  void postUserLeftChatMessage_shouldEscapeBackslashAndQuoteInUsername() {
    // Usernames with JSON-special characters must not break the in-room notification payload.
    var session = sessionWithUserMatrixId(USER_MATRIX_ID, "say\"hi\\");
    when(matrixSynapseService.loginAsUserAccessToken(USER_MATRIX_ID)).thenReturn(ACCESS_TOKEN);
    when(matrixSynapseService.sendMessage(anyString(), anyString(), anyString()))
        .thenReturn(Map.of("event_id", "$evt"));

    matrixSessionSystemMessageService.postUserLeftChatMessage(session);

    var bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(matrixSynapseService).sendMessage(anyString(), bodyCaptor.capture(), anyString());
    assertThat(bodyCaptor.getValue()).contains("\"username\":\"say\\\"hi\\\\\"");
  }

  @Test
  void postUserLeftChatMessage_shouldUseAgencyPasswordLogin_withMatrixLocalpartExtracted() {
    // Agency service accounts post as a localpart login when no human Matrix ID is on the session.
    var session = sessionWithoutHumanMatrixIds();
    var credentials = agencyCredentials("@agency-bot:matrix.oriso.org", "agency-secret");
    when(agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(credentials));
    when(matrixSynapseService.loginUser("agency-bot", "agency-secret")).thenReturn(ACCESS_TOKEN);
    when(matrixSynapseService.sendMessage(anyString(), anyString(), eq(ACCESS_TOKEN)))
        .thenReturn(Map.of("event_id", "$evt"));

    matrixSessionSystemMessageService.postUserLeftChatMessage(session);

    verify(matrixSynapseService).loginUser("agency-bot", "agency-secret");
    verify(matrixSynapseService).sendMessage(eq(MATRIX_ROOM_ID), anyString(), eq(ACCESS_TOKEN));
  }

  @Test
  void postUserLeftChatMessage_shouldUseBareAgencyLocalpart_whenMatrixUserIdHasNoSigil() {
    // Bare localpart agency IDs must be passed through without forcing a @ prefix.
    var session = sessionWithoutHumanMatrixIds();
    var credentials = agencyCredentials("agency-bot", "agency-secret");
    when(agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(credentials));
    when(matrixSynapseService.loginUser("agency-bot", "agency-secret")).thenReturn(ACCESS_TOKEN);
    when(matrixSynapseService.sendMessage(anyString(), anyString(), eq(ACCESS_TOKEN)))
        .thenReturn(Map.of("event_id", "$evt"));

    matrixSessionSystemMessageService.postUserLeftChatMessage(session);

    verify(matrixSynapseService).loginUser("agency-bot", "agency-secret");
  }

  @Test
  void postUserLeftChatMessage_shouldUseConsultantMatrixId_afterReloadFromConsultantService() {
    // Consultant credentials are refreshed from the DB so a stale session snapshot still works.
    var consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    consultant.setMatrixUserId(CONSULTANT_MATRIX_ID);

    var session = baseSession();
    session.setUser(new User());
    session.setConsultant(consultant);

    when(consultantService.getConsultant(CONSULTANT_ID)).thenReturn(Optional.of(consultant));
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MATRIX_ID))
        .thenReturn(ACCESS_TOKEN);
    when(matrixSynapseService.sendMessage(anyString(), anyString(), eq(ACCESS_TOKEN)))
        .thenReturn(Map.of("event_id", "$evt"));

    matrixSessionSystemMessageService.postUserLeftChatMessage(session);

    verify(consultantService).getConsultant(CONSULTANT_ID);
    verify(matrixSynapseService).loginAsUserAccessToken(CONSULTANT_MATRIX_ID);
    verify(matrixSynapseService).sendMessage(eq(MATRIX_ROOM_ID), anyString(), eq(ACCESS_TOKEN));
  }

  @Test
  void postUserLeftChatMessage_shouldDoNothing_whenSessionIsNull() {
    // Finishing a session must never NPE when the caller passes a null reference.
    matrixSessionSystemMessageService.postUserLeftChatMessage(null);

    verifyNoInteractions(
        matrixSynapseService, sessionService, consultantService, agencyMatrixCredentialClient);
  }

  @Test
  void postUserLeftChatMessage_shouldDoNothing_whenSessionIdIsNull() {
    // Sessions without a persisted id cannot be addressed in Matrix — skip quietly.
    var session = new Session();
    session.setMatrixRoomId(MATRIX_ROOM_ID);

    matrixSessionSystemMessageService.postUserLeftChatMessage(session);

    verifyNoInteractions(
        matrixSynapseService, sessionService, consultantService, agencyMatrixCredentialClient);
  }

  @Test
  void postUserLeftChatMessage_shouldReloadMatrixRoomIdFromSessionService_whenBlankOnSession() {
    // Room id may only exist on the persisted session row, not the in-memory object.
    var session = sessionWithUserMatrixId(USER_MATRIX_ID, "asker.username");
    session.setMatrixRoomId(null);
    var persisted = baseSession();
    persisted.setMatrixRoomId(MATRIX_ROOM_ID);
    persisted.setUser(session.getUser());

    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(persisted));
    when(matrixSynapseService.loginAsUserAccessToken(USER_MATRIX_ID)).thenReturn(ACCESS_TOKEN);
    when(matrixSynapseService.sendMessage(anyString(), anyString(), eq(ACCESS_TOKEN)))
        .thenReturn(Map.of("event_id", "$evt"));

    matrixSessionSystemMessageService.postUserLeftChatMessage(session);

    verify(sessionService).getSession(SESSION_ID);
    verify(matrixSynapseService).sendMessage(eq(MATRIX_ROOM_ID), anyString(), eq(ACCESS_TOKEN));
  }

  @Test
  void postUserLeftChatMessage_shouldNotSendMessage_whenRoomIdStillBlankAfterReload() {
    // Without a Matrix room there is nowhere to post — abort without calling Synapse.
    var session = sessionWithUserMatrixId(USER_MATRIX_ID, "asker");
    session.setMatrixRoomId("  ");
    var persisted = baseSession();
    persisted.setMatrixRoomId(null);
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(persisted));

    matrixSessionSystemMessageService.postUserLeftChatMessage(session);

    verify(sessionService).getSession(SESSION_ID);
    verify(matrixSynapseService, never()).sendMessage(anyString(), anyString(), anyString());
  }

  @Test
  void postUserLeftChatMessage_shouldNotSendMessage_whenAgencyDtoMissingUserId() {
    // Incomplete agency credentials cannot authenticate — message must not be attempted.
    var session = sessionWithoutHumanMatrixIds();
    var credentials = agencyCredentials(null, "agency-secret");
    when(agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(credentials));

    matrixSessionSystemMessageService.postUserLeftChatMessage(session);

    verify(agencyMatrixCredentialClient).fetchMatrixCredentials(AGENCY_ID);
    verify(matrixSynapseService, never()).sendMessage(anyString(), anyString(), anyString());
    assertThat(logAppender.list).noneMatch(e -> e.getLevel().toString().equals("WARN"));
  }

  @Test
  void postUserLeftChatMessage_shouldNotSendMessage_whenAgencyDtoMissingPassword() {
    // Password-less agency credentials are filtered out before any Synapse login attempt.
    var session = sessionWithoutHumanMatrixIds();
    var credentials = agencyCredentials("@agency:matrix.oriso.org", "  ");
    when(agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(credentials));

    matrixSessionSystemMessageService.postUserLeftChatMessage(session);

    verify(matrixSynapseService, never()).sendMessage(anyString(), anyString(), anyString());
    verify(matrixSynapseService, never()).loginUser(anyString(), anyString());
    assertThat(logAppender.list).noneMatch(e -> e.getLevel().toString().equals("WARN"));
  }

  @Test
  void postUserLeftChatMessage_shouldWarnAndSkipSend_whenAccessTokenUnavailable() {
    // A missing token must not block session teardown — warn and continue.
    var session = sessionWithUserMatrixId(USER_MATRIX_ID, "asker");
    when(matrixSynapseService.loginAsUserAccessToken(USER_MATRIX_ID)).thenReturn(null);

    assertThatCode(() -> matrixSessionSystemMessageService.postUserLeftChatMessage(session))
        .doesNotThrowAnyException();

    verify(matrixSynapseService, never()).sendMessage(anyString(), anyString(), anyString());
    assertThat(logAppender.list)
        .anyMatch(
            e ->
                e.getLevel().toString().equals("WARN")
                    && e.getFormattedMessage().contains("token unavailable"));
  }

  @Test
  void postUserLeftChatMessage_shouldWarn_whenSendMessageReturnsErrorKey() {
    // Synapse error responses must be surfaced without aborting the leave/delete flow.
    var session = sessionWithUserMatrixId(USER_MATRIX_ID, "asker");
    when(matrixSynapseService.loginAsUserAccessToken(USER_MATRIX_ID)).thenReturn(ACCESS_TOKEN);
    when(matrixSynapseService.sendMessage(anyString(), anyString(), eq(ACCESS_TOKEN)))
        .thenReturn(Map.of("error", "M_FORBIDDEN"));

    assertThatCode(() -> matrixSessionSystemMessageService.postUserLeftChatMessage(session))
        .doesNotThrowAnyException();

    assertThat(logAppender.list)
        .anyMatch(
            e ->
                e.getLevel().toString().equals("WARN")
                    && e.getFormattedMessage().contains("M_FORBIDDEN"));
  }

  @Test
  void postUserLeftChatMessage_shouldPropagateException_whenSendMessageThrows() {
    // Production code does not catch Synapse transport failures — document actual behaviour.
    var session = sessionWithUserMatrixId(USER_MATRIX_ID, "asker");
    when(matrixSynapseService.loginAsUserAccessToken(USER_MATRIX_ID)).thenReturn(ACCESS_TOKEN);
    when(matrixSynapseService.sendMessage(anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("synapse down"));

    assertThrows(
        RuntimeException.class,
        () -> matrixSessionSystemMessageService.postUserLeftChatMessage(session));
  }

  @Test
  void postUserLeftChatMessage_shouldReloadUsernameFromSession_whenBlankOnUser() {
    var session = sessionWithUserMatrixId(USER_MATRIX_ID, "  ");
    var persistedUser = new User();
    persistedUser.setUsername("persisted.username");
    var persisted = baseSession();
    persisted.setUser(persistedUser);

    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(persisted));
    when(matrixSynapseService.loginAsUserAccessToken(USER_MATRIX_ID)).thenReturn(ACCESS_TOKEN);
    when(matrixSynapseService.sendMessage(anyString(), anyString(), eq(ACCESS_TOKEN)))
        .thenReturn(Map.of("event_id", "$evt"));

    matrixSessionSystemMessageService.postUserLeftChatMessage(session);

    var bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(matrixSynapseService).sendMessage(anyString(), bodyCaptor.capture(), anyString());
    assertThat(bodyCaptor.getValue()).contains("\"username\":\"persisted.username\"");
  }

  @Test
  void postUserLeftChatMessage_shouldUseEmptyUsername_whenReloadAlsoBlank() {
    var session = sessionWithUserMatrixId(USER_MATRIX_ID, "");
    var persisted = baseSession();
    var persistedUser = new User();
    persistedUser.setUsername("");
    persisted.setUser(persistedUser);

    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(persisted));
    when(matrixSynapseService.loginAsUserAccessToken(USER_MATRIX_ID)).thenReturn(ACCESS_TOKEN);
    when(matrixSynapseService.sendMessage(anyString(), anyString(), eq(ACCESS_TOKEN)))
        .thenReturn(Map.of("event_id", "$evt"));

    matrixSessionSystemMessageService.postUserLeftChatMessage(session);

    var bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(matrixSynapseService).sendMessage(anyString(), bodyCaptor.capture(), anyString());
    assertThat(bodyCaptor.getValue()).contains("\"username\":\"\"");
  }

  @Test
  void postUserLeftChatMessage_shouldNotSendMessage_whenAgencyCredentialsEmpty() {
    var session = sessionWithoutHumanMatrixIds();
    when(agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.empty());

    matrixSessionSystemMessageService.postUserLeftChatMessage(session);

    verify(agencyMatrixCredentialClient).fetchMatrixCredentials(AGENCY_ID);
    verify(matrixSynapseService, never()).sendMessage(anyString(), anyString(), anyString());
  }

  @Test
  void postUserLeftChatMessage_shouldNotSendMessage_whenConsultantHasNoMatrixIdAfterReload() {
    var consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    consultant.setMatrixUserId(null);

    var session = baseSession();
    session.setUser(new User());
    session.setConsultant(consultant);

    when(consultantService.getConsultant(CONSULTANT_ID)).thenReturn(Optional.of(consultant));
    when(agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.empty());

    matrixSessionSystemMessageService.postUserLeftChatMessage(session);

    verify(consultantService).getConsultant(CONSULTANT_ID);
    verify(matrixSynapseService, never()).sendMessage(anyString(), anyString(), anyString());
  }

  private Session baseSession() {
    var session = new Session();
    session.setId(SESSION_ID);
    session.setAgencyId(AGENCY_ID);
    session.setMatrixRoomId(MATRIX_ROOM_ID);
    return session;
  }

  private Session sessionWithUserMatrixId(String matrixUserId, String username) {
    var user = new User();
    user.setMatrixUserId(matrixUserId);
    user.setUsername(username);
    var session = baseSession();
    session.setUser(user);
    return session;
  }

  private Session sessionWithoutHumanMatrixIds() {
    var session = baseSession();
    session.setUser(new User());
    session.setConsultant(new Consultant());
    return session;
  }

  private AgencyMatrixCredentialsDTO agencyCredentials(String matrixUserId, String password) {
    var dto = new AgencyMatrixCredentialsDTO();
    dto.setMatrixUserId(matrixUserId);
    dto.setMatrixPassword(password);
    return dto;
  }
}
