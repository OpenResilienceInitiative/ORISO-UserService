package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixIdentifierRedactor;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixMediaClient;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixRoomClient;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.conversationfeature.ConversationFeatureGate;
import de.caritas.cob.userservice.api.service.matrix.RedisMessageMirrorService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class MatrixMessageControllerTest {

  private static final Long SESSION_ID = 42L;
  private static final String USER_ID = "user-id";
  private static final String USERNAME = "seeker";
  private static final String MATRIX_ROOM_ID = "!room:matrix";
  private static final String MATRIX_USER_ID = "@seeker:matrix";
  private static final String MATRIX_BASE_URL = "https://matrix.example.com";
  private static final String MATRIX_LOGIN_URL = MATRIX_BASE_URL + "/_matrix/client/r0/login";
  private static final String MATRIX_PASSWORD_UPDATE_URL =
      MATRIX_BASE_URL + "/_synapse/admin/v2/users/%40seeker%3Amatrix";
  private static final String BROWSER_DEVICE_ID = "DEVICE_A";

  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private SessionService sessionService;
  @Mock private ChatService chatService;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private ConsultantService consultantService;
  @Mock private UserService userService;
  @Mock private AgencyMatrixCredentialClient matrixCredentialClient;
  @Mock private ChatPermissionVerifier chatPermissionVerifier;
  @Mock private ConversationFeatureGate conversationFeatureGate;

  private MatrixMessageController controller;

  @BeforeEach
  void setUp() {
    controller = controllerWith(matrixSynapseService);
  }

  private MatrixMessageController controllerWith(MatrixSynapseService service) {
    return new MatrixMessageController(
        service,
        sessionService,
        chatService,
        authenticatedUser,
        consultantService,
        userService,
        matrixCredentialClient,
        chatPermissionVerifier,
        conversationFeatureGate,
        Optional.<RedisMessageMirrorService>empty());
  }

  @Test
  void retainedRejectedHistoryCannotMintTokenOrSendMessage() {
    var session = sessionWithMatrixRoom();
    session.setStatus(Session.SessionStatus.REJECTED);
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser)).thenReturn(session);
    var response =
        controller.sendMessage(SESSION_ID, Map.of("message", "Blocked synthetic message"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void uploadFile_ShouldBeRefusedBeforeAnyMatrixCall_WhenAttachmentsAreSwitchedOff() {
    var session = sessionWithMatrixRoom();
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(session));
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser)).thenReturn(session);
    doThrow(
            new de.caritas.cob.userservice.api.exception.httpresponses
                .CustomValidationHttpStatusException(
                de.caritas.cob.userservice.api.exception.httpresponses.customheader
                    .HttpStatusExceptionReason.FEATURE_DISABLED,
                HttpStatus.FORBIDDEN))
        .when(conversationFeatureGate)
        .require(
            List.of(
                de.caritas.cob.userservice.api.service.conversationfeature.ConversationFeature
                    .MEDIA_UPLOAD),
            session);

    // Not turned into a 500 by the controller's own catch-all.
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException
            .class,
        () ->
            controller.uploadFile(
                SESSION_ID,
                new MockMultipartFile("file", "photo.png", "image/png", "bytes".getBytes())));
    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void uploadFile_ShouldAskTheVoiceSwitchToo_WhenTheFileBecomesAVoiceMessage() {
    var session = sessionWithMatrixRoom();
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(session));
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser)).thenReturn(session);
    doThrow(
            new de.caritas.cob.userservice.api.exception.httpresponses
                .CustomValidationHttpStatusException(
                de.caritas.cob.userservice.api.exception.httpresponses.customheader
                    .HttpStatusExceptionReason.FEATURE_DISABLED,
                HttpStatus.FORBIDDEN))
        .when(conversationFeatureGate)
        .require(
            List.of(
                de.caritas.cob.userservice.api.service.conversationfeature.ConversationFeature
                    .MEDIA_UPLOAD,
                de.caritas.cob.userservice.api.service.conversationfeature.ConversationFeature
                    .VOICE_MESSAGES),
            session);

    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException
            .class,
        () ->
            controller.uploadFile(
                SESSION_ID,
                new MockMultipartFile("file", "note.mp3", "audio/mpeg", "bytes".getBytes())));
    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void retainedRejectedHistoryCannotMintTokenOrUploadFile() {
    var session = sessionWithMatrixRoom();
    session.setStatus(Session.SessionStatus.REJECTED);
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(session));
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser)).thenReturn(session);
    var response =
        controller.uploadFile(
            SESSION_ID,
            new MockMultipartFile("file", "synthetic.txt", "text/plain", "blocked".getBytes()));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void sendMessage_ShouldThrowForbiddenBeforeMatrixCall_WhenSessionAccessIsDenied() {
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenThrow(new ForbiddenException("No permission"));

    assertThrows(
        ForbiddenException.class,
        () -> controller.sendMessage(SESSION_ID, Map.of("message", "blocked")));

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void getMessages_ShouldThrowForbiddenBeforeMatrixCall_WhenSessionAccessIsDenied() {
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(sessionWithMatrixRoom()));
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenThrow(new ForbiddenException("No permission"));

    assertThrows(ForbiddenException.class, () -> controller.getMessages(SESSION_ID));

    verifyNoInteractions(matrixSynapseService);
    verifyNoInteractions(matrixCredentialClient);
  }

  @Test
  void unassignedEnquiryReadsMessagesWithAgencyIdentityOnly() {
    var session = sessionWithMatrixRoom();
    session.setAgencyId(7L);
    session.setConsultant(null);
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(session));
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser)).thenReturn(session);
    when(authenticatedUser.getRoles()).thenReturn(Set.of("consultant"));
    var identity =
        new de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO();
    identity.setMatrixUserId("@agency:matrix");
    when(matrixCredentialClient.fetchMatrixCredentials(7L)).thenReturn(Optional.of(identity));
    when(matrixSynapseService.loginAsUserAccessToken("@agency:matrix")).thenReturn("agency-token");
    when(matrixSynapseService.getRoomMessages(MATRIX_ROOM_ID, "agency-token"))
        .thenReturn(List.of(Map.of("event_id", "$enquiry")));

    var response = controller.getMessages(SESSION_ID);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(matrixSynapseService).getRoomMessages(MATRIX_ROOM_ID, "agency-token");
    verify(matrixSynapseService, never())
        .loginUser(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  void unavailableAgencyImpersonationDoesNotFallBackToPasswordOrReadMessages(String token) {
    var session = sessionWithMatrixRoom();
    session.setAgencyId(7L);
    session.setConsultant(null);
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(session));
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser)).thenReturn(session);
    when(authenticatedUser.getRoles()).thenReturn(Set.of("consultant"));
    var identity =
        new de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO();
    identity.setMatrixUserId("@agency:matrix");
    when(matrixCredentialClient.fetchMatrixCredentials(7L)).thenReturn(Optional.of(identity));
    when(matrixSynapseService.loginAsUserAccessToken("@agency:matrix")).thenReturn(token);

    assertEquals(HttpStatus.BAD_GATEWAY, controller.getMessages(SESSION_ID).getStatusCode());

    verify(matrixSynapseService, never())
        .getRoomMessages(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    verify(matrixSynapseService, never())
        .loginUser(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"absent", "invalid"})
  void unavailableAgencyIdentityIsNotReportedAsEmptyConversation(String state) {
    var session = sessionWithMatrixRoom();
    session.setAgencyId(7L);
    session.setConsultant(null);
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(session));
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser)).thenReturn(session);
    when(authenticatedUser.getRoles()).thenReturn(Set.of("consultant"));
    var identity =
        new de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO();
    identity.setMatrixUserId("invalid");
    when(matrixCredentialClient.fetchMatrixCredentials(7L))
        .thenReturn("absent".equals(state) ? Optional.empty() : Optional.of(identity));

    assertEquals(HttpStatus.BAD_GATEWAY, controller.getMessages(SESSION_ID).getStatusCode());
    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void getMessages_ShouldThrowForbiddenBeforeMatrixCall_WhenChatAccessIsDenied() {
    var chat = chatWithMatrixRoom();
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.empty());
    when(chatService.getChat(SESSION_ID)).thenReturn(Optional.of(chat));
    doThrow(new ForbiddenException("No permission"))
        .when(chatPermissionVerifier)
        .verifyPermissionForChat(chat);

    assertThrows(ForbiddenException.class, () -> controller.getMessages(SESSION_ID));

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void sendMessage_ShouldSendMatrixMessage_WhenSessionAccessIsAllowed() {
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenReturn(sessionWithMatrixRoom());
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(authenticatedUser.getRoles()).thenReturn(Set.of("user"));
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(userService.getUser(USER_ID)).thenReturn(Optional.of(userWithMatrixId()));
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn("matrix-token");
    when(matrixSynapseService.sendMessage(MATRIX_ROOM_ID, "hello", "matrix-token"))
        .thenReturn(Map.of("event_id", "$event"));

    var response = controller.sendMessage(SESSION_ID, Map.of("message", "hello"));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(matrixSynapseService).sendMessage(MATRIX_ROOM_ID, "hello", "matrix-token");
  }

  @Test
  void getCurrentUserMatrixToken_ShouldReturnNotFound_WhenMatrixUserMissing() {
    // Business reason: clients should get explicit 404 when no Matrix identity is provisioned yet.
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(userService.getUser(USER_ID)).thenReturn(Optional.of(userWithMatrixId("")));

    var response = controller.getCurrentUserMatrixToken("ORISO_WEB_DEVICE_ONE");

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  void getCurrentUserMatrixToken_ShouldReturnBadRequest_WhenDeviceIdIsInvalid() {
    var response = controller.getCurrentUserMatrixToken("bad/device");

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    verifyNoInteractions(matrixSynapseService, consultantService, userService);
  }

  @Test
  void getCurrentUserMatrixToken_ShouldReturnOk_WhenTokenMintingSucceeds() {
    // Business reason: authenticated users need short-lived Matrix browser tokens for messaging
    // features.
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(userService.getUser(USER_ID)).thenReturn(Optional.of(userWithMatrixId()));
    when(matrixSynapseService.loginBrowserDevice(MATRIX_USER_ID, "ORISO_WEB_DEVICE_ONE"))
        .thenReturn(
            Map.of(
                "access_token", "abc",
                "user_id", MATRIX_USER_ID,
                "device_id", "dev1",
                "interactive_auth_password", "ephemeral-uia-password"));

    var response = controller.getCurrentUserMatrixToken("ORISO_WEB_DEVICE_ONE");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    var body = new ObjectMapper().convertValue(response.getBody(), Map.class);
    assertEquals("abc", body.get("accessToken"));
    assertEquals("dev1", body.get("deviceId"));
    assertEquals("ephemeral-uia-password", body.get("uiaPassword"));
    assertEquals("no-store", response.getHeaders().getCacheControl());
  }

  @ParameterizedTest
  @MethodSource("synapseRateLimitDelays")
  void browserLoginReturnsSafeRateLimitWithUsableRetryAfter(
      String upstreamRetryAfter, String retryAfterMsJson, String expectedRetryAfter)
      throws Exception {
    var restTemplate = new RestTemplate();
    var synapse = MockRestServiceServer.bindTo(restTemplate).build();
    var mvc = browserLoginMvc(restTemplate);

    expectBrowserRateLimit(synapse, upstreamRetryAfter, retryAfterMsJson);

    var response =
        mvc.perform(get("/service/matrix/me/token").param("deviceId", BROWSER_DEVICE_ID))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn()
            .getResponse();

    assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo(expectedRetryAfter);
    assertThat(new ObjectMapper().readTree(response.getContentAsString()))
        .isEqualTo(new ObjectMapper().readTree("{\"error\":\"Matrix token unavailable\"}"));
    synapse.verify();
  }

  private static Stream<Arguments> synapseRateLimitDelays() {
    return Stream.of(
        Arguments.of(null, "16006", "17"),
        Arguments.of(null, "1", "1"),
        Arguments.of("120", "16006", "120"),
        Arguments.of("1", "16006", "17"),
        Arguments.of("invalid", "21037", "22"),
        Arguments.of("17", "null", "17"),
        Arguments.of("0", "0", null),
        Arguments.of(null, "-10", null),
        Arguments.of(null, "90000, \"retry_after_ms\": 1000", null),
        Arguments.of(null, "21037} {\"unexpected\": true", null),
        Arguments.of(null, "\"16006\"", null),
        Arguments.of(null, "16006.5", null),
        Arguments.of("2147483648", "2147483647001", null),
        Arguments.of(null, "9223372036854775808", null));
  }

  @Test
  void browserLoginNormalizesAnUpstreamRetryAfterDateToSeconds() throws Exception {
    var restTemplate = new RestTemplate();
    var synapse = MockRestServiceServer.bindTo(restTemplate).build();
    var mvc = browserLoginMvc(restTemplate);
    var deadline = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.SECONDS);
    var retryAfterDate =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(deadline.atZone(ZoneOffset.UTC));
    expectBrowserRateLimit(synapse, retryAfterDate, "null");

    var earliest = Instant.now();
    var response =
        mvc.perform(get("/matrix/me/token").param("deviceId", BROWSER_DEVICE_ID))
            .andExpect(status().isTooManyRequests())
            .andReturn()
            .getResponse();
    var latest = Instant.now();

    var delay = Long.parseLong(response.getHeader(HttpHeaders.RETRY_AFTER));
    assertThat(latest.plusSeconds(delay)).isAfterOrEqualTo(deadline);
    assertThat(earliest.plusSeconds(delay)).isBefore(deadline.plusSeconds(1));
    synapse.verify();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void browserLoginKeepsServiceUnavailableForOtherSynapseFailures(boolean passwordUpdateFails)
      throws Exception {
    var restTemplate = new RestTemplate();
    var synapse = MockRestServiceServer.bindTo(restTemplate).build();
    var mvc = browserLoginMvc(restTemplate);

    expectAdminLogin(synapse);
    if (passwordUpdateFails) {
      expectBrowserPasswordUpdate(
          synapse, withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "30"));
    } else {
      expectBrowserPasswordUpdate(synapse, withSuccess("{}", MediaType.APPLICATION_JSON));
      expectBrowserLogin(
          synapse,
          withStatus(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "30"));
    }

    mvc.perform(get("/service/matrix/me/token").param("deviceId", BROWSER_DEVICE_ID))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
    synapse.verify();
  }

  private MockMvc browserLoginMvc(RestTemplate restTemplate) {
    var config = new MatrixConfig();
    config.setApiUrl(MATRIX_BASE_URL);
    config.setAdminUsername("matrix-admin");
    config.setAdminPassword("synthetic-admin-password");
    config.setRegistrationSharedSecret("synthetic-shared-secret");
    var service =
        new MatrixSynapseService(
            config,
            restTemplate,
            restTemplate,
            mock(MatrixRoomClient.class),
            mock(MatrixMediaClient.class),
            mock(MatrixIdentifierRedactor.class));
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(userService.getUser(USER_ID)).thenReturn(Optional.of(userWithMatrixId()));
    return MockMvcBuilders.standaloneSetup(controllerWith(service)).build();
  }

  private static void expectAdminLogin(MockRestServiceServer synapse) {
    synapse
        .expect(requestTo(MATRIX_LOGIN_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.user").value("matrix-admin"))
        .andRespond(
            withSuccess(
                "{\"access_token\":\"synthetic-admin-token\"}", MediaType.APPLICATION_JSON));
  }

  private static void expectBrowserPasswordUpdate(
      MockRestServiceServer synapse, DefaultResponseCreator response) {
    synapse
        .expect(requestTo(MATRIX_PASSWORD_UPDATE_URL))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(jsonPath("$.logout_devices").value(false))
        .andRespond(response);
  }

  private static void expectBrowserRateLimit(
      MockRestServiceServer synapse, String upstreamRetryAfter, String retryAfterMsJson) {
    expectAdminLogin(synapse);
    expectBrowserPasswordUpdate(synapse, withSuccess("{}", MediaType.APPLICATION_JSON));
    var response =
        withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "errcode": "M_LIMIT_EXCEEDED",
                  "error": "upstream private diagnostic",
                  "retry_after_ms": %s
                }
                """
                    .formatted(retryAfterMsJson));
    if (upstreamRetryAfter != null) {
      response.header(HttpHeaders.RETRY_AFTER, upstreamRetryAfter);
    }
    expectBrowserLogin(synapse, response);
  }

  private static void expectBrowserLogin(
      MockRestServiceServer synapse, DefaultResponseCreator response) {
    synapse
        .expect(requestTo(MATRIX_LOGIN_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.user").value(MATRIX_USER_ID))
        .andExpect(jsonPath("$.device_id").value(BROWSER_DEVICE_ID))
        .andRespond(response);
  }

  @Test
  void browserLoginResponseKeepsCredentialsOnWireButOutOfMvcTraceLogs() throws Exception {
    String token = "synthetic-browser-token-for-log-test";
    String password = "synthetic-stable-uia-credential-for-log-test";
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(userService.getUser(USER_ID)).thenReturn(Optional.of(userWithMatrixId()));
    when(matrixSynapseService.loginBrowserDevice(MATRIX_USER_ID, "DEVICE_A"))
        .thenReturn(
            Map.of(
                "access_token",
                token,
                "user_id",
                MATRIX_USER_ID,
                "device_id",
                "DEVICE_A",
                "interactive_auth_password",
                password));

    var logger =
        (Logger) LoggerFactory.getLogger("org.springframework.web.servlet.mvc.method.annotation");
    var previousLevel = logger.getLevel();
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.TRACE);
    try {
      var mvc = MockMvcBuilders.standaloneSetup(controller).build();
      var result =
          mvc.perform(get("/matrix/me/token").param("deviceId", "DEVICE_A"))
              .andExpect(status().isOk())
              .andExpect(header().string("Cache-Control", "no-store"))
              .andReturn();
      var json = new ObjectMapper().readTree(result.getResponse().getContentAsString());
      assertEquals(token, json.path("accessToken").asText());
      assertEquals(password, json.path("uiaPassword").asText());
      assertEquals(MATRIX_USER_ID, json.path("userId").asText());
      assertEquals("DEVICE_A", json.path("deviceId").asText());
      assertEquals(55 * 60 * 1000L, json.path("expiresInMs").asLong());
      var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
      assertThat(messages).anyMatch(message -> message.startsWith("Writing ["));
      assertThat(messages)
          .noneMatch(message -> message.contains(password) || message.contains(token));
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
      appender.stop();
    }
  }

  @Test
  void sendMessage_ShouldReturnUnauthorized_WhenMatrixTokenUnavailable() {
    // Business reason: write operations must fail safely when Matrix auth token cannot be minted.
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenReturn(sessionWithMatrixRoom());
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(authenticatedUser.getRoles()).thenReturn(Set.of("user"));
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(userService.getUser(USER_ID)).thenReturn(Optional.of(userWithMatrixId()));
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn(null);

    var response = controller.sendMessage(SESSION_ID, Map.of("message", "hello"));

    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    verify(matrixSynapseService, never())
        .sendMessage(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void getMessages_ShouldReturnOkWithMessages_WhenRoomAndTokenAvailable() {
    // Business reason: authorized users must receive message history for active Matrix sessions.
    var session = sessionWithMatrixRoom();
    session.setConsultant(consultantWithMatrixId("ignored"));
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(session));
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser)).thenReturn(session);
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(authenticatedUser.getRoles()).thenReturn(Set.of("user"));
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(userService.getUser(USER_ID)).thenReturn(Optional.of(userWithMatrixId()));
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn("matrix-token");
    when(matrixSynapseService.getRoomMessages(MATRIX_ROOM_ID, "matrix-token"))
        .thenReturn(List.of(Map.of("event_id", "$1")));

    var response = controller.getMessages(SESSION_ID);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    var body = assertInstanceOf(Map.class, response.getBody());
    assertEquals(true, body.get("success"));
  }

  @Test
  void syncMessages_ShouldReturnSyncPayload_WhenSessionRoomAndTokenExist() {
    // Business reason: chat UI long-polling depends on sync endpoint forwarding Matrix updates.
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenReturn(sessionWithMatrixRoom());
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(userService.getUser(USER_ID)).thenReturn(Optional.of(userWithMatrixId()));
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn("token");
    when(matrixSynapseService.syncRoom(MATRIX_ROOM_ID, "token", USERNAME, 30000))
        .thenReturn(Map.of("messages", List.of("m1")));

    var response = controller.syncMessages(SESSION_ID);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    var body = assertInstanceOf(Map.class, response.getBody());
    assertTrue(body.containsKey("messages"));
  }

  @Test
  void uploadFile_ShouldReturnNotFound_WhenRoomCannotBeResolved() {
    // Business reason: uploads must be blocked for non-existent or unauthorized sessions/chats.
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.empty());
    when(chatService.getChat(SESSION_ID)).thenReturn(Optional.empty());
    var file = new MockMultipartFile("file", "a.txt", "text/plain", "a".getBytes());

    var response = controller.uploadFile(SESSION_ID, file);

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  void uploadFile_ShouldReturnOk_WhenUploadSucceeds() {
    // Business reason: successful media uploads must return Matrix response metadata for message
    // rendering.
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(sessionWithMatrixRoom()));
    when(sessionService.assertUserHasAccess(SESSION_ID, authenticatedUser))
        .thenReturn(sessionWithMatrixRoom());
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(userService.getUser(USER_ID)).thenReturn(Optional.of(userWithMatrixId()));
    when(matrixSynapseService.loginAsUserAccessToken(MATRIX_USER_ID)).thenReturn("token");
    var file = new MockMultipartFile("file", "a.txt", "text/plain", "a".getBytes());
    when(matrixSynapseService.uploadFile(file, MATRIX_ROOM_ID, "token"))
        .thenReturn(Map.of("content_uri", "mxc://server/id"));

    var response = controller.uploadFile(SESSION_ID, file);

    assertEquals(HttpStatus.OK, response.getStatusCode());
  }

  @Test
  void downloadFile_ShouldReturnOkWithBytes_WhenAdminTokenPresent() {
    // Business reason: authenticated users must be able to fetch shared media assets.
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(matrixSynapseService.getAdminToken()).thenReturn("admin-token");
    when(matrixSynapseService.downloadFile("matrix.org", "media-1", "admin-token"))
        .thenReturn("hello".getBytes());

    var response = controller.downloadFile("matrix.org", "media-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getHeaders().getFirst("Content-Type"));
  }

  private Session sessionWithMatrixRoom() {
    return Session.builder()
        .id(SESSION_ID)
        .user(userWithMatrixId())
        .consultingTypeId(0)
        .registrationType(Session.RegistrationType.REGISTERED)
        .postcode("12345")
        .status(Session.SessionStatus.IN_PROGRESS)
        .matrixRoomId(MATRIX_ROOM_ID)
        .teamSession(false)
        .build();
  }

  private Chat chatWithMatrixRoom() {
    return Chat.builder()
        .id(SESSION_ID)
        .topic("group")
        .initialStartDate(LocalDateTime.now())
        .startDate(LocalDateTime.now())
        .duration(60)
        .matrixRoomId(MATRIX_ROOM_ID)
        .build();
  }

  private User userWithMatrixId() {
    return User.builder()
        .userId(USER_ID)
        .username(USERNAME)
        .email("seeker@example.org")
        .matrixUserId(MATRIX_USER_ID)
        .languageFormal(false)
        .build();
  }

  private User userWithMatrixId(String matrixUserId) {
    return User.builder()
        .userId(USER_ID)
        .username(USERNAME)
        .email("seeker@example.org")
        .matrixUserId(matrixUserId)
        .languageFormal(false)
        .build();
  }

  private de.caritas.cob.userservice.api.model.Consultant consultantWithMatrixId(
      String matrixUserId) {
    return de.caritas.cob.userservice.api.model.Consultant.builder()
        .id("consultant-id")
        .username("consultant-user")
        .firstName("Con")
        .lastName("Sultant")
        .email("consultant@example.org")
        .matrixUserId(matrixUserId)
        .build();
  }
}
