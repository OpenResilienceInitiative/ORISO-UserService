package de.caritas.cob.userservice.api.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.SupportRoom;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeAuditEventRepository;
import de.caritas.cob.userservice.api.port.out.SupportRoomRepository;
import de.caritas.cob.userservice.api.service.handshake.HandshakePurpose;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
class SupportRoomServiceTest {

  private static final String GSA_ID = "gsa-1";
  private static final String CONSULTANT_ID = "consultant-1";
  private static final String GSA_MXID = "@sam.support:caritas.local";
  private static final String CONSULTANT_MXID = "@tina.consultant:caritas.local";
  private static final String ROOM_ID = "!room:caritas.local";

  @InjectMocks private SupportRoomService supportRoomService;

  @Mock private SupportRoomRepository supportRoomRepository;
  @Mock private HandshakeAuditEventRepository handshakeAuditEventRepository;
  @Mock private AdminRepository adminRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private MatrixConfig matrixConfig;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(supportRoomService, "roomTtlHours", 4L);
    ReflectionTestUtils.setField(supportRoomService, "sweepBatchSize", 200);
    lenient().when(matrixConfig.getServerName()).thenReturn("caritas.local");
  }

  private HandshakeSession confirmedSession() {
    return HandshakeSession.builder()
        .id("hs-1")
        .purpose(HandshakePurpose.SUPPORT_ACCESS)
        .initiatorId(GSA_ID)
        .counterpartId(CONSULTANT_ID)
        .status(HandshakeSession.HandshakeStatus.CONFIRMED)
        .createDate(LocalDateTime.now(ZoneOffset.UTC))
        .expiryDate(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5))
        .tenantId(1L)
        .build();
  }

  private Admin gsa() {
    return Admin.builder()
        .id(GSA_ID)
        .username("sam.support")
        .firstName("Sam")
        .lastName("Support")
        .email("sam@support.example")
        .type(Admin.AdminType.SUPPORT)
        .build();
  }

  private Consultant consultantWithMatrix() {
    var consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    consultant.setMatrixUserId(CONSULTANT_MXID);
    return consultant;
  }

  private void stubHappyMatrix() throws Exception {
    when(matrixSynapseService.userExists("sam.support")).thenReturn(true);
    when(matrixSynapseService.loginAsUserAccessToken(GSA_MXID)).thenReturn("gsa-token");
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MXID))
        .thenReturn("consultant-token");
    var roomResponse = new MatrixCreateRoomResponseDTO();
    roomResponse.setRoomId(ROOM_ID);
    when(matrixSynapseService.createRoom(anyString(), any(), eq("gsa-token")))
        .thenReturn(ResponseEntity.ok(roomResponse));
    when(matrixSynapseService.joinRoom(ROOM_ID, "consultant-token")).thenReturn(true);
  }

  // --- creation on confirmed handshake ---

  @Test
  void createForConfirmedHandshake_Should_CreateEncryptedRoomWithBothMembersAndFourHourLease()
      throws Exception {
    when(adminRepository.findById(GSA_ID)).thenReturn(Optional.of(gsa()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(consultantWithMatrix()));
    stubHappyMatrix();
    when(supportRoomRepository.save(any(SupportRoom.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var room = supportRoomService.createForConfirmedHandshake(confirmedSession());

    verify(matrixSynapseService).inviteUserToRoom(ROOM_ID, CONSULTANT_MXID, "gsa-token");
    verify(matrixSynapseService).joinRoom(ROOM_ID, "consultant-token");
    assertThat(room.getMatrixRoomId()).isEqualTo(ROOM_ID);
    assertThat(room.getStatus()).isEqualTo(SupportRoom.SupportRoomStatus.ACTIVE);
    assertThat(room.getSupportAdminId()).isEqualTo(GSA_ID);
    assertThat(room.getConsultantId()).isEqualTo(CONSULTANT_ID);
    assertThat(room.getExpiryDate())
        .isAfter(LocalDateTime.now(ZoneOffset.UTC).plusHours(3))
        .isBefore(LocalDateTime.now(ZoneOffset.UTC).plusHours(5));
    verify(handshakeAuditEventRepository).save(any());
  }

  @Test
  void createForConfirmedHandshake_Should_Fail_When_ConsultantHasNoMatrixIdentity() {
    when(adminRepository.findById(GSA_ID)).thenReturn(Optional.of(gsa()));
    var consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(consultant));

    assertThatThrownBy(() -> supportRoomService.createForConfirmedHandshake(confirmedSession()))
        .isInstanceOf(IllegalStateException.class);

    verify(supportRoomRepository, never()).save(any());
  }

  // --- termination ---

  private SupportRoom activeRoom() {
    return SupportRoom.builder()
        .id("sr-1")
        .handshakeId("hs-1")
        .matrixRoomId(ROOM_ID)
        .supportAdminId(GSA_ID)
        .supportAdminMatrixId(GSA_MXID)
        .consultantId(CONSULTANT_ID)
        .status(SupportRoom.SupportRoomStatus.ACTIVE)
        .createDate(LocalDateTime.now(ZoneOffset.UTC).minusHours(1))
        .expiryDate(LocalDateTime.now(ZoneOffset.UTC).plusHours(3))
        .build();
  }

  @Test
  void terminate_Should_KickSupportAdminAndCloseRoom_When_CalledByTheRoomsConsultant() {
    var room = activeRoom();
    when(supportRoomRepository.findById("sr-1")).thenReturn(Optional.of(room));
    when(matrixSynapseService.loginAsUserAccessToken(GSA_MXID)).thenReturn("gsa-token");
    when(matrixSynapseService.leaveRoom(ROOM_ID, "gsa-token")).thenReturn(true);
    when(supportRoomRepository.save(any(SupportRoom.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var consultant = new AuthenticatedUser();
    consultant.setUserId(CONSULTANT_ID);

    supportRoomService.terminate(consultant, "sr-1");

    assertThat(room.getStatus()).isEqualTo(SupportRoom.SupportRoomStatus.CLOSED);
    assertThat(room.getCloseReason()).isEqualTo("TERMINATED");
    verify(matrixSynapseService).leaveRoom(ROOM_ID, "gsa-token");
    verify(handshakeAuditEventRepository).save(any());
  }

  @Test
  void terminate_Should_Forbid_When_CallerIsNotTheRoomsConsultant() {
    when(supportRoomRepository.findById("sr-1")).thenReturn(Optional.of(activeRoom()));
    var stranger = new AuthenticatedUser();
    stranger.setUserId("someone-else");

    assertThatThrownBy(() -> supportRoomService.terminate(stranger, "sr-1"))
        .isInstanceOf(ForbiddenException.class);

    verify(matrixSynapseService, never()).leaveRoom(anyString(), anyString());
  }

  // --- hard four-hour cutoff ---

  @Test
  void sweepExpired_Should_KickAndCloseLapsedActiveRooms() {
    var room = activeRoom();
    room.setExpiryDate(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    when(supportRoomRepository.findAllByStatusAndExpiryDateBefore(
            eq(SupportRoom.SupportRoomStatus.ACTIVE),
            any(LocalDateTime.class),
            any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(List.of(room));
    when(matrixSynapseService.loginAsUserAccessToken(GSA_MXID)).thenReturn("gsa-token");
    when(matrixSynapseService.leaveRoom(ROOM_ID, "gsa-token")).thenReturn(true);
    when(supportRoomRepository.save(any(SupportRoom.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    supportRoomService.sweepExpired();

    assertThat(room.getStatus()).isEqualTo(SupportRoom.SupportRoomStatus.CLOSED);
    assertThat(room.getCloseReason()).isEqualTo("EXPIRED");
    verify(handshakeAuditEventRepository).save(any());
  }

  @Test
  void sweepExpired_Should_CloseRoomEvenIfMatrixKickFails() {
    // Fail-safe: the lease must end at 4h even when the homeserver hiccups.
    var room = activeRoom();
    room.setExpiryDate(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    when(supportRoomRepository.findAllByStatusAndExpiryDateBefore(
            eq(SupportRoom.SupportRoomStatus.ACTIVE),
            any(LocalDateTime.class),
            any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(List.of(room));
    when(matrixSynapseService.loginAsUserAccessToken(GSA_MXID))
        .thenThrow(new RuntimeException("synapse down"));
    when(supportRoomRepository.save(any(SupportRoom.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    supportRoomService.sweepExpired();

    assertThat(room.getStatus()).isEqualTo(SupportRoom.SupportRoomStatus.CLOSED);
  }

  // --- listing ---

  @Test
  void activeFor_Should_ReturnRoomsWhereTheUserIsEitherSide() {
    var user = new AuthenticatedUser();
    user.setUserId(CONSULTANT_ID);
    when(supportRoomRepository.findAllByStatusAndConsultantIdOrStatusAndSupportAdminId(
            SupportRoom.SupportRoomStatus.ACTIVE,
            CONSULTANT_ID,
            SupportRoom.SupportRoomStatus.ACTIVE,
            CONSULTANT_ID))
        .thenReturn(List.of(activeRoom()));

    var items = supportRoomService.activeFor(user);

    assertThat(items).hasSize(1);
    assertThat(items.get(0).getMatrixRoomId()).isEqualTo(ROOM_ID);
  }
}
