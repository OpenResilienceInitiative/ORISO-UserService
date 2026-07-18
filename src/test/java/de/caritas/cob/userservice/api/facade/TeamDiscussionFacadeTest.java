package de.caritas.cob.userservice.api.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionParticipantRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionFeatureGate;
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

/** US#473 / ADR-016 — Team-Besprechung lifecycle. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamDiscussionFacadeTest {

  private static final Long SESSION_ID = 42L;
  private static final Long AGENCY_ID = 7L;
  private static final String CONSULTANT_ID = "consultant-1";
  private static final String ROOM_ID = "!discussion:oriso";

  @InjectMocks private TeamDiscussionFacade facade;

  @Mock private SessionRepository sessionRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private TeamDiscussionRepository teamDiscussionRepository;
  @Mock private TeamDiscussionParticipantRepository participantRepository;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private AgencyMatrixCredentialClient matrixCredentialClient;
  @Mock private TeamDiscussionFeatureGate featureGate;

  private Session session;
  private Consultant consultant;

  @BeforeEach
  void setUp() throws Exception {
    session = new Session();
    session.setId(SESSION_ID);
    session.setAgencyId(AGENCY_ID);
    session.setStatus(SessionStatus.NEW);
    session.setRegistrationType(RegistrationType.REGISTERED);
    session.setTenantId(3L);

    consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    consultant.setMatrixUserId("@consultant1:oriso");

    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(consultantRepository.findById(CONSULTANT_ID)).thenReturn(Optional.of(consultant));
    when(consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            CONSULTANT_ID, AGENCY_ID))
        .thenReturn(true);
    when(teamDiscussionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.empty());
    when(teamDiscussionRepository.save(any(TeamDiscussion.class)))
        .thenAnswer(
            invocation -> {
              TeamDiscussion d = invocation.getArgument(0);
              d.setId(99L);
              return d;
            });

    var credentials = new AgencyMatrixCredentialsDTO();
    credentials.setMatrixUserId("@agency7:oriso");
    credentials.setMatrixPassword("secret");
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(credentials));
    when(matrixSynapseService.loginUser("agency7", "secret")).thenReturn("agency-token");
    when(matrixSynapseService.loginAsUserAccessToken("@consultant1:oriso"))
        .thenReturn("consultant-token");

    var body = new MatrixCreateRoomResponseDTO();
    body.setRoomId(ROOM_ID);
    when(matrixSynapseService.createRoom(anyString(), anyString(), eq("agency-token")))
        .thenReturn(ResponseEntity.ok(body));
  }

  @Test
  void getOrCreateDiscussion_shouldCreateRoomWithAgencyOperatorAndRecordParticipant()
      throws Exception {
    var view = facade.getOrCreateDiscussion(SESSION_ID, CONSULTANT_ID);

    assertThat(view.matrixRoomId()).isEqualTo(ROOM_ID);
    assertThat(view.status()).isEqualTo(TeamDiscussion.Status.OPEN);
    verify(matrixSynapseService).createRoom(anyString(), anyString(), eq("agency-token"));
    verify(matrixSynapseService).inviteUserToRoom(ROOM_ID, "@consultant1:oriso", "agency-token");
    verify(matrixSynapseService).joinRoom(ROOM_ID, "consultant-token");
    verify(participantRepository).save(any());
  }

  @Test
  void getOrCreateDiscussion_shouldReuseExistingDiscussionWithoutCreatingRoom() throws Exception {
    when(teamDiscussionRepository.findBySessionId(SESSION_ID))
        .thenReturn(
            Optional.of(
                TeamDiscussion.builder()
                    .id(99L)
                    .sessionId(SESSION_ID)
                    .matrixRoomId(ROOM_ID)
                    .status(TeamDiscussion.Status.OPEN)
                    .build()));

    var view = facade.getOrCreateDiscussion(SESSION_ID, CONSULTANT_ID);

    assertThat(view.matrixRoomId()).isEqualTo(ROOM_ID);
    verify(matrixSynapseService, never()).createRoom(anyString(), anyString(), anyString());
  }

  @Test
  void getOrCreateDiscussion_shouldRejectAssignedSession() {
    session.setConsultant(consultant);

    assertThatThrownBy(() -> facade.getOrCreateDiscussion(SESSION_ID, CONSULTANT_ID))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void getOrCreateDiscussion_shouldRejectForeignAgencyConsultant() {
    when(consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            CONSULTANT_ID, AGENCY_ID))
        .thenReturn(false);

    assertThatThrownBy(() -> facade.getOrCreateDiscussion(SESSION_ID, CONSULTANT_ID))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void getOrCreateDiscussion_shouldRespectFeatureGate() throws Exception {
    doThrow(new ForbiddenException("Team discussion is disabled"))
        .when(featureGate)
        .requireEnabled();

    assertThatThrownBy(() -> facade.getOrCreateDiscussion(SESSION_ID, CONSULTANT_ID))
        .isInstanceOf(ForbiddenException.class);
    verify(matrixSynapseService, never()).createRoom(anyString(), anyString(), anyString());
  }

  @Test
  void archiveDiscussionIfPresent_shouldArchiveAndSetRoomReadOnly() {
    var discussion =
        TeamDiscussion.builder()
            .id(99L)
            .sessionId(SESSION_ID)
            .matrixRoomId(ROOM_ID)
            .status(TeamDiscussion.Status.OPEN)
            .build();
    when(teamDiscussionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(discussion));

    facade.archiveDiscussionIfPresent(session);

    assertThat(discussion.getStatus()).isEqualTo(TeamDiscussion.Status.ARCHIVED);
    assertThat(discussion.getArchiveDate()).isNotNull();
    verify(teamDiscussionRepository).save(discussion);
    verify(matrixSynapseService)
        .setRoomEventsDefaultPowerLevel(
            ROOM_ID, TeamDiscussionFacade.ARCHIVED_EVENTS_DEFAULT_POWER_LEVEL, "agency-token");
  }

  @Test
  void archiveDiscussionIfPresent_shouldNeverThrow() {
    when(teamDiscussionRepository.findBySessionId(SESSION_ID))
        .thenReturn(
            Optional.of(
                TeamDiscussion.builder()
                    .id(99L)
                    .sessionId(SESSION_ID)
                    .matrixRoomId(ROOM_ID)
                    .status(TeamDiscussion.Status.OPEN)
                    .build()));
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenThrow(new IllegalStateException("agency service down"));

    assertThatCode(() -> facade.archiveDiscussionIfPresent(session)).doesNotThrowAnyException();
  }

  @Test
  void archiveDiscussionIfPresent_shouldIgnoreAlreadyArchived() {
    var discussion =
        TeamDiscussion.builder()
            .id(99L)
            .sessionId(SESSION_ID)
            .matrixRoomId(ROOM_ID)
            .status(TeamDiscussion.Status.ARCHIVED)
            .archiveDate(LocalDateTime.now().minusDays(1))
            .build();
    when(teamDiscussionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(discussion));

    facade.archiveDiscussionIfPresent(session);

    verify(teamDiscussionRepository, never()).save(any());
    verify(matrixSynapseService, never())
        .setRoomEventsDefaultPowerLevel(anyString(), anyInt(), anyString());
  }

  @Test
  void getDiscussion_shouldReturnArchivedDiscussionForReadOnlyAccess() {
    when(teamDiscussionRepository.findBySessionId(SESSION_ID))
        .thenReturn(
            Optional.of(
                TeamDiscussion.builder()
                    .id(99L)
                    .sessionId(SESSION_ID)
                    .matrixRoomId(ROOM_ID)
                    .status(TeamDiscussion.Status.ARCHIVED)
                    .archiveDate(LocalDateTime.now())
                    .build()));

    var view = facade.getDiscussion(SESSION_ID, CONSULTANT_ID);

    assertThat(view).isPresent();
    assertThat(view.get().status()).isEqualTo(TeamDiscussion.Status.ARCHIVED);
    assertThat(view.get().archiveDate()).isNotNull();
  }
}
