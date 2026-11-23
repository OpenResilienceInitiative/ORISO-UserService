package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.config.observability.LiveChatDiagnosticMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * ORISO-Frontend#2229: blurring a passage ({@code org.oriso.passage_blur}) is a moderator act, and
 * lifting a blur redacts it. A real Synapse decides who may send and redact, for each kind of room
 * UserService creates. Moderators (product owner, 12 Nov 2026): the assigned counsellor in a
 * one-to-one or live chat (power 100), the owner of a group (the room creator, 100) and its named
 * moderators (co-moderators and owners who did not create the room, raised to 50). Advice seekers
 * (0), supervisors (10) and plain participants never can.
 */
@Testcontainers
@Timeout(120)
class PassageBlurPowerLevelSynapseIT {

  private static final String SERVER_NAME = "matrix-browser-uia.test";
  private static final String REGISTRATION_SECRET = "public-disposable-uia-registration-fixture";
  private static final String ADMIN_PASSWORD = "public-disposable-uia-admin-fixture";
  private static final String USER_PASSWORD = "public-disposable-blur-user-fixture";
  private static final String PASSAGE_BLUR = "org.oriso.passage_blur";
  private static final AtomicInteger TXN = new AtomicInteger();

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

  private static final RestTemplate HTTP = new RestTemplate();
  private static String baseUrl;
  private static String adminToken;
  private static MatrixRoomClient roomClient;

