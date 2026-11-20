package de.caritas.cob.userservice.api.service.matrixrtc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.controller.MatrixRtcCallPolicyController;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.service.ConsultingTypeService;
import de.caritas.cob.userservice.api.service.matrix.CounsellorStartedCall;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Frank, 17 Nov 2026, on the wire the MatrixRTC gateway reads: an advice seeker's start is refused
 * with a reason code the gateway can pass on; a grant and every other denial carry no reason.
 */
class CallStartPolicyHttpTest {
  private static final String ROOM_ID = "!source:matrix.example.org";
  private static final String CALL_ROOM_ID = "!call:matrix.example.org";
  private static final String ASKER = "@asker:matrix.example.org";
  private static final long TENANT_ID = 7L;

  private final SessionRepository sessions = mock(SessionRepository.class);
  private final TenantService tenants = mock(TenantService.class);
  private final MatrixSynapseService matrix = mock(MatrixSynapseService.class);
  private final ConsultingTypeService consultingTypes = mock(ConsultingTypeService.class);
  private final CounsellorStartedCall counsellorCalls = mock(CounsellorStartedCall.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    var jdbc =
        new org.springframework.jdbc.core.JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:rtcstarthttp"
                    + java.util.UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
                "sa",
                ""));
    jdbc.execute("CREATE TABLE user(user_id VARCHAR(36),matrix_user_id VARCHAR(255))");
    jdbc.execute("CREATE TABLE consultant(consultant_id VARCHAR(36),matrix_user_id VARCHAR(255))");
    jdbc.execute("CREATE TABLE account_inactivity(identity_id VARCHAR(36),status VARCHAR(20))");
    jdbc.execute("CREATE TABLE session(id BIGINT,matrix_room_id VARCHAR(255))");
    jdbc.execute(
        "CREATE TABLE group_chat_join_request(id BIGINT AUTO_INCREMENT,session_id BIGINT,"
            + "user_id VARCHAR(36),via VARCHAR(32),status VARCHAR(16))");
    jdbc.update("INSERT INTO user VALUES ('asker',?)", ASKER);
    jdbc.update("INSERT INTO account_inactivity VALUES ('asker','ACTIVE')");
    when(matrix.getRoomMembers(ROOM_ID)).thenReturn(Optional.of(List.of(ASKER)));
    var session = mock(Session.class);
    when(session.getTenantId()).thenReturn(TENANT_ID);
    when(session.getConversationType()).thenReturn(ConversationType.AGENCY_COUNSELLING);
    when(session.getConsultingTypeId()).thenReturn(12);
    when(sessions.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.of(session));
    when(tenants.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(TENANT_ID)
                .settings(
                    new Settings()
                        .featureCallsEnabled(true)
                        .featureAudioCallsEnabled(true)
                        .featureVideoCallsEnabled(true)));
    var consultingType = new ExtendedConsultingTypeResponseDTO().isVideoCallAllowed(true);
    when(consultingTypes.getFreshExtendedConsultingTypeResponseDTO(anyInt()))
        .thenReturn(consultingType);
    var hasher = new MatrixRtcCorrelationIdHasher();
    ReflectionTestUtils.setField(hasher, "secret", "test-hmac-secret");
    ReflectionTestUtils.invokeMethod(hasher, "init");
    var resolver =
        new MatrixRtcPolicyContextResolver(
            sessions,
            mock(ChatRepository.class),
            mock(SessionSupervisorRepository.class),
            mock(TeamDiscussionRepository.class));
    var service =
        new MatrixRtcCallPolicyService(
            resolver,
            tenants,
            matrix,
            hasher,
            jdbc,
            mock(MatrixCallGuestService.class),
            counsellorCalls);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new MatrixRtcCallPolicyController(
                    service,
                    new MatrixRtcPolicyTokenVerifier("test-only"),
                    new ConsultingTypeCallGate(resolver, consultingTypes)))
            .build();
  }

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void anAskersStartIsRefusedWithAReasonCode() throws Exception {
    when(counsellorCalls.isRunning(ROOM_ID, CALL_ROOM_ID, TENANT_ID)).thenReturn(false);

    policy()
        .andExpect(jsonPath("$.audioAllowed").value(false))
        .andExpect(jsonPath("$.videoAllowed").value(false))
        .andExpect(jsonPath("$.denialReason").value("call_start_requires_counsellor"));
  }

  @Test
  void anAskersJoinIsGrantedWithoutAReason() throws Exception {
    when(counsellorCalls.isRunning(ROOM_ID, CALL_ROOM_ID, TENANT_ID)).thenReturn(true);

    policy()
        .andExpect(jsonPath("$.audioAllowed").value(true))
        .andExpect(jsonPath("$.videoAllowed").value(true))
        .andExpect(content().string(not(containsString("denialReason"))));
  }

  @Test
  void anyOtherDenialCarriesNoReason() throws Exception {
    when(matrix.getRoomMembers(ROOM_ID)).thenReturn(Optional.of(List.of()));

    policy()
        .andExpect(jsonPath("$.audioAllowed").value(false))
        .andExpect(content().string(not(containsString("denialReason"))));
  }

  private ResultActions policy() throws Exception {
    return mvc.perform(
            post("/internal/matrixrtc/call-policy")
                .header("x-matrixrtc-policy-token", "test-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceRoomId\":\""
                        + ROOM_ID
                        + "\",\"matrixUserId\":\""
                        + ASKER
                        + "\",\"callRoomId\":\""
                        + CALL_ROOM_ID
                        + "\"}"))
        .andExpect(status().isOk());
  }
}
