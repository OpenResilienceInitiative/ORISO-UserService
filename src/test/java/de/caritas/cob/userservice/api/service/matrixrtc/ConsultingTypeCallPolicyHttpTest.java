package de.caritas.cob.userservice.api.service.matrixrtc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.controller.MatrixRtcCallPolicyController;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.service.ConsultingTypeService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.ResourceAccessException;

/**
 * The call policy over HTTP, with the switch an admin sets per form of counselling ("Videoanrufe
 * erlauben", ORISO-UserService#1564): the app hides the whole call menu of a consulting type that
 * does not allow calls, and the policy that Element Call and LiveKit ask must say the same.
 */
class ConsultingTypeCallPolicyHttpTest {

  private static final String ROOM_ID = "!source:matrix.example.org";
  private static final String MATRIX_USER_ID = "@participant:matrix.example.org";
  private static final long TENANT_ID = 7L;
  private static final int CONSULTING_TYPE_ID = 12;

  private final SessionRepository sessionRepository = mock(SessionRepository.class);
  private final ChatRepository chatRepository = mock(ChatRepository.class);
  private final SessionSupervisorRepository supervisorRepository =
      mock(SessionSupervisorRepository.class);
  private final TeamDiscussionRepository discussionRepository =
      mock(TeamDiscussionRepository.class);
  private final TenantService tenantService = mock(TenantService.class);
  private final MatrixSynapseService matrixSynapseService = mock(MatrixSynapseService.class);
  private final ConsultingTypeService consultingTypeService = mock(ConsultingTypeService.class);

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    var jdbc =
        new org.springframework.jdbc.core.JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:rtcconsulting"
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
    jdbc.update("INSERT INTO user VALUES ('person',?)", MATRIX_USER_ID);
    jdbc.update("INSERT INTO account_inactivity VALUES ('person','ACTIVE')");

