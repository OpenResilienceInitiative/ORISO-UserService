package de.caritas.cob.userservice.api.service.support;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus;
import de.caritas.cob.userservice.api.model.SupportAccessSession;
import de.caritas.cob.userservice.api.model.SupportAccessSession.SupportAccessSessionStatus;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeAuditEventRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeSessionRepository;
import de.caritas.cob.userservice.api.port.out.SupportAccessSessionRepository;
import de.caritas.cob.userservice.api.service.handshake.HandshakePurpose;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SupportAccessMatrixWorkerTest {

  private static final String SESSION_ID = "11111111-2222-3333-4444-555555555555";
  private static final String HANDSHAKE_ID = "hs-1";
  private static final String SUPPORT_MATRIX_ID = "@support-111111112222333344445:oriso";
  private static final String CONSULTANT_MATRIX_ID = "@consultant:oriso";
  private static final String ROOM_ID = "!room:oriso";
  private static final String CALL_ROOM_ID = "!call:oriso";

  @InjectMocks private SupportAccessMatrixWorker worker;

  @Mock private SupportAccessSessionRepository sessionRepository;
  @Mock private HandshakeSessionRepository handshakeSessionRepository;
  @Mock private HandshakeAuditEventRepository auditRepository;
  @Mock private AdminRepository adminRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private MatrixConfig matrixConfig;

  @BeforeEach
  void setUp() throws Exception {
    ReflectionTestUtils.setField(worker, "sessionTtlHours", 4L);
    ReflectionTestUtils.setField(worker, "maxProvisioningAttempts", 3);
    lenient()
        .when(sessionRepository.saveAndFlush(any(SupportAccessSession.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  // --- provisioning ---

  @Test
  void provision_Should_CreateAFreshIdentityAndEncryptedRoomAndVerifyMembership() throws Exception {
    givenConfirmedHandshake();
    givenParticipants();
    when(sessionRepository.findByHandshakeId(HANDSHAKE_ID))
        .thenReturn(Optional.of(session(SupportAccessSessionStatus.PROVISIONING)));
    givenMatrixHappyPath();

    worker.provision(HANDSHAKE_ID);

    var captor = org.mockito.ArgumentCaptor.forClass(SupportAccessSession.class);
    verify(sessionRepository, org.mockito.Mockito.atLeastOnce()).saveAndFlush(captor.capture());
    var last = captor.getValue();
    assertThat(last.getStatus()).isEqualTo(SupportAccessSessionStatus.ACTIVE);
    assertThat(last.getMatrixRoomId()).isEqualTo(ROOM_ID);
    // A per-session localpart, derived from this session's id and never an admin's username.
    verify(matrixSynapseService)
        .createUser(org.mockito.ArgumentMatchers.startsWith("support-"), anyString(), anyString());
    verify(matrixSynapseService).createEncryptedRoom(anyString(), anyString(), anyString());
  }

  @Test
  void provision_Should_NotBuildASecondRoom_OnRedeliveryOfAFinishedJob() throws Exception {
    givenConfirmedHandshake();
    when(sessionRepository.findByHandshakeId(HANDSHAKE_ID))
        .thenReturn(Optional.of(session(SupportAccessSessionStatus.ACTIVE)));

    worker.provision(HANDSHAKE_ID);

    verify(matrixSynapseService, never())
        .createEncryptedRoom(anyString(), anyString(), anyString());
    verify(matrixSynapseService, never()).createUser(anyString(), anyString(), anyString());
  }

  @Test
  void provision_Should_RefuseAHandshakeThatWasNeverConfirmed() throws Exception {
    var handshake = handshake();
    handshake.setStatus(HandshakeStatus.PENDING);
    when(handshakeSessionRepository.findById(HANDSHAKE_ID)).thenReturn(Optional.of(handshake));

    assertThatThrownBy(() -> worker.provision(HANDSHAKE_ID))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void provision_Should_RejectARoomThatHoldsAnUnexpectedMember() throws Exception {
    givenConfirmedHandshake();
    givenParticipants();
    when(sessionRepository.findByHandshakeId(HANDSHAKE_ID))
        .thenReturn(Optional.of(session(SupportAccessSessionStatus.PROVISIONING)));
    givenMatrixHappyPath();
    when(matrixSynapseService.getRoomMembers(ROOM_ID))
        .thenReturn(
            Optional.of(List.of(SUPPORT_MATRIX_ID, CONSULTANT_MATRIX_ID, "@stranger:oriso")));

    assertThatThrownBy(() -> worker.provision(HANDSHAKE_ID))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void provision_Should_CompensateAndFailTerminally_AtTheAttemptLimit() throws Exception {
    givenConfirmedHandshake();
    givenParticipants();
    var existing = session(SupportAccessSessionStatus.PROVISIONING);
    existing.setProvisioningAttempts(2);
    existing.setMatrixRoomId(ROOM_ID);
    existing.setSupportAdminMatrixId(SUPPORT_MATRIX_ID);
    when(sessionRepository.findByHandshakeId(HANDSHAKE_ID)).thenReturn(Optional.of(existing));
    when(matrixSynapseService.loginAsUserAccessToken(SUPPORT_MATRIX_ID))
        .thenThrow(new IllegalStateException("homeserver down"));

    worker.provision(HANDSHAKE_ID);

    assertThat(existing.getStatus()).isEqualTo(SupportAccessSessionStatus.PROVISIONING_FAILED);
    // The lease is released and the orphaned room removed, so nothing survives a failed start.
    assertThat(existing.getActiveLeaseKey()).isNull();
    verify(matrixSynapseService).purgeRoom(ROOM_ID);
    verify(matrixSynapseService).deactivateUser(SUPPORT_MATRIX_ID);
  }

  // --- withdrawal ---

  @Test
  void revoke_Should_CloseOnlyAfterEveryWithdrawalStepWasConfirmed() throws Exception {
    var session = session(SupportAccessSessionStatus.REVOCATION_PENDING);
    session.setMatrixRoomId(ROOM_ID);
    session.setCallMatrixRoomId(CALL_ROOM_ID);
    session.setSupportAdminMatrixId(SUPPORT_MATRIX_ID);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(matrixSynapseService.purgeRoom(anyString())).thenReturn(true);
    when(matrixSynapseService.deactivateUser(SUPPORT_MATRIX_ID)).thenReturn(true);
    when(matrixSynapseService.getRoomMembers(ROOM_ID)).thenReturn(Optional.empty());

    worker.revoke(SESSION_ID);

    verify(matrixSynapseService).purgeRoom(CALL_ROOM_ID);
    verify(matrixSynapseService).deactivateUser(SUPPORT_MATRIX_ID);
    verify(matrixSynapseService).purgeRoom(ROOM_ID);
    assertThat(session.getStatus()).isEqualTo(SupportAccessSessionStatus.CLOSED);
    assertThat(session.getActiveLeaseKey()).isNull();
  }

  @Test
  void revoke_Should_StayPending_WhenTheIdentityCouldNotBeDeactivated() throws Exception {
    var session = session(SupportAccessSessionStatus.REVOCATION_PENDING);
    session.setMatrixRoomId(ROOM_ID);
    session.setSupportAdminMatrixId(SUPPORT_MATRIX_ID);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(matrixSynapseService.deactivateUser(SUPPORT_MATRIX_ID)).thenReturn(false);

    assertThatThrownBy(() -> worker.revoke(SESSION_ID)).isInstanceOf(IllegalStateException.class);

    // A Matrix outage must never be reported as a completed withdrawal.
    assertThat(session.getStatus()).isEqualTo(SupportAccessSessionStatus.REVOCATION_PENDING);
    verify(matrixSynapseService, never()).purgeRoom(ROOM_ID);
  }

  @Test
  void revoke_Should_StayPending_WhenTheSupportIdentityIsStillAMemberOfALiveRoom()
      throws Exception {
    var session = session(SupportAccessSessionStatus.REVOCATION_PENDING);
    session.setMatrixRoomId(ROOM_ID);
    session.setSupportAdminMatrixId(SUPPORT_MATRIX_ID);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(matrixSynapseService.deactivateUser(SUPPORT_MATRIX_ID)).thenReturn(true);
    when(matrixSynapseService.purgeRoom(ROOM_ID)).thenReturn(true);
    when(matrixSynapseService.getRoomMembers(ROOM_ID))
        .thenReturn(Optional.of(List.of(SUPPORT_MATRIX_ID)));
    when(matrixSynapseService.isUserDeactivated(SUPPORT_MATRIX_ID)).thenReturn(false);

    assertThatThrownBy(() -> worker.revoke(SESSION_ID)).isInstanceOf(IllegalStateException.class);

    assertThat(session.getStatus()).isEqualTo(SupportAccessSessionStatus.REVOCATION_PENDING);
  }

  @Test
  void revoke_Should_Close_WhenTheIdentityIsProvenDeadEvenThoughThePurgedRoomStillListsIt()
      throws Exception {
    var session = session(SupportAccessSessionStatus.REVOCATION_PENDING);
    session.setMatrixRoomId(ROOM_ID);
    session.setSupportAdminMatrixId(SUPPORT_MATRIX_ID);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(matrixSynapseService.deactivateUser(SUPPORT_MATRIX_ID)).thenReturn(true);
    when(matrixSynapseService.purgeRoom(ROOM_ID)).thenReturn(true);
    // Synapse keeps the historical membership rows of a purged room, so asking the room alone
    // would leave every withdrawal REVOCATION_PENDING forever. What actually decides whether
    // access is gone is the identity: a deactivated user holds no tokens and can join nothing.
    when(matrixSynapseService.getRoomMembers(ROOM_ID))
        .thenReturn(Optional.of(List.of(SUPPORT_MATRIX_ID)));
    when(matrixSynapseService.isUserDeactivated(SUPPORT_MATRIX_ID)).thenReturn(true);

    worker.revoke(SESSION_ID);

    assertThat(session.getStatus()).isEqualTo(SupportAccessSessionStatus.CLOSED);
    assertThat(session.getActiveLeaseKey()).isNull();
  }

  @Test
  void revoke_Should_StayPending_WhenSynapseCannotSayWhetherTheIdentityIsDead() throws Exception {
    var session = session(SupportAccessSessionStatus.REVOCATION_PENDING);
    session.setMatrixRoomId(ROOM_ID);
    session.setSupportAdminMatrixId(SUPPORT_MATRIX_ID);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(matrixSynapseService.deactivateUser(SUPPORT_MATRIX_ID)).thenReturn(true);
    when(matrixSynapseService.purgeRoom(ROOM_ID)).thenReturn(true);
    when(matrixSynapseService.getRoomMembers(ROOM_ID))
        .thenReturn(Optional.of(List.of(SUPPORT_MATRIX_ID)));
    // Unknown is not proof, and fail-closed means unproven withdrawal is not withdrawal.
    when(matrixSynapseService.isUserDeactivated(SUPPORT_MATRIX_ID)).thenReturn(null);

    assertThatThrownBy(() -> worker.revoke(SESSION_ID)).isInstanceOf(IllegalStateException.class);

    assertThat(session.getStatus()).isEqualTo(SupportAccessSessionStatus.REVOCATION_PENDING);
  }

  @Test
  void revoke_Should_NotPurgeTheSameRoomTwice_WhenTheCallRanInTheSupportRoom() throws Exception {
    var session = session(SupportAccessSessionStatus.REVOCATION_PENDING);
    session.setMatrixRoomId(ROOM_ID);
    // The current Element Call integration runs the call in the support room itself.
    session.setCallMatrixRoomId(ROOM_ID);
    session.setSupportAdminMatrixId(SUPPORT_MATRIX_ID);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(matrixSynapseService.deactivateUser(SUPPORT_MATRIX_ID)).thenReturn(true);
    when(matrixSynapseService.purgeRoom(ROOM_ID)).thenReturn(true, false);
    when(matrixSynapseService.getRoomMembers(ROOM_ID)).thenReturn(Optional.empty());

    worker.revoke(SESSION_ID);

    // A second purge of the same room would fail and keep the session REVOCATION_PENDING forever,
    // reporting an outage that never happened.
    verify(matrixSynapseService, org.mockito.Mockito.times(1)).purgeRoom(ROOM_ID);
    assertThat(session.getStatus()).isEqualTo(SupportAccessSessionStatus.CLOSED);
  }

  @Test
  void revoke_Should_BeIdempotentOnAnAlreadyClosedSession() throws Exception {
    var session = session(SupportAccessSessionStatus.CLOSED);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

    worker.revoke(SESSION_ID);

    verify(matrixSynapseService, never()).purgeRoom(anyString());
    verify(matrixSynapseService, never()).deactivateUser(anyString());
  }

  // --- helpers ---

  private void givenConfirmedHandshake() throws Exception {
    when(handshakeSessionRepository.findById(HANDSHAKE_ID)).thenReturn(Optional.of(handshake()));
  }

  private void givenParticipants() throws Exception {
    var admin =
        Admin.builder()
            .id("gsa-1")
            .type(Admin.AdminType.SUPPORT)
            .username("gsa")
            .firstName("Sam")
            .lastName("Support")
            .email("support@example.org")
            .build();
    var consultant = new Consultant();
    consultant.setId("consultant-1");
    consultant.setMatrixUserId(CONSULTANT_MATRIX_ID);
    lenient().when(adminRepository.findById("gsa-1")).thenReturn(Optional.of(admin));
    lenient()
        .when(consultantRepository.findByIdAndDeleteDateIsNull("consultant-1"))
        .thenReturn(Optional.of(consultant));
  }

  private void givenMatrixHappyPath() throws Exception {
    var userResponse = new MatrixCreateUserResponseDTO();
    userResponse.setUserId(SUPPORT_MATRIX_ID);
    lenient()
        .when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.ok(userResponse));
    lenient().when(matrixSynapseService.loginAsUserAccessToken(anyString())).thenReturn("token");
    lenient()
        .when(matrixSynapseService.resolveRoomAlias(anyString(), anyString()))
        .thenReturn(Optional.empty());
    var roomResponse = new MatrixCreateRoomResponseDTO();
    roomResponse.setRoomId(ROOM_ID);
    lenient()
        .when(matrixSynapseService.createEncryptedRoom(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.ok(roomResponse));
    lenient().when(matrixSynapseService.joinRoom(anyString(), anyString())).thenReturn(true);
    lenient()
        .when(matrixSynapseService.getRoomMembers(ROOM_ID))
        .thenReturn(Optional.of(List.of(SUPPORT_MATRIX_ID, CONSULTANT_MATRIX_ID)));
  }

  private HandshakeSession handshake() {
    return HandshakeSession.builder()
        .id(HANDSHAKE_ID)
        .purpose(HandshakePurpose.SUPPORT_ACCESS)
        .initiatorId("gsa-1")
        .counterpartId("consultant-1")
        .agencyId(7L)
        .tenantId(1L)
        .status(HandshakeStatus.CONFIRMED)
        .createDate(nowInUtc())
        .expiryDate(nowInUtc().plusMinutes(5))
        .build();
  }

  private SupportAccessSession session(SupportAccessSessionStatus status) {
    return SupportAccessSession.builder()
        .id(SESSION_ID)
        .handshakeId(HANDSHAKE_ID)
        .supportAdminId("gsa-1")
        .consultantId("consultant-1")
        .agencyId(7L)
        .tenantId(1L)
        .status(status)
        .activeLeaseKey(SupportAccessSession.leaseKeyOf("gsa-1", "consultant-1", 7L))
        .createDate(nowInUtc())
        .expiryDate(nowInUtc().plusHours(4))
        .build();
  }
}
