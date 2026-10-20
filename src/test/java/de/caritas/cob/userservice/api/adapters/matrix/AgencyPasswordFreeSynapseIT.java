package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.web.controller.MatrixMessageController;
import de.caritas.cob.userservice.api.config.observability.LiveChatDiagnosticMetrics;
import de.caritas.cob.userservice.api.facade.TeamDiscussionFacade;
import de.caritas.cob.userservice.api.facade.assignsession.AssignEnquiryFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.session.AgencyLateJoinerMembershipService;
import de.caritas.cob.userservice.api.service.session.AgencyPreAssignmentRoomService;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionCreationWriter;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionFeatureGate;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionParticipantWriter;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionRoomCleanupService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * #289 functional Matrix boundary: six real production callers, real HTTP/Synapse permissions,
 * synthetic users only. Domain repositories and authenticated caller context are test doubles; this
 * is not a browser/full-stack or Dev acceptance test. Docker is required, never silently skipped.
 */
@Testcontainers
@Timeout(120)
class AgencyPasswordFreeSynapseIT {

  private static final String SERVER = "matrix-browser-uia.test";
  private static final String REGISTRATION_SECRET = "public-disposable-uia-registration-fixture";
  private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
  private static final RestTemplate HTTP = httpClient();
  private static final ObjectMapper JSON = new ObjectMapper();

