package de.caritas.cob.userservice.api.service.matrixrtc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/** ORISO-UserService#1477: the call-room invitation, not source membership, admits a guest. */
class MatrixRtcCallGuestPolicyTest {
  private static final String SOURCE = "!source:example";
  private static final String GUEST = "@guest:example";

  private final SessionRepository sessions = mock(SessionRepository.class);
  private final MatrixSynapseService matrix = mock(MatrixSynapseService.class);
  private final TenantService tenants = mock(TenantService.class);
  private JdbcTemplate jdbc;
  private MatrixRtcCallPolicyService service;

  @BeforeEach
  void setUp() {
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:rtcguest"
                    + java.util.UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
                "sa",
                ""));
    jdbc.execute("CREATE TABLE user(user_id VARCHAR(36),matrix_user_id VARCHAR(255))");
    jdbc.execute("CREATE TABLE consultant(consultant_id VARCHAR(36),matrix_user_id VARCHAR(255))");
    jdbc.execute("CREATE TABLE account_inactivity(identity_id VARCHAR(36),status VARCHAR(20))");
    jdbc.execute(
        "CREATE TABLE matrix_call_binding(id BIGINT PRIMARY KEY,source_room_id VARCHAR(255),"
            + "media_room_id VARCHAR(255),ended_at BIGINT)");
    jdbc.execute(
        "CREATE TABLE matrix_call_guest(id BIGINT AUTO_INCREMENT PRIMARY KEY,binding_id BIGINT,"
            + "matrix_user_id VARCHAR(255),admitted_by_matrix_id VARCHAR(255),admitted_at BIGINT,"
            + "revoked_at BIGINT)");
    // Read by the direct-link knock check (#1475); no knock rows means no knock gate.
    jdbc.execute("CREATE TABLE session(id BIGINT PRIMARY KEY,matrix_room_id VARCHAR(255))");
    jdbc.execute(
        "CREATE TABLE group_chat_join_request(id BIGINT PRIMARY KEY,session_id BIGINT,"
            + "user_id VARCHAR(36),via VARCHAR(32),status VARCHAR(16))");
    jdbc.update("INSERT INTO user VALUES ('guest',?)", GUEST);
    jdbc.update("INSERT INTO account_inactivity VALUES ('guest','ACTIVE')");
    jdbc.update("INSERT INTO matrix_call_binding VALUES (1,?,'!media:example',NULL)", SOURCE);
    var hasher = new MatrixRtcCorrelationIdHasher();
    ReflectionTestUtils.setField(hasher, "secret", "test-hmac-secret");
    ReflectionTestUtils.invokeMethod(hasher, "init");
    service =
        new MatrixRtcCallPolicyService(
            new MatrixRtcPolicyContextResolver(
                sessions,
                mock(ChatRepository.class),
                mock(SessionSupervisorRepository.class),
                mock(TeamDiscussionRepository.class)),
            tenants,
            matrix,
            hasher,
            jdbc);
    when(matrix.getRoomMembers(SOURCE)).thenReturn(Optional.of(List.of("@counsellor:example")));
    var session = mock(Session.class);
    when(session.getTenantId()).thenReturn(7L);
    when(session.getConversationType()).thenReturn(ConversationType.AGENCY_COUNSELLING);
    when(sessions.findByMatrixRoomId(SOURCE)).thenReturn(Optional.of(session));
    when(tenants.getRestrictedTenantDataFresh(7L))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(7L)
                .settings(
                    new Settings()
                        .featureCallsEnabled(true)
                        .featureAudioCallsEnabled(true)
                        .featureVideoCallsEnabled(true)));
  }

  @Test
  void admittedGuestOfTheRunningCallGetsMediaWithoutSourceMembership() {
    guest(1, null);

    assertThat(service.resolve(SOURCE, GUEST, "!media:example"))
        .isEqualTo(new CallMediaPolicy(true, true));
  }

  /** A database outage still denies, but says so, without any Matrix identifier. */
  @Test
  void unreadableGuestSeatsDenyAndAreLoggedWithoutIdentifiers() {
    jdbc.execute("DROP TABLE matrix_call_guest");

    try (var logs =
        de.caritas.cob.userservice.testutils.LogbackCaptor.forClass(
            MatrixRtcCallPolicyService.class)) {
      assertThat(service.resolve(SOURCE, GUEST, "!media:example"))
          .isEqualTo(CallMediaPolicy.denied());
      assertThat(logs.messages(ch.qos.logback.classic.Level.WARN))
          .anySatisfy(message -> assertThat(message).contains("Call guest seat lookup failed"))
          .allSatisfy(message -> assertThat(message).doesNotContain(GUEST, SOURCE));
    }
  }

  @Test
  void revokedGuestIsDenied() {
    guest(1, 5000L);

    assertThat(service.resolve(SOURCE, GUEST, "!media:example"))
        .isEqualTo(CallMediaPolicy.denied());
  }

  @Test
  void guestOfAnEndedCallIsDenied() {
    guest(1, null);
    jdbc.update("UPDATE matrix_call_binding SET ended_at=5000 WHERE id=1");

    assertThat(service.resolve(SOURCE, GUEST, "!media:example"))
        .isEqualTo(CallMediaPolicy.denied());
  }

  @Test
  void guestOfAnotherConversationsCallIsDenied() {
    jdbc.update("INSERT INTO matrix_call_binding VALUES (2,'!other:example','!m2:example',NULL)");
    guest(2, null);

    assertThat(service.resolve(SOURCE, GUEST, "!media:example"))
        .isEqualTo(CallMediaPolicy.denied());
  }

  /** Security finding 1: a seat in one call is no seat in another call of the conversation. */
  @Test
  void guestOfOneCallIsDeniedForAnotherCallFromTheSameConversation() {
    jdbc.update(
        "INSERT INTO matrix_call_binding VALUES (3,?,'!second-media:example',NULL)", SOURCE);
    guest(1, null);

    assertThat(service.resolve(SOURCE, GUEST, "!second-media:example"))
        .isEqualTo(CallMediaPolicy.denied());
    assertThat(service.resolve(SOURCE, GUEST))
        .as("no call room named")
        .isEqualTo(CallMediaPolicy.denied());
    assertThat(service.resolve(SOURCE, GUEST, "!media:example"))
        .isEqualTo(new CallMediaPolicy(true, true));
  }

  private void guest(long binding, Long revokedAt) {
    jdbc.update(
        "INSERT INTO matrix_call_guest(binding_id,matrix_user_id,admitted_by_matrix_id,admitted_at,"
            + "revoked_at) VALUES (?,?,'@counsellor:example',1000,?)",
        binding,
        GUEST,
        revokedAt);
  }
}
