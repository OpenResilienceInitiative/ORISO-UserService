package de.caritas.cob.userservice.api.service.matrixrtc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MatrixRtcCallPolicyServiceTest {

  private static final String ROOM_ID = "!source:matrix.oriso.org";
  private static final String MATRIX_USER_ID = "@participant:matrix.oriso.org";
  private static final long TENANT_ID = 7L;

  @Mock private SessionRepository sessionRepository;
  @Mock private ChatRepository chatRepository;
  @Mock private SessionSupervisorRepository sessionSupervisorRepository;
  @Mock private TeamDiscussionRepository teamDiscussionRepository;
  @Mock private TenantService tenantService;
  @Mock private MatrixSynapseService matrixSynapseService;

  private MatrixRtcCallPolicyService service;

  @BeforeEach
  void setUp() {
    service =
        new MatrixRtcCallPolicyService(
            sessionRepository,
            chatRepository,
            sessionSupervisorRepository,
            teamDiscussionRepository,
            tenantService,
            matrixSynapseService);
    when(matrixSynapseService.getRoomMembers(ROOM_ID))
        .thenReturn(Optional.of(List.of(MATRIX_USER_ID)));
  }

  @Test
  void resolvesAgencyCounsellingAgainstOneOnOneFlags() {
    var session = session(ConversationType.AGENCY_COUNSELLING);
    when(sessionRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.of(session));
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(
            tenant(
                enabledSettings()
                    .featureAudioCallsOneOnOneChatsEnabled(true)
                    .featureVideoCallsOneOnOneChatsEnabled(false)));

    assertThat(service.resolve(ROOM_ID, MATRIX_USER_ID))
        .isEqualTo(new CallMediaPolicy(true, false));
  }

  @Test
  void resolvesTeamCounsellingAgainstOneOnOneFlagsLikeTheFrontend() {
    var session =
        Session.builder()
            .tenantId(TENANT_ID)
            .conversationType(ConversationType.AGENCY_COUNSELLING)
            .registrationType(Session.RegistrationType.REGISTERED)
            .postcode("00000")
            .status(Session.SessionStatus.IN_PROGRESS)
            .teamSession(true)
            .build();
    when(sessionRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.of(session));
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(
            tenant(
                enabledSettings()
                    .featureAudioCallsOneOnOneChatsEnabled(true)
                    .featureVideoCallsOneOnOneChatsEnabled(false)
                    .featureAudioCallsGroupChatsEnabled(false)
                    .featureVideoCallsGroupChatsEnabled(true)));

    assertThat(service.resolve(ROOM_ID, MATRIX_USER_ID))
        .isEqualTo(new CallMediaPolicy(true, false));
  }

  @Test
  void resolvesLiveChatAgainstAnonymousFlags() {
    var session = session(ConversationType.LIVE_CHAT);
    when(sessionRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.of(session));
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(
            tenant(
                enabledSettings()
                    .featureAudioCallsAnonymousChatsEnabled(false)
                    .featureVideoCallsAnonymousChatsEnabled(true)));

    assertThat(service.resolve(ROOM_ID, MATRIX_USER_ID))
        .isEqualTo(new CallMediaPolicy(false, true));
  }

  @Test
  void resolvesInternalAndSelfHelpChatsAgainstGroupFlags() {
    var chat = mock(Chat.class);
    var owner = mock(Consultant.class);
    when(chat.getChatOwner()).thenReturn(owner);
    when(owner.getTenantId()).thenReturn(TENANT_ID);
    when(sessionRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.empty());
    when(chatRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.of(chat));
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(
            tenant(
                enabledSettings()
                    .featureAudioCallsGroupChatsEnabled(true)
                    .featureVideoCallsGroupChatsEnabled(false)));

    assertThat(service.resolve(ROOM_ID, MATRIX_USER_ID))
        .isEqualTo(new CallMediaPolicy(true, false));
  }

  @Test
  void resolvesSupervisionRoomAgainstSupervisionFlags() {
    var supervision = mock(SessionSupervisor.class);
    var session = mock(Session.class);
    when(session.getTenantId()).thenReturn(TENANT_ID);
    when(supervision.getSession()).thenReturn(session);
    when(sessionSupervisorRepository.findByMatrixRoomId(ROOM_ID))
        .thenReturn(Optional.of(supervision));
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(
            tenant(
                enabledSettings()
                    .featureAudioCallsSupervisionChatsEnabled(false)
                    .featureVideoCallsSupervisionChatsEnabled(true)));

    assertThat(service.resolve(ROOM_ID, MATRIX_USER_ID))
        .isEqualTo(new CallMediaPolicy(false, true));
  }

  @Test
  void resolvesTeamDiscussionAgainstGroupFlags() {
    var discussion = mock(TeamDiscussion.class);
    when(discussion.getTenantId()).thenReturn(TENANT_ID);
    when(sessionRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.empty());
    when(chatRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.empty());
    when(teamDiscussionRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.of(discussion));
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(
            tenant(
                enabledSettings()
                    .featureAudioCallsGroupChatsEnabled(false)
                    .featureVideoCallsGroupChatsEnabled(true)));

    assertThat(service.resolve(ROOM_ID, MATRIX_USER_ID))
        .isEqualTo(new CallMediaPolicy(false, true));
  }

  @Test
  void masterAndMediaMasterFlagsCannotBeOverriddenByChatType() {
    var session = session(ConversationType.LIVE_CHAT);
    when(sessionRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.of(session));
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(
            tenant(
                enabledSettings()
                    .featureCallsEnabled(false)
                    .featureAudioCallsAnonymousChatsEnabled(true)
                    .featureVideoCallsAnonymousChatsEnabled(true)));

    assertThat(service.resolve(ROOM_ID, MATRIX_USER_ID)).isEqualTo(CallMediaPolicy.denied());
  }

  @Test
  void missingRoomOrTenantSettingsFailsClosed() {
    assertThat(service.resolve(ROOM_ID, MATRIX_USER_ID)).isEqualTo(CallMediaPolicy.denied());
    verifyNoInteractions(tenantService);

    var session = session(ConversationType.AGENCY_COUNSELLING);
    when(sessionRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.of(session));
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(new RestrictedTenantDTO().id(TENANT_ID).settings(null));

    assertThat(service.resolve(ROOM_ID, MATRIX_USER_ID)).isEqualTo(CallMediaPolicy.denied());
  }

  @Test
  void absentLegacyFlagsRetainExistingEnabledSemantics() {
    var session = session(ConversationType.AGENCY_COUNSELLING);
    when(sessionRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.of(session));
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID)).thenReturn(tenant(new Settings()));

    assertThat(service.resolve(ROOM_ID, MATRIX_USER_ID)).isEqualTo(new CallMediaPolicy(true, true));
  }

  @Test
  void callerMustCurrentlyBelongToTheSourceConversationRoom() {
    when(matrixSynapseService.getRoomMembers(ROOM_ID))
        .thenReturn(Optional.of(List.of("@someone-else:matrix.oriso.org")));

    assertThat(service.resolve(ROOM_ID, MATRIX_USER_ID)).isEqualTo(CallMediaPolicy.denied());
    verifyNoInteractions(tenantService);
  }

  @Test
  void unavailableSourceRoomMembershipFailsClosed() {
    when(matrixSynapseService.getRoomMembers(ROOM_ID)).thenReturn(Optional.empty());

    assertThat(service.resolve(ROOM_ID, MATRIX_USER_ID)).isEqualTo(CallMediaPolicy.denied());
    verifyNoInteractions(tenantService);
  }

  private Session session(ConversationType conversationType) {
    var session = mock(Session.class);
    when(session.getTenantId()).thenReturn(TENANT_ID);
    when(session.getConversationType()).thenReturn(conversationType);
    return session;
  }

  private RestrictedTenantDTO tenant(Settings settings) {
    return new RestrictedTenantDTO().id(TENANT_ID).settings(settings);
  }

  private Settings enabledSettings() {
    return new Settings()
        .featureCallsEnabled(true)
        .featureAudioCallsEnabled(true)
        .featureVideoCallsEnabled(true);
  }
}
