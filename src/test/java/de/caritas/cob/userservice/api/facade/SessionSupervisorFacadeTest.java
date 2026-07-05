package de.caritas.cob.userservice.api.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

/**
 * ADR-008 safety contract: supervisor feedback / asides must live in a SEPARATE Matrix room the
 * client is never invited to. These tests lock the behaviour that {@code addSupervisor} stores the
 * SIDE room id (never the client room id) on the entity and provisions the side room, so a later
 * regression can't silently route asides back into the client room.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionSupervisorFacadeTest {

  private static final Long SESSION_ID = 42L;
  private static final String CLIENT_ROOM = "!clientroom:oriso";
  private static final String SIDE_ROOM = "!sideroom:oriso";
  private static final String SUPERVISOR_ID = "sup-1";
  private static final String SUPERVISOR_MXID = "@sup:oriso";
  private static final String CONSULTANT_MXID = "@con:oriso";
  private static final String CLIENT_MXID = "@client:oriso";

  @InjectMocks private SessionSupervisorFacade facade;

  @Mock private SessionSupervisorRepository sessionSupervisorRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private de.caritas.cob.userservice.api.service.user.UserAccountService userAccountService;
  @Mock private de.caritas.cob.userservice.api.port.out.IdentityClient identityClient;

  private Session session;
  private Consultant addedBy;
  private Consultant supervisor;

  @BeforeEach
  void setup() throws Exception {
    addedBy = new Consultant();
    addedBy.setId("con-1");
    addedBy.setMatrixUserId(CONSULTANT_MXID);

    supervisor = new Consultant();
    supervisor.setId(SUPERVISOR_ID);
    supervisor.setUsername("sup");
    supervisor.setMatrixUserId(SUPERVISOR_MXID);
    supervisor.setSupervisor(true);

    session = new Session();
    session.setId(SESSION_ID);
    session.setMatrixRoomId(CLIENT_ROOM);
    session.setAgencyId(7L);
    session.setConsultant(addedBy); // assigned consultant == addedBy → has permission

    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(consultantRepository.findById(SUPERVISOR_ID)).thenReturn(Optional.of(supervisor));
    when(sessionSupervisorRepository.findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(
            SESSION_ID, SUPERVISOR_ID))
        .thenReturn(Optional.empty());
    // supervisor is in the same agency (7L) as the session
    when(consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(SUPERVISOR_ID))
        .thenReturn(
            List.of(
                de.caritas.cob.userservice.api.model.ConsultantAgency.builder()
                    .agencyId(7L)
                    .build()));
    when(sessionSupervisorRepository.findBySessionIdAndIsActiveTrue(SESSION_ID))
        .thenReturn(List.of());
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("tok");
    var roomResponse = new MatrixCreateRoomResponseDTO();
    roomResponse.setRoomId(SIDE_ROOM);
    when(matrixSynapseService.createRoom(any(), any(), any()))
        .thenReturn(ResponseEntity.ok(roomResponse));
    when(matrixSynapseService.setUserPowerLevel(
            any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
        .thenReturn(true);
    when(matrixSynapseService.joinRoom(any(), any())).thenReturn(true);
    when(sessionSupervisorRepository.save(any()))
        .thenAnswer(inv -> inv.getArgument(0, SessionSupervisor.class));
  }

  @Test
  void addSupervisor_Should_storeSideRoomId_notClientRoomId() throws Exception {
    SessionSupervisor saved = facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, "reason");

    assertThat(saved.getMatrixRoomId())
        .as("entity must hold the supervision SIDE room, never the client room")
        .isEqualTo(SIDE_ROOM)
        .isNotEqualTo(CLIENT_ROOM);
  }

  @Test
  void addSupervisor_Should_provisionSideRoom_andNeverInviteIntoItAsClientRoom() throws Exception {
    facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, "reason");

    // A side room was created...
    verify(matrixSynapseService).createRoom(any(), any(), any());
    // ...and the supervisor was invited to BOTH the client room (observation) and the side room.
    ArgumentCaptor<String> rooms = ArgumentCaptor.forClass(String.class);
    verify(matrixSynapseService, org.mockito.Mockito.atLeast(2))
        .inviteUserToRoom(rooms.capture(), eq(SUPERVISOR_MXID), any());
    assertThat(rooms.getAllValues()).contains(CLIENT_ROOM, SIDE_ROOM);
  }

  @Test
  void addSupervisor_Should_reuseExistingSideRoom_when_anotherSupervisorAlreadyHasOne()
      throws Exception {
    SessionSupervisor existing = SessionSupervisor.builder().matrixRoomId(SIDE_ROOM).build();
    when(sessionSupervisorRepository.findBySessionIdAndIsActiveTrue(SESSION_ID))
        .thenReturn(List.of(existing));

    SessionSupervisor saved = facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, "reason");

    assertThat(saved.getMatrixRoomId()).isEqualTo(SIDE_ROOM);
    // no NEW room created — the existing side room is reused
    verify(matrixSynapseService, never()).createRoom(any(), any(), any());
  }

  @Test
  void addSupervisor_Should_notTreatOldStyleClientRoomRow_asASideRoom() throws Exception {
    // A pre-ADR-008 row stored the CLIENT room id in matrixRoomId. It must NOT be reused as a side
    // room (that would re-open the leak) — a fresh side room is created instead.
    SessionSupervisor oldStyle = SessionSupervisor.builder().matrixRoomId(CLIENT_ROOM).build();
    when(sessionSupervisorRepository.findBySessionIdAndIsActiveTrue(SESSION_ID))
        .thenReturn(List.of(oldStyle));

    SessionSupervisor saved = facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, "reason");

    assertThat(saved.getMatrixRoomId()).isEqualTo(SIDE_ROOM).isNotEqualTo(CLIENT_ROOM);
    verify(matrixSynapseService).createRoom(any(), any(), any());
  }

  @Test
  void addSupervisor_Should_neverInviteTheClient_intoTheSideRoom() throws Exception {
    // ADR-008 safeguarding regression guard. The asker (client) must NEVER be invited into the
    // supervision side room — nor anywhere by this flow. We give the session's client a Matrix id
    // so that a future change which started inviting it would be caught here. Today only the
    // supervisor is ever invited (client-room observation + side-room membership), so every
    // captured
    // invitee must be the supervisor and never the client.
    User client = new User();
    client.setMatrixUserId(CLIENT_MXID);
    session.setUser(client);

    facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, "reason");

    ArgumentCaptor<String> invitedUsers = ArgumentCaptor.forClass(String.class);
    verify(matrixSynapseService, org.mockito.Mockito.atLeastOnce())
        .inviteUserToRoom(any(), invitedUsers.capture(), any());
    assertThat(invitedUsers.getAllValues())
        .as("only the supervisor is ever invited; the client is never invited into any room")
        .containsOnly(SUPERVISOR_MXID)
        .doesNotContain(CLIENT_MXID);
    verify(matrixSynapseService, never()).inviteUserToRoom(eq(SIDE_ROOM), eq(CLIENT_MXID), any());
  }
}
