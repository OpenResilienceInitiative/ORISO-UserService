package de.caritas.cob.userservice.api.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

/**
 * C2 hardening (facade level): with {@code rocket-chat.enabled=false} (ADR-004), {@link
 * CreateChatFacade#createChatV1} and {@link CreateChatFacade#createChatV2} MUST take the Matrix
 * branch ({@code createSimplifiedGroupChat}) and never touch the {@link RocketChatService}
 * collaborator.
 *
 * <p>This complements {@code DisabledRocketChatServiceContractTest} (which proves the inert adapter
 * contract) by proving that the branch selection in the facade actually routes away from
 * Rocket.Chat when it is disabled. It is a deterministic {@link MockitoExtension} unit test — no
 * external services, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateChatFacadeRocketChatDisabledTest {

  private static final String MATRIX_ROOM_ID = "!room:matrix.oriso.local";
  private static final String CONSULTANT_MATRIX_USER_ID = "@consultant:matrix.oriso.local";
  private static final Long AGENCY_ID = 42L;

  @InjectMocks private CreateChatFacade createChatFacade;

  @Mock private ChatService chatService;
  @Mock private SessionService sessionService;
  @Mock private RocketChatService rocketChatService;
  @Mock private AgencyService agencyService;
  @Mock private ChatConverter chatConverter;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private GroupChatParticipantRepository groupChatParticipantRepository;
  @Mock private UserRepository userRepository;

  @Mock private ChatDTO chatDTO;
  @Mock private AgencyDTO agencyDTO;
  @Mock private Consultant consultant;

  @BeforeEach
  void setUp() throws Exception {
    // Rocket.Chat disabled: the facade must always take the Matrix path.
    setField(createChatFacade, "rocketChatEnabled", false);

    // A plain group-chat request (no consultantIds) still routes to Matrix when RC is off.
    when(chatDTO.getConsultantIds()).thenReturn(null);
    when(chatDTO.getAgencyId()).thenReturn(AGENCY_ID);
    when(chatDTO.getTopic()).thenReturn("Group chat topic");

    when(consultant.getMatrixUserId()).thenReturn(CONSULTANT_MATRIX_USER_ID);
    when(consultant.getId()).thenReturn("consultant-id");
    when(consultant.getTenantId()).thenReturn(null);

    // Group-chat system user resolves to an existing tenant user (no fallback creation needed).
    when(userRepository.findByUserIdAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.of(mock(User.class)));

    when(agencyService.getAgency(AGENCY_ID)).thenReturn(agencyDTO);
    when(agencyDTO.getConsultingType()).thenReturn(1);

    Session savedSession = new Session();
    savedSession.setId(1000L);
    savedSession.setCreateDate(LocalDateTime.now());
    when(sessionService.saveSession(any(Session.class))).thenReturn(savedSession);

    Chat savedChat = mock(Chat.class);
    when(savedChat.getId()).thenReturn(2000L);
    when(chatConverter.convertToEntity(
            any(ChatDTO.class), any(Consultant.class), any(AgencyDTO.class)))
        .thenReturn(savedChat);
    when(chatService.saveChat(any(Chat.class))).thenReturn(savedChat);

    var roomBody = new MatrixCreateRoomResponseDTO();
    roomBody.setRoomId(MATRIX_ROOM_ID);
    when(matrixSynapseService.createRoomAsMatrixUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.ok(roomBody));
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MATRIX_USER_ID))
        .thenReturn("consultant-access-token");
  }

  @Test
  void createChatV1ShouldTakeMatrixPathAndNeverCallRocketChat() throws Exception {
    var response = createChatFacade.createChatV1(chatDTO, consultant);

    assertThat(response.getGroupId()).isEqualTo(MATRIX_ROOM_ID);
    verify(matrixSynapseService).createRoomAsMatrixUser(anyString(), anyString(), anyString());
    verifyNoInteractions(rocketChatService);
  }

  @Test
  void createChatV2ShouldTakeMatrixPathAndNeverCallRocketChat() throws Exception {
    var response = createChatFacade.createChatV2(chatDTO, consultant);

    assertThat(response.getGroupId()).isEqualTo(MATRIX_ROOM_ID);
    verify(matrixSynapseService).createRoomAsMatrixUser(anyString(), anyString(), anyString());
    verifyNoInteractions(rocketChatService);
  }
}