    when(matrixSynapseService.getRoomMembers(ROOM_ID))
        .thenReturn(Optional.of(List.of(MATRIX_USER_ID)));
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(TENANT_ID)
                .settings(
                    new Settings()
                        .featureCallsEnabled(true)
                        .featureAudioCallsEnabled(true)
                        .featureVideoCallsEnabled(true)));
    var hasher = new MatrixRtcCorrelationIdHasher();
    ReflectionTestUtils.setField(hasher, "secret", "test-hmac-secret");
    ReflectionTestUtils.invokeMethod(hasher, "init");

    var resolver =
        new MatrixRtcPolicyContextResolver(
            sessionRepository, chatRepository, supervisorRepository, discussionRepository);
    var service =
        new MatrixRtcCallPolicyService(resolver, tenantService, matrixSynapseService, hasher, jdbc);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new MatrixRtcCallPolicyController(
                    service,
                    new MatrixRtcPolicyTokenVerifier("test-only"),
                    new ConsultingTypeCallGate(resolver, consultingTypeService)))
            .build();
  }

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void consultingTypeThatAllowsCalls_Should_KeepAudioAndVideo() throws Exception {
    inSession(ConversationType.AGENCY_COUNSELLING);
    consultingTypeAllowsCalls(true);

    policy()
        .andExpect(jsonPath("$.audioAllowed").value(true))
        .andExpect(jsonPath("$.videoAllowed").value(true));
  }

  @Test
  void consultingTypeThatSwitchedCallsOff_Should_DenyAudioAndVideo() throws Exception {
    inSession(ConversationType.AGENCY_COUNSELLING);
    consultingTypeAllowsCalls(false);

    policy()
        .andExpect(jsonPath("$.audioAllowed").value(false))
        .andExpect(jsonPath("$.videoAllowed").value(false));
  }

  @Test
  void consultingTypeWithoutAValue_Should_DenyLikeTheAppHidesTheMenu() throws Exception {
    inSession(ConversationType.AGENCY_COUNSELLING);
    when(consultingTypeService.getExtendedConsultingTypeResponseDTO(anyInt()))
        .thenReturn(new ExtendedConsultingTypeResponseDTO());

    policy()
        .andExpect(jsonPath("$.audioAllowed").value(false))
        .andExpect(jsonPath("$.videoAllowed").value(false));
  }

  @Test
  void consultingTypeThatCannotBeRead_Should_DenyAndNotAllowOnDoubt() throws Exception {
    inSession(ConversationType.AGENCY_COUNSELLING);
    when(consultingTypeService.getExtendedConsultingTypeResponseDTO(anyInt()))
        .thenThrow(new ResourceAccessException("consulting type service down"));

    policy()
        .andExpect(jsonPath("$.audioAllowed").value(false))
        .andExpect(jsonPath("$.videoAllowed").value(false));
  }

  @Test
  void groupChat_Should_FollowTheConsultingTypeOfTheChat() throws Exception {
    var chat = mock(Chat.class);
    when(chat.getConsultingTypeId()).thenReturn(CONSULTING_TYPE_ID);
    var owner = mock(de.caritas.cob.userservice.api.model.Consultant.class);
    when(owner.getTenantId()).thenReturn(TENANT_ID);
    when(chat.getChatOwner()).thenReturn(owner);
    when(chatRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.of(chat));
    consultingTypeAllowsCalls(false);

    policy()
        .andExpect(jsonPath("$.audioAllowed").value(false))
        .andExpect(jsonPath("$.videoAllowed").value(false));
  }

  /** Product decision 12.09.2026: a supervision call is staff to staff, no consulting type gate. */
  @Test
  void supervisionRoom_Should_NotInheritTheConsultingTypeGate() throws Exception {
    var supervision = mock(SessionSupervisor.class);
    var session = session(ConversationType.AGENCY_COUNSELLING);
    when(supervision.getSession()).thenReturn(session);
    when(supervisorRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.of(supervision));
    consultingTypeAllowsCalls(false);

    policy()
        .andExpect(jsonPath("$.audioAllowed").value(true))
        .andExpect(jsonPath("$.videoAllowed").value(true));
  }

  /**
   * An admin's change applies to the next call: the consulting type is read fresh, in its Träger.
   */
  @Test
  void consultingTypeIsReadFreshInTheTraegerOfTheConversation() throws Exception {
    inSession(ConversationType.AGENCY_COUNSELLING);
    var tenantDuringRead = new AtomicReference<Long>();
    when(consultingTypeService.getFreshExtendedConsultingTypeResponseDTO(CONSULTING_TYPE_ID))
        .thenAnswer(
            invocation -> {
              tenantDuringRead.set(TenantContext.getCurrentTenant());
              return new ExtendedConsultingTypeResponseDTO().isVideoCallAllowed(true);
            });

    policy().andExpect(status().isOk());

    org.assertj.core.api.Assertions.assertThat(tenantDuringRead.get()).isEqualTo(TENANT_ID);
    org.mockito.Mockito.verify(consultingTypeService, org.mockito.Mockito.never())
        .getExtendedConsultingTypeResponseDTO(anyInt());
  }

  // ---- helpers

  private ResultActions policy() throws Exception {
    return mvc.perform(
            post("/internal/matrixrtc/call-policy")
                .header("x-matrixrtc-policy-token", "test-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceRoomId\":\""
                        + ROOM_ID
                        + "\",\"matrixUserId\":\""
                        + MATRIX_USER_ID
                        + "\"}"))
        .andExpect(status().isOk());
  }

  private void inSession(ConversationType type) {
    var session = session(type);
    when(sessionRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.of(session));
  }

  private Session session(ConversationType type) {
    var session = mock(Session.class);
    when(session.getTenantId()).thenReturn(TENANT_ID);
    when(session.getConversationType()).thenReturn(type);
    when(session.getConsultingTypeId()).thenReturn(CONSULTING_TYPE_ID);
    return session;
  }

  private void consultingTypeAllowsCalls(boolean allowed) {
    var consultingType = new ExtendedConsultingTypeResponseDTO().isVideoCallAllowed(allowed);
    when(consultingTypeService.getExtendedConsultingTypeResponseDTO(anyInt()))
        .thenReturn(consultingType);
    when(consultingTypeService.getFreshExtendedConsultingTypeResponseDTO(anyInt()))
        .thenReturn(consultingType);
  }
}
