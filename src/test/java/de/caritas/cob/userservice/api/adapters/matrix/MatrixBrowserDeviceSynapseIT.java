package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.BeforeAll;
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
 * Slow public-boundary regression for US #1224. Uses real password UIA and persisted cross-signing
 * keys in a disposable Synapse; no Dev accounts, mocked HTTP responses or optional Docker skip.
 */
@Testcontainers
@Timeout(120)
class MatrixBrowserDeviceSynapseIT {

  private static final String SERVER_NAME = "matrix-browser-uia.test";
  private static final String REGISTRATION_SECRET = "public-disposable-uia-registration-fixture";
  private static final String ADMIN_PASSWORD = "public-disposable-uia-admin-fixture";
  private static final String SIGNING_UPLOAD = "/_matrix/client/v3/keys/device_signing/upload";
  private static final String BACKUP_VERSION = "/_matrix/client/v3/room_keys/version";

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

  private static final RestTemplate HTTP = httpClient();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static String baseUrl;
  private static String adminToken;

  private static RestTemplate httpClient() {
    // HttpURLConnection loses some 401 bodies; UIA must preserve Synapse's challenge/session JSON.
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    factory.setReadTimeout(Duration.ofSeconds(15));
    return new RestTemplate(factory);
  }

  @BeforeAll
  static void provisionSyntheticAdmin() throws Exception {
    baseUrl = "http://" + SYNAPSE.getHost() + ":" + SYNAPSE.getMappedPort(8008);
    var nonce = HTTP.getForObject(baseUrl + "/_synapse/admin/v1/register", Map.class).get("nonce");
    var mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(REGISTRATION_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
    var registrationMac =
        Hex.encodeHexString(
            mac.doFinal(
                (nonce + "\0testadmin\0" + ADMIN_PASSWORD + "\0admin")
                    .getBytes(StandardCharsets.UTF_8)));
    var registration =
        post(
            "/_synapse/admin/v1/register",
            null,
            Map.of(
                "nonce", nonce,
                "username", "testadmin",
                "password", ADMIN_PASSWORD,
                "admin", true,
                "mac", registrationMac));
    adminToken = (String) registration.get("access_token");
    assertThat(adminToken).isNotBlank();
  }

  @Test
  void originalDeviceCanReplaceCrossSigningIdentityAfterAnotherBackendInstanceLogsInDeviceB()
      throws Exception {
    var userId = createSyntheticUser("reset-two-devices");
    var deviceA = browserService().loginBrowserDevice(userId, "DEVICE_A");
    assertDeviceIdentity(deviceA, userId, "DEVICE_A");
    var originalKey = masterKey(userId);
    assertThat(post(SIGNING_UPLOAD, token(deviceA), Map.of("master_key", originalKey))).isEmpty();
    assertThat(queriedMasterKey(deviceA, userId)).isEqualTo(originalKey);
    var originalBackup = createBackup(deviceA);
    assertThat(get(BACKUP_VERSION, token(deviceA))).containsEntry("version", originalBackup);

    // A new backend object has no shared login lock or cache: this also covers a restart/other pod.
    var deviceB = browserService().loginBrowserDevice(userId, "DEVICE_B");
    assertDeviceIdentity(deviceB, userId, "DEVICE_B");
    assertThat(deviceB.get("device_id")).isNotEqualTo(deviceA.get("device_id"));
    Thread.sleep(1500); // A user leaves the first tab open before returning to the reset dialog.
    assertDeviceIdentity(
        get("/_matrix/client/v3/account/whoami", token(deviceA)), userId, "DEVICE_A");

    // Match the reset's destructive ordering: the old backup is gone before password UIA.
    assertThat(
            HTTP.exchange(
                    baseUrl + BACKUP_VERSION + "/" + originalBackup,
                    HttpMethod.DELETE,
                    entity(token(deviceA), null),
                    Map.class)
                .getBody())
        .isEmpty();
    assertBackupMissing(deviceA, originalBackup);

    var replacement = masterKey(userId);
    var upload = new HashMap<String, Object>(Map.of("master_key", replacement));
    var challenge = signingChallenge(deviceA, upload);
    assertThat(challenge.get("flows"))
        .as("replacing an existing identity must require real password UIA")
        .isEqualTo(List.of(Map.of("stages", List.of("m.login.password"))));
    upload.put(
        "auth",
        Map.of(
            "type", "m.login.password",
            "identifier", Map.of("type", "m.id.user", "user", userId),
            "password", deviceA.get("interactive_auth_password"),
            "session", challenge.get("session")));

    // RED on the old UUID rotation: Synapse returns 401 M_FORBIDDEN for A's stale password.
    assertThat(post(SIGNING_UPLOAD, token(deviceA), upload)).isEmpty();
    assertThat(queriedMasterKey(deviceA, userId)).isEqualTo(replacement);
    assertThat(queriedMasterKey(deviceB, userId)).isEqualTo(replacement);
    var replacementBackup = createBackup(deviceA);
    assertThat(replacementBackup).isNotEqualTo(originalBackup);
    assertThat(get(BACKUP_VERSION, token(deviceA))).containsEntry("version", replacementBackup);
    assertThat(get(BACKUP_VERSION, token(deviceB))).containsEntry("version", replacementBackup);
    assertBackupMissing(deviceB, originalBackup);
  }

  @Test
  void originalDeviceCanSetUpFirstIdentityAndBackupAfterAnotherBackendLogsInDeviceB()
      throws Exception {
    var userId = createSyntheticUser("setup-two-devices");
    var deviceA = browserService().loginBrowserDevice(userId, "SETUP_A");
    var deviceB = browserService().loginBrowserDevice(userId, "SETUP_B");
    assertDeviceIdentity(deviceA, userId, "SETUP_A");
    assertDeviceIdentity(deviceB, userId, "SETUP_B");
    Thread.sleep(1500);

    // Synapse permits first-time setup without UIA (MSC3967); replacement above must require it.
    var initialKey = masterKey(userId);
    assertThat(post(SIGNING_UPLOAD, token(deviceA), Map.of("master_key", initialKey))).isEmpty();
    assertThat(queriedMasterKey(deviceA, userId)).isEqualTo(initialKey);
    assertThat(queriedMasterKey(deviceB, userId)).isEqualTo(initialKey);
    var initialBackup = createBackup(deviceA);
    assertThat(get(BACKUP_VERSION, token(deviceA))).containsEntry("version", initialBackup);
    assertThat(get(BACKUP_VERSION, token(deviceB))).containsEntry("version", initialBackup);
    assertDeviceIdentity(
        get("/_matrix/client/v3/account/whoami", token(deviceA)), userId, "SETUP_A");
    assertDeviceIdentity(
        get("/_matrix/client/v3/account/whoami", token(deviceB)), userId, "SETUP_B");
  }

  private static String createBackup(Map<String, Object> device) throws Exception {
    var encoded = KeyPairGenerator.getInstance("X25519").generateKeyPair().getPublic().getEncoded();
    var publicKey =
        Base64.getEncoder()
            .withoutPadding()
            .encodeToString(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    // Real backup metadata lifecycle; encrypted message payloads and browser recovery UI are
    // separate.
    return (String)
        post(
                BACKUP_VERSION,
                token(device),
                Map.of(
                    "algorithm",
                    "m.megolm_backup.v1.curve25519-aes-sha2",
                    "auth_data",
                    Map.of("public_key", publicKey, "signatures", Map.of())))
            .get("version");
  }

  private static void assertBackupMissing(Map<String, Object> device, String version)
      throws Exception {
    try {
      get(BACKUP_VERSION + "/" + version, token(device));
      throw new AssertionError("Deleted backup version still exists");
    } catch (HttpClientErrorException exception) {
      assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
      var error =
          JSON.readValue(
              exception.getResponseBodyAsString(), new TypeReference<Map<String, Object>>() {});
      assertThat(error).containsEntry("errcode", "M_NOT_FOUND");
    }
  }

  private static MatrixSynapseService browserService() {
    var config = new MatrixConfig();
    config.setApiUrl(baseUrl);
    config.setServerName(SERVER_NAME);
    config.setRegistrationSharedSecret(REGISTRATION_SECRET);
    config.setAdminUsername("testadmin");
    config.setAdminPassword(ADMIN_PASSWORD);
    return new MatrixSynapseService(
        config,
        HTTP,
        HTTP,
        mock(MatrixRoomClient.class),
        mock(MatrixMediaClient.class),
        MatrixIdentifierRedactor.withKey("public-disposable-log-pseudonym-fixture"));
  }

  private static String createSyntheticUser(String localpart) {
    var userId = "@" + localpart + ":" + SERVER_NAME;
    HTTP.exchange(
        baseUrl + "/_synapse/admin/v2/users/{userId}",
        HttpMethod.PUT,
        entity(adminToken, Map.of("password", "public-disposable-initial-password")),
        Map.class,
        userId);
    return userId;
  }

  private static void assertDeviceIdentity(
      Map<String, Object> device, String userId, String deviceId) {
    assertThat(device)
        .isNotNull()
        .containsEntry("user_id", userId)
        .containsEntry("device_id", deviceId);
  }

  private static Map<String, Object> masterKey(String userId) throws Exception {
    var encoded =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded();
    // Ed25519 SubjectPublicKeyInfo ends in the 32-byte public key required by Matrix.
    var publicKey =
        Base64.getEncoder()
            .withoutPadding()
            .encodeToString(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    return Map.of(
        "user_id", userId,
        "usage", List.of("master"),
        "keys", Map.of("ed25519:" + publicKey, publicKey));
  }

  private static Object queriedMasterKey(Map<String, Object> device, String userId) {
    var result =
        post(
            "/_matrix/client/v3/keys/query",
            token(device),
            Map.of("device_keys", Map.of(userId, List.of())));
    return ((Map<?, ?>) result.get("master_keys")).get(userId);
  }

  private static Map<String, Object> signingChallenge(
      Map<String, Object> device, Map<String, Object> upload) throws Exception {
    try {
      post(SIGNING_UPLOAD, token(device), upload);
      throw new AssertionError("Synapse accepted a replacement identity without UIA");
    } catch (HttpClientErrorException exception) {
      assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      var challenge =
          JSON.readValue(
              exception.getResponseBodyAsString(), new TypeReference<Map<String, Object>>() {});
      assertThat(challenge.get("session")).isInstanceOf(String.class).asString().isNotBlank();
      return challenge;
    }
  }

  private static String token(Map<String, Object> device) {
    return (String) device.get("access_token");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> post(String endpoint, String token, Map<String, Object> body) {
    return HTTP.postForObject(baseUrl + endpoint, entity(token, body), Map.class);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> get(String endpoint, String token) {
    return HTTP.exchange(baseUrl + endpoint, HttpMethod.GET, entity(token, null), Map.class)
        .getBody();
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