  @BeforeAll
  static void provisionSyntheticAdmin() throws Exception {
    baseUrl = "http://" + SYNAPSE.getHost() + ":" + SYNAPSE.getMappedPort(8008);
    var nonce = HTTP.getForObject(baseUrl + "/_synapse/admin/v1/register", Map.class).get("nonce");
    var mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(REGISTRATION_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
    var registrationMac =
        Hex.encodeHexString(
            mac.doFinal(
                (nonce + "\0blur-admin\0" + ADMIN_PASSWORD + "\0admin")
                    .getBytes(StandardCharsets.UTF_8)));
    adminToken =
        (String)
            post(
                    "/_synapse/admin/v1/register",
                    null,
                    Map.of(
                        "nonce", nonce,
                        "username", "blur-admin",
                        "password", ADMIN_PASSWORD,
                        "admin", true,
                        "mac", registrationMac))
                .get("access_token");
    var config = new MatrixConfig();
    config.setApiUrl(baseUrl);
    config.setServerName(SERVER_NAME);
    roomClient = new MatrixRoomClient(config, HTTP, mock(LiveChatDiagnosticMetrics.class));
  }

  /** Session rooms (one-to-one and live chat): the agency creates them, the counsellor gets 100. */
  @Test
  void oneToOneRoomLetsOnlyTheAssignedCounsellorBlurAndLiftBlurs() throws Exception {
    var agency = user("agency");
    var counsellor = user("counsellor");
    var adviceSeeker = user("adviceseeker");
    var supervisor = user("supervisor");
    var roomId = createdRoomId(roomClient.createRoom("Session", alias(), agency.token(), false));
    join(roomId, agency, counsellor, adviceSeeker, supervisor);
    assertThat(roomClient.setUserPowerLevel(roomId, counsellor.id(), 100, agency.token())).isTrue();
    assertThat(roomClient.setUserPowerLevel(roomId, supervisor.id(), 10, agency.token())).isTrue();
    var message =
        send(
            roomId,
            adviceSeeker,
            "m.room.message",
            Map.of("msgtype", "m.text", "body", "Call me at 0151 1234"));

    var blur = send(roomId, counsellor, PASSAGE_BLUR, blurOf(message));

    assertThat(blur).startsWith("$");
    assertRefused(() -> send(roomId, adviceSeeker, PASSAGE_BLUR, blurOf(message)));
    assertRefused(() -> send(roomId, supervisor, PASSAGE_BLUR, blurOf(message)));
    assertRefused(() -> redact(roomId, adviceSeeker, blur));
    assertRefused(() -> redact(roomId, supervisor, blur));
    redact(roomId, counsellor, blur);
  }

  /** Group rooms (also self-help): the owner creates them and is the room's moderator. */
  @Test
  void groupRoomLetsTheOwnerBlurAndKeepsParticipantsOut() throws Exception {
    var owner = user("owner");
    var participant = user("participant");
    var roomId = createdRoomId(roomClient.createRoom("Group", alias(), owner.token(), false));
    join(roomId, owner, participant);
    var message =
        send(
            roomId,
            participant,
            "m.room.message",
            Map.of("msgtype", "m.text", "body", "Call me at 0151 1234"));

    send(roomId, owner, PASSAGE_BLUR, blurOf(message));

    assertRefused(() -> send(roomId, participant, PASSAGE_BLUR, blurOf(message)));
  }

  /**
   * Every moderator may lift any blur, also one another moderator set: the redact level is the
   * moderator level (50), so a member holding it can redact the owner's blur.
   */
  @Test
  void membersOnlyGroupRoomLetsOneModeratorLiftAnotherModeratorsBlur() throws Exception {
    var owner = user("mo-owner");
    var moderator = user("mo-moderator");
    var participant = user("mo-participant");
    var roomId =
        createdRoomId(
            roomClient.createMembersOnlyRoom("Members only", alias(), owner.token(), false));
    join(roomId, owner, moderator, participant);
    assertThat(roomClient.setUserPowerLevel(roomId, moderator.id(), 50, owner.token())).isTrue();
    var message =
        send(
            roomId,
            participant,
            "m.room.message",
            Map.of("msgtype", "m.text", "body", "Call me at 0151 1234"));
    var ownersBlur = send(roomId, owner, PASSAGE_BLUR, blurOf(message));

    assertRefused(() -> redact(roomId, participant, ownersBlur));
    redact(roomId, moderator, ownersBlur);
    send(roomId, moderator, PASSAGE_BLUR, blurOf(message));
  }

  /**
   * A named moderator of a group sits at 0 until UserService raises them. Raised, they blur and
   * lift the owner's blur; demoted again, they can no longer blur.
   */
  @Test
  void namedGroupModeratorBlursAndLiftsTheOwnersBlurUntilDemoted() throws Exception {
    var owner = user("cm-owner");
    var coModerator = user("cm-moderator");
    var participant = user("cm-participant");
    var roomId = createdRoomId(roomClient.createRoom("Group", alias(), owner.token(), false));
    join(roomId, owner, coModerator, participant);
    var message =
        send(
            roomId,
            participant,
            "m.room.message",
            Map.of("msgtype", "m.text", "body", "Call me at 0151 1234"));
    assertRefused(() -> send(roomId, coModerator, PASSAGE_BLUR, blurOf(message)));

    assertThat(
            roomClient.syncModeratorPower(
                roomId, Map.of(coModerator.id(), true), owner.id(), owner.token()))
        .isEqualTo(MatrixRoomClient.ModeratorPowerSync.WRITTEN);
    var ownersBlur = send(roomId, owner, PASSAGE_BLUR, blurOf(message));
    redact(roomId, coModerator, ownersBlur);
    var moderatorsBlur = send(roomId, coModerator, PASSAGE_BLUR, blurOf(message));
    assertRefused(() -> redact(roomId, participant, moderatorsBlur));

    assertThat(
            roomClient.syncModeratorPower(
                roomId, Map.of(coModerator.id(), false), owner.id(), owner.token()))
        .isEqualTo(MatrixRoomClient.ModeratorPowerSync.WRITTEN);
    assertRefused(() -> send(roomId, coModerator, PASSAGE_BLUR, blurOf(message)));
    // Matrix lets everyone redact their own events; someone else's blur is out of reach again.
    var ownersSecondBlur = send(roomId, owner, PASSAGE_BLUR, blurOf(message));
    assertRefused(() -> redact(roomId, coModerator, ownersSecondBlur));
  }

  /**
   * Selected self-help moderators are invited after the group is created and join later: the level
   * written before they joined holds once they are in.
   */
  @Test
  void moderatorLevelWrittenBeforeTheJoinHoldsAfterIt() throws Exception {
    var owner = user("pre-owner");
    var coModerator = user("pre-moderator");
    var roomId = createdRoomId(roomClient.createRoom("Self-help", alias(), owner.token(), false));

    assertThat(
            roomClient.syncModeratorPower(
                roomId, Map.of(coModerator.id(), true), owner.id(), owner.token()))
        .isEqualTo(MatrixRoomClient.ModeratorPowerSync.WRITTEN);
    join(roomId, owner, coModerator);
    var message =
        send(roomId, owner, "m.room.message", Map.of("msgtype", "m.text", "body", "Hello"));

    send(roomId, coModerator, PASSAGE_BLUR, blurOf(message));
  }

  /**
   * Demotion only takes back exactly the moderator level: the room creator keeps 100. A moderator
   * cannot demote another moderator, as Synapse would refuse it.
   */
  @Test
  void demotionLeavesTheCreatorAloneAndNeedsSomeoneAboveTheModerator() throws Exception {
    var owner = user("dm-owner");
    var first = user("dm-first");
    var second = user("dm-second");
    var roomId = createdRoomId(roomClient.createRoom("Group", alias(), owner.token(), false));
    join(roomId, owner, first, second);
    roomClient.syncModeratorPower(
        roomId, Map.of(first.id(), true, second.id(), true), owner.id(), owner.token());

    assertThat(
            roomClient.syncModeratorPower(
                roomId, Map.of(first.id(), false), second.id(), second.token()))
        .isEqualTo(MatrixRoomClient.ModeratorPowerSync.NOT_PERMITTED);
    assertThat(
            roomClient.syncModeratorPower(
                roomId, Map.of(owner.id(), false), owner.id(), owner.token()))
        .isEqualTo(MatrixRoomClient.ModeratorPowerSync.UNCHANGED);

    var power = roomClient.getPowerLevels(roomId, owner.token()).orElseThrow();
    assertThat(power.get("users"))
        .isEqualTo(Map.of(owner.id(), 100, first.id(), 50, second.id(), 50));
  }

  /** The rule is added on top of Synapse's defaults, which a create-room override would replace. */
  @Test
  void createdRoomKeepsSynapsesOwnProtections() throws Exception {
    var owner = user("defaults");
    var roomId = createdRoomId(roomClient.createRoom("Defaults", alias(), owner.token(), true));

    var power = roomClient.getPowerLevels(roomId, owner.token()).orElseThrow();

    assertThat(power).containsEntry("redact", 50);
    @SuppressWarnings("unchecked")
    var events = (Map<String, Object>) power.get("events");
    assertThat(events)
        .containsEntry(PASSAGE_BLUR, 50)
        .containsEntry("m.room.power_levels", 100)
        .containsEntry("m.room.encryption", 100)
        .containsEntry("m.room.history_visibility", 100);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private record MatrixUser(String id, String token) {}

  private static MatrixUser user(String prefix) {
    var localpart = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    var id = "@" + localpart + ":" + SERVER_NAME;
    HTTP.exchange(
        baseUrl + "/_synapse/admin/v2/users/{userId}",
        HttpMethod.PUT,
        entity(adminToken, Map.of("password", USER_PASSWORD)),
        Map.class,
        id);
    var login =
        post(
            "/_matrix/client/v3/login",
            null,
            Map.of(
                "type",
                "m.login.password",
                "identifier",
                Map.of("type", "m.id.user", "user", localpart),
                "password",
                USER_PASSWORD));
    return new MatrixUser(id, (String) login.get("access_token"));
  }

  private static String alias() {
    return "blur-" + UUID.randomUUID();
  }

  private static String createdRoomId(
      org.springframework.http.ResponseEntity<
              de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO>
          response) {
    return response.getBody().getRoomId();
  }

  private static void join(String roomId, MatrixUser creator, MatrixUser... members) {
    for (var member : members) {
      post(
          "/_matrix/client/v3/rooms/" + roomId + "/invite",
          creator.token(),
          Map.of("user_id", member.id()));
      post("/_matrix/client/v3/rooms/" + roomId + "/join", member.token(), Map.of());
    }
  }

  private static Map<String, Object> blurOf(String eventId) {
    return Map.of(
        "m.relates_to", Map.of("rel_type", PASSAGE_BLUR, "event_id", eventId),
        "start", 0,
        "length", 4);
  }

  @SuppressWarnings("unchecked")
  private static String send(
      String roomId, MatrixUser sender, String type, Map<String, Object> content) {
    return (String)
        HTTP.exchange(
                baseUrl + "/_matrix/client/v3/rooms/{room}/send/{type}/{txn}",
                HttpMethod.PUT,
                entity(sender.token(), content),
                Map.class,
                roomId,
                type,
                "txn-" + TXN.incrementAndGet())
            .getBody()
            .get("event_id");
  }

  private static void redact(String roomId, MatrixUser sender, String eventId) {
    HTTP.exchange(
        baseUrl + "/_matrix/client/v3/rooms/{room}/redact/{event}/{txn}",
        HttpMethod.PUT,
        entity(sender.token(), Map.of()),
        Map.class,
        roomId,
        eventId,
        "txn-" + TXN.incrementAndGet());
  }

  private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call).isInstanceOf(HttpClientErrorException.Forbidden.class);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> post(String endpoint, String token, Map<String, Object> body) {
    return HTTP.postForObject(baseUrl + endpoint, entity(token, body), Map.class);
  }

  private static HttpEntity<Map<String, Object>> entity(String token, Map<String, Object> body) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return new HttpEntity<>(body, headers);
  }
}