  @Container
  private static final GenericContainer<?> SYNAPSE =
      new GenericContainer<>(DockerImageName.parse("ghcr.io/element-hq/synapse:v1.158.0"))
          .withExposedPorts(8008)
          .withEnv("UID", "0")
          .withEnv("GID", "0")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("matrix-browser-uia/homeserver.yaml"),
              "/data/homeserver.yaml")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("matrix-browser-uia/test.signing.key"),
              "/data/test.signing.key")
          .waitingFor(Wait.forHttp("/_matrix/client/versions").forStatusCode(200))
          .withStartupTimeout(Duration.ofMinutes(2));

  private static String baseUrl;
  private static String adminToken;
  private static final AtomicLong requestedExpiry = new AtomicLong();
  private static MatrixSynapseService matrix;
  private static MatrixSessionRoomGateway gateway;
  private AgencyMatrixCredentialClient identities;
  private ConsultantRepository consultants;
  private SessionService sessions;
  private SessionRepository sessionRepository;
  private TeamDiscussionRepository discussions;
  private AgencySilentMembershipService silentMembership;
  private Session session;
  private Consultant consultant;
  private String agencyId;
  private String seekerId;

  @BeforeAll
  static void startSyntheticAccounts() throws Exception {
    baseUrl = "http://" + SYNAPSE.getHost() + ":" + SYNAPSE.getMappedPort(8008);
    var nonce = get("/_synapse/admin/v1/register", null).get("nonce");
    var mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(REGISTRATION_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
    var registrationMac =
        Hex.encodeHexString(
            mac.doFinal(
                (nonce + "\0testadmin\0" + ADMIN_PASSWORD + "\0admin")
                    .getBytes(StandardCharsets.UTF_8)));
    var response =
        post(
            "/_synapse/admin/v1/register",
            null,
            Map.of(
                "nonce",
                nonce,
                "username",
                "testadmin",
                "password",
                ADMIN_PASSWORD,
                "admin",
                true,
                "mac",
                registrationMac));
    adminToken = (String) response.get("access_token");
    assertThat(adminToken).isNotBlank();
  }

  @BeforeEach
  void setUp() {
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    agencyId = createUser("agency-" + suffix);
    seekerId = createUser("seeker-" + suffix);
    consultant = new Consultant();
    consultant.setId("counsellor-" + suffix);
    consultant.setMatrixUserId(createUser("counsellor-" + suffix));
    var user = new User();
    user.setMatrixUserId(seekerId);
    user.setUsername("synthetic seeker");
    session = new Session();
    session.setId(42L);
    session.setAgencyId(7L);
    session.setTenantId(1L);
    session.setUser(user);
    session.setStatus(Session.SessionStatus.NEW);
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    session.setEnquiryMessageDate(LocalDateTime.now());

    // Production uses a singleton adapter/admin login cache; do not create a login burst per test.
    if (matrix == null) {
      var config = new MatrixConfig();
      config.setApiUrl(baseUrl);
      config.setServerName(SERVER);
      config.setAdminUsername("testadmin");
      config.setAdminPassword(ADMIN_PASSWORD);
      var transport = httpClient();
      transport
          .getInterceptors()
          .add(
              (request, body, execution) -> {
                if (request.getURI().getPath().endsWith("/login")) {
                  try {
                    var payload = JSON.readTree(body);
                    if (request.getURI().getPath().startsWith("/_synapse/admin/")) {
                      requestedExpiry.set(payload.path("valid_until_ms").asLong());
                    } else {
                      assertThat(payload.path("user").asText()).isEqualTo("testadmin");
                    }
                  } catch (java.io.IOException exception) {
                    throw new AssertionError("Invalid login request", exception);
                  }
                }
                return execution.execute(request, body);
              });
      matrix =
          new MatrixSynapseService(
              config,
              transport,
              transport,
              new MatrixRoomClient(config, transport, mock(LiveChatDiagnosticMetrics.class)),
              new MatrixMediaClient(config, transport),
              MatrixIdentifierRedactor.withKey("public-disposable-agency-log-fixture"));
      gateway = new MatrixSessionRoomGateway(matrix, config);
    }
    identities = mock(AgencyMatrixCredentialClient.class);
    var identity = new AgencyMatrixCredentialsDTO();
    identity.setMatrixUserId(agencyId);
    when(identities.fetchMatrixCredentials(7L)).thenReturn(Optional.of(identity));
    consultants = mock(ConsultantRepository.class);
    sessions = mock(SessionService.class);
    sessionRepository = mock(SessionRepository.class);
    discussions = mock(TeamDiscussionRepository.class);
    silentMembership =
        new AgencySilentMembershipService(
            consultants,
            gateway,
            mock(UserHelper.class),
            mock(UsernameTranscoder.class),
            mock(ConsultantDisplayNameResolver.class));
  }

  @Test
  void boundedImpersonationKeepsIdentityAndDoesNotGrantAdminOrCreateDevice() throws Exception {
    var before = System.currentTimeMillis();
    var token = matrix.loginAsUserAccessToken(agencyId);
    assertThat(requestedExpiry.get())
        .isBetween(before + 590_000L, System.currentTimeMillis() + 600_000L);
    assertThat(get("/_matrix/client/v3/account/whoami", token)).containsEntry("user_id", agencyId);
    assertThat(get("/_matrix/client/v3/devices", token).get("devices")).isEqualTo(List.of());
    assertThatThrownBy(
            () -> post("/_synapse/admin/v1/users/" + seekerId + "/login", token, Map.of()))
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    var expiring = (String) matrix.loginAsUser(agencyId, 1500L).get("access_token");
    Thread.sleep(2000L);
    assertThatThrownBy(() -> get("/_matrix/client/v3/account/whoami", expiring))
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void holdingRoomInvitesAndJoinsDepartmentAndSeekerWithoutPassword() {
    when(consultants.findByConsultantAgenciesAgencyIdAndDeleteDateIsNull(7L))
        .thenReturn(List.of(consultant));
    holdingRoom();
    var token = matrix.loginAsUserAccessToken(agencyId);
    assertThat(members(session.getMatrixRoomId(), token))
        .contains(agencyId, seekerId, consultant.getMatrixUserId());
    verify(sessions).saveSession(session);
  }

  @Test
  void lateJoinAndRevocationUsePasswordFreeAgencyIdentity() {
    holdingRoom();
    when(sessionRepository.findByAgencyIdAndStatusAndConsultantIsNull(
            7L, Session.SessionStatus.NEW))
        .thenReturn(List.of(session));
    var lateJoin =
        new AgencyLateJoinerMembershipService(
            sessionRepository,
            identities,
            gateway,
            silentMembership,
            discussions,
            mock(ConsultantAgencyRepository.class),
            mock(TeamDiscussionParticipantWriter.class),
            mock(
                de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService
                    .class));
    assertThat(lateJoin.joinConsultantIntoOpenEnquiryRooms(consultant, 7L)).isEqualTo(1);
    var token = matrix.loginAsUserAccessToken(agencyId);
    assertThat(members(session.getMatrixRoomId(), token)).contains(consultant.getMatrixUserId());
    assertThat(lateJoin.removeConsultantFromAgencyRooms(consultant, 7L)).isEqualTo(1);
    assertThat(members(session.getMatrixRoomId(), token))
        .doesNotContain(consultant.getMatrixUserId());
  }

  @Test
  void authorizedUnassignedMessageReadAndAgencySystemMessageFallbackWork() {
    holdingRoom();
    assertThat(
            matrix.sendMessage(
                session.getMatrixRoomId(),
                "synthetic enquiry",
                matrix.loginAsUserAccessToken(seekerId)))
        .containsKey("event_id");
    var authenticated = mock(AuthenticatedUser.class);
    when(authenticated.getRoles()).thenReturn(java.util.Set.of("consultant"));
    when(sessions.getSession(42L)).thenReturn(Optional.of(session));
    when(sessions.assertUserHasAccess(42L, authenticated)).thenReturn(session);
    var controller =
        new MatrixMessageController(
            matrix,
            sessions,
            mock(ChatService.class),
            authenticated,
            mock(ConsultantService.class),
            mock(UserService.class),
            identities,
            mock(ChatPermissionVerifier.class),
            Optional.empty());
    var response = controller.getMessages(42L);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isInstanceOf(Map.class);
    assertThat(objectMap(response.getBody())).containsKey("success");
    assertThat(((List<?>) ((Map<?, ?>) response.getBody()).get("messages"))).isNotEmpty();

    // Force the final agency fallback, not the usual seeker/counsellor identity branch.
    session.getUser().setMatrixUserId(null);
    var systemMessages =
        new MatrixSessionSystemMessageService(
            matrix, identities, sessions, mock(ConsultantService.class));
    systemMessages.postUserLeftChatMessage(session);
    var events =
        matrix.getRoomMessages(session.getMatrixRoomId(), matrix.loginAsUserAccessToken(agencyId));
    assertThat(events)
        .anySatisfy(
            event -> {
              assertThat(event.get("sender")).isEqualTo(agencyId);
              assertThat(objectMap(event.get("content")))
                  .containsEntry(
                      "body",
                      "[SYSTEM_NOTIFICATION]{\"type\":\"USER_LEFT_CHAT\",\"username\":\"synthetic seeker\"}");
            });
  }

  @Test
  void teamDiscussionCreatesJoinsAndArchivesReadOnlyWithoutPassword() {
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));
    when(consultants.findById(consultant.getId())).thenReturn(Optional.of(consultant));
    var relations = mock(ConsultantAgencyRepository.class);
    when(relations.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(consultant.getId(), 7L))
        .thenReturn(true);
    when(discussions.saveAndFlush(any(TeamDiscussion.class)))
        .thenAnswer(
            call -> {
              TeamDiscussion discussion = call.getArgument(0);
              discussion.setId(99L);
              when(discussions.findBySessionId(42L)).thenReturn(Optional.of(discussion));
              return discussion;
            });
    var facade =
        new TeamDiscussionFacade(
            sessionRepository,
            consultants,
            relations,
            discussions,
            matrix,
            identities,
            mock(TeamDiscussionFeatureGate.class),
            new TeamDiscussionCreationWriter(discussions),
            mock(TeamDiscussionParticipantWriter.class),
            mock(TeamDiscussionRoomCleanupService.class));
    var view = facade.getOrCreateDiscussion(42L, consultant.getId());
    var consultantToken = matrix.loginAsUserAccessToken(consultant.getMatrixUserId());
    assertThat(matrix.sendMessage(view.matrixRoomId(), "before archive", consultantToken))
        .containsKey("event_id");
    facade.archiveDiscussionIfPresent(session);
    var persisted = discussions.findBySessionId(42L).orElseThrow();
    assertThat(persisted.getStatus()).isEqualTo(TeamDiscussion.Status.ARCHIVED);
    assertThat(persisted.isReadOnlyApplied()).isTrue();
    assertThat(matrix.sendMessage(view.matrixRoomId(), "must be denied", consultantToken))
        .doesNotContainKey("event_id");
  }

  @Test
  void acceptingEnquiryPreservesRoomHistoryAndGrantsCounsellorPower() {
    holdingRoom();
    var originalRoom = session.getMatrixRoomId();
    matrix.sendMessage(originalRoom, "preserved enquiry", matrix.loginAsUserAccessToken(seekerId));
    var facade =
        new AssignEnquiryFacade(
            sessions,
            de.caritas.cob.userservice.api.testHelper.PermittingDpaOwnerFixture.policy(),
            gateway,
            mock(
                de.caritas.cob.userservice.api.facade.assignsession.SessionToConsultantVerifier
                    .class),
            mock(de.caritas.cob.userservice.api.service.statistics.StatisticsService.class),
            mock(de.caritas.cob.userservice.api.facade.EmailNotificationFacade.class),
            mock(jakarta.servlet.http.HttpServletRequest.class),
            consultants,
            mock(UserRepository.class),
            mock(UserHelper.class),
            mock(UsernameTranscoder.class),
            mock(ConsultantDisplayNameResolver.class),
            identities,
            mock(
                de.caritas.cob.userservice.api.service.notification.EventNotificationService.class),
            mock(
                de.caritas.cob.userservice.api.facade.assignsession
                    .AnonymousEnquiryDepartmentResolver.class),
            mock(de.caritas.cob.userservice.api.facade.SessionSupervisorFacade.class),
            mock(TeamDiscussionFacade.class),
            mock(de.caritas.cob.userservice.api.service.matrix.InquiryAcceptanceNoticeStore.class),
            mock(
                de.caritas.cob.userservice.api.service.matrix.InquiryAcceptanceNoticeDelivery
                    .class));
    facade.assignRegisteredEnquiry(session, consultant);
    assertThat(session.getMatrixRoomId()).isEqualTo(originalRoom);
    var token = matrix.loginAsUserAccessToken(consultant.getMatrixUserId());
    assertThat(members(originalRoom, token))
        .contains(seekerId, consultant.getMatrixUserId())
        .doesNotContain(agencyId);
    var power =
        get("/_matrix/client/v3/rooms/" + originalRoom + "/state/m.room.power_levels", token);
    assertThat(objectMap(power.get("users"))).containsEntry(consultant.getMatrixUserId(), 100);
    assertThat(matrix.getRoomMessages(originalRoom, token))
        .anySatisfy(
            event ->
                assertThat(objectMap(event.get("content")))
                    .containsEntry("body", "preserved enquiry"));
  }

  private void holdingRoom() {
    new AgencyPreAssignmentRoomService(identities, gateway, sessions, silentMembership)
        .ensureHoldingRoom(session, session.getUser());
    assertThat(session.getMatrixRoomId()).isNotBlank();
  }

  private static String createUser(String localpart) {
    var id = "@" + localpart + ":" + SERVER;
    // No password at all on the agency (or other synthetic users): password login cannot work.
    HTTP.exchange(
        baseUrl + "/_synapse/admin/v2/users/{id}",
        HttpMethod.PUT,
        entity(adminToken, Map.of("admin", false)),
        Map.class,
        id);
    return id;
  }

  private static java.util.Set<String> members(String roomId, String token) {
    return objectMap(
            get("/_matrix/client/v3/rooms/" + roomId + "/joined_members", token).get("joined"))
        .keySet();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMap(Object value) {
    return (Map<String, Object>) value;
  }

  private static RestTemplate httpClient() {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    factory.setReadTimeout(Duration.ofSeconds(15));
    return new RestTemplate(factory);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> get(String path, String token) {
    return HTTP.exchange(baseUrl + path, HttpMethod.GET, entity(token, null), Map.class).getBody();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> post(String path, String token, Map<String, Object> body) {
    return HTTP.postForObject(baseUrl + path, entity(token, body), Map.class);
  }

  private static HttpEntity<Map<String, Object>> entity(String token, Map<String, Object> body) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) headers.setBearerAuth(token);
    return new HttpEntity<>(body, headers);
  }
}
