package de.caritas.cob.userservice.api.service.matrixrtc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.service.matrix.CounsellorStartedCall;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Frank, 17 Nov 2026: only counsellors start calls. Advice seekers and guests get media only for a
 * running call a counsellor of the Träger started, in 1:1 sessions and group chats alike, whatever
 * the client shows or sends. Counsellors are unchanged.
 */
class MatrixRtcCallStartPolicyTest {
  private static final String SOURCE = "!source:example";
  private static final String MEDIA = "!media:example";
  private static final String ASKER = "@asker:example";
  private static final String COUNSELLOR = "@counsellor:example";
  private static final String GUEST = "@guest:example";
  private static final long TENANT = 7L;

  private final SessionRepository sessions = mock(SessionRepository.class);
  private final MatrixSynapseService matrix = mock(MatrixSynapseService.class);
  private final TenantService tenants = mock(TenantService.class);
  private final MatrixCallGuestService callGuests = mock(MatrixCallGuestService.class);
  private final CounsellorStartedCall counsellorCalls = mock(CounsellorStartedCall.class);
  private JdbcTemplate jdbc;
  private MatrixRtcCallPolicyService service;

  @BeforeEach
  void setUp() {
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:rtcstart"
                    + java.util.UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
                "sa",
                ""));
    jdbc.execute("CREATE TABLE user(user_id VARCHAR(36),matrix_user_id VARCHAR(255))");
    jdbc.execute("CREATE TABLE consultant(consultant_id VARCHAR(36),matrix_user_id VARCHAR(255))");
    jdbc.execute("CREATE TABLE account_inactivity(identity_id VARCHAR(36),status VARCHAR(20))");
    jdbc.execute(
        "CREATE TABLE matrix_call_binding(id BIGINT PRIMARY KEY,source_room_id VARCHAR(255),"
            + "media_room_id VARCHAR(255),ended_at BIGINT,session_id BIGINT)");
    jdbc.execute(
        "CREATE TABLE matrix_call_guest(id BIGINT AUTO_INCREMENT PRIMARY KEY,binding_id BIGINT,"
            + "matrix_user_id VARCHAR(255),admitted_by_matrix_id VARCHAR(255),admitted_at BIGINT,"
            + "revoked_at BIGINT)");
    jdbc.execute("CREATE TABLE session(id BIGINT PRIMARY KEY,matrix_room_id VARCHAR(255))");
    jdbc.execute(
        "CREATE TABLE group_chat_join_request(id BIGINT PRIMARY KEY,session_id BIGINT,"
            + "user_id VARCHAR(36),via VARCHAR(32),status VARCHAR(16))");
    identity("user", "asker", ASKER);
    identity("user", "guest", GUEST);
    identity("consultant", "counsellor", COUNSELLOR);
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
            jdbc,
            callGuests,
            counsellorCalls);
    when(matrix.getRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(ASKER, COUNSELLOR)));
    when(counsellorCalls.isCounsellor(COUNSELLOR, TENANT)).thenReturn(true);
    var session = mock(Session.class);
    when(session.getTenantId()).thenReturn(TENANT);
    when(session.getConversationType()).thenReturn(ConversationType.AGENCY_COUNSELLING);
    when(sessions.findByMatrixRoomId(SOURCE)).thenReturn(Optional.of(session));
    when(tenants.getRestrictedTenantDataFresh(TENANT))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(TENANT)
                .settings(
                    new Settings()
                        .featureCallsEnabled(true)
                        .featureAudioCallsEnabled(true)
                        .featureVideoCallsEnabled(true)));
  }

  @Test
  void anAskerCannotStartACall() {
    when(counsellorCalls.isRunning(SOURCE, MEDIA, TENANT)).thenReturn(false);

    try (var logs = LogbackCaptor.forClass(MatrixRtcCallPolicyService.class)) {
      var policy = service.resolve(SOURCE, ASKER, MEDIA);

      assertThat(policy.audioAllowed()).isFalse();
      assertThat(policy.videoAllowed()).isFalse();
      assertThat(policy.denialReason()).isEqualTo("call_start_requires_counsellor");
      assertThat(logs.messages(Level.INFO))
          .anySatisfy(message -> assertThat(message).contains("CALL_START_REQUIRES_COUNSELLOR"))
          .allSatisfy(message -> assertThat(message).doesNotContain(ASKER, SOURCE, MEDIA));
    }
  }

  @Test
  void anAskerJoinsTheRunningCallOfTheCounsellor() {
    when(counsellorCalls.isRunning(SOURCE, MEDIA, TENANT)).thenReturn(true);

    assertThat(service.resolve(SOURCE, ASKER, MEDIA)).isEqualTo(new CallMediaPolicy(true, true));
  }

  @Test
  void anAskerNamingNoCallRoomIsRefused() {
    when(counsellorCalls.isRunning(SOURCE, null, TENANT)).thenReturn(false);

    assertThat(service.resolve(SOURCE, ASKER).denialReason())
        .isEqualTo("call_start_requires_counsellor");
  }

  @Test
  void aCounsellorStartsACallAsBefore() {
    assertThat(service.resolve(SOURCE, COUNSELLOR, MEDIA))
        .isEqualTo(new CallMediaPolicy(true, true));
    verify(counsellorCalls, never()).isRunning(anyString(), any(), anyLong());
  }

  /** A counsellor of another Träger counts as nobody's counsellor here. */
  @Test
  void aCounsellorOfAnotherTraegerCannotStartACall() {
    when(counsellorCalls.isCounsellor(COUNSELLOR, TENANT)).thenReturn(false);
    when(counsellorCalls.isRunning(SOURCE, MEDIA, TENANT)).thenReturn(false);

    assertThat(service.resolve(SOURCE, COUNSELLOR, MEDIA).denialReason())
        .isEqualTo("call_start_requires_counsellor");
  }

  /** The start rule never opens what an earlier rule closed (here: not a room member). */
  @Test
  void aRunningCounsellorCallAdmitsNoOutsider() {
    when(matrix.getRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(COUNSELLOR)));
    when(counsellorCalls.isRunning(SOURCE, MEDIA, TENANT)).thenReturn(true);

    assertThat(service.resolve(SOURCE, ASKER, MEDIA)).isEqualTo(CallMediaPolicy.denied());
  }

  @Test
  void anAdmittedGuestJoinsTheRunningCallOfTheCounsellor() {
    guestSeat();
    when(counsellorCalls.isRunning(SOURCE, MEDIA, TENANT)).thenReturn(true);

    assertThat(service.resolve(SOURCE, GUEST, MEDIA)).isEqualTo(new CallMediaPolicy(true, true));
  }

  @Test
  void aGuestSeatOpensNoCallThatNoCounsellorStarted() {
    guestSeat();
    when(counsellorCalls.isRunning(SOURCE, MEDIA, TENANT)).thenReturn(false);

    assertThat(service.resolve(SOURCE, GUEST, MEDIA).denialReason())
        .isEqualTo("call_start_requires_counsellor");
  }

  @Test
  void otherDenialsCarryNoReasonToTheGateway() {
    when(matrix.getRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(COUNSELLOR)));

    assertThat(service.resolve(SOURCE, ASKER, MEDIA).denialReason()).isNull();
  }

  private void guestSeat() {
    jdbc.update(
        "INSERT INTO matrix_call_binding(id,source_room_id,media_room_id) VALUES (1,?,?)",
        SOURCE,
        MEDIA);
    jdbc.update(
        "INSERT INTO matrix_call_guest(binding_id,matrix_user_id,admitted_by_matrix_id,admitted_at)"
            + " VALUES (1,?,?,1000)",
        GUEST,
        COUNSELLOR);
  }

  private void identity(String table, String id, String matrixId) {
    jdbc.update("INSERT INTO " + table + " VALUES (?,?)", id, matrixId);
    jdbc.update("INSERT INTO account_inactivity VALUES (?,'ACTIVE')", id);
  }
}
