package de.caritas.cob.userservice.api.service.support;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.SupportRoom;
import de.caritas.cob.userservice.api.model.SupportRoomCreationIntent;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeAuditEventRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeSessionRepository;
import de.caritas.cob.userservice.api.port.out.SupportRoomRepository;
import de.caritas.cob.userservice.api.service.handshake.HandshakePurpose;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SupportRoomServiceTest {

  private static final String GSA_ID = "gsa-1";
  private static final String CONSULTANT_ID = "consultant-1";
  private static final String GSA_MXID = "@sam.support:caritas.local";
  private static final String CONSULTANT_MXID = "@tina.consultant:caritas.local";
  private static final String ROOM_ID = "!room:caritas.local";
  private static final String INTENT_ID = "abca1483-3861-4fd1-b6c4-2aab42cf3300";
  private static final String NONCE = "a10f3d54-8316-40b0-a235-39e3966b55b9";
  private static final String LOCAL_ALIAS = "support-" + INTENT_ID;
  private static final String FULL_ALIAS = "#" + LOCAL_ALIAS + ":caritas.local";

  private SupportRoomService supportRoomService;

  @Mock private SupportRoomRepository supportRoomRepository;
  @Mock private HandshakeAuditEventRepository handshakeAuditEventRepository;
  @Mock private HandshakeSessionRepository handshakeSessionRepository;
  @Mock private SupportRoomProvisioningLedger provisioning;
  @Mock private SupportRoomCreationIntent intent;
  @Mock private AdminRepository adminRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private MatrixConfig matrixConfig;

  @BeforeEach
  void setUp() {
    supportRoomService =
        new SupportRoomService(
            supportRoomRepository,
            new SupportRoomLifecycle(
                supportRoomRepository,
                handshakeAuditEventRepository,
                handshakeSessionRepository,
                Clock.systemUTC()),
            provisioning,
            adminRepository,
            consultantRepository,
            matrixSynapseService,
            matrixConfig);
    ReflectionTestUtils.setField(supportRoomService, "roomTtlHours", 4L);
    ReflectionTestUtils.setField(supportRoomService, "sweepBatchSize", 200);
    lenient().when(matrixConfig.getServerName()).thenReturn("caritas.local");
    lenient().when(intent.getId()).thenReturn(INTENT_ID);
    lenient().when(intent.getRoomAlias()).thenReturn(LOCAL_ALIAS);
    lenient().when(intent.getNonce()).thenReturn(NONCE);
    lenient().when(intent.getSupportAdminMatrixId()).thenReturn(GSA_MXID);
    lenient().when(intent.getConsultantMatrixId()).thenReturn(CONSULTANT_MXID);
    lenient().when(intent.getProvisioningDeadline()).thenReturn(nowInUtc().plusMinutes(3));
    lenient().when(intent.getExpiresAt()).thenReturn(nowInUtc().plusMinutes(5));
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

  private void stubAuthorizedReservation(boolean firstAttempt) {
    when(adminRepository.findById(GSA_ID)).thenReturn(Optional.of(gsa()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(consultantWithMatrix()));
    when(matrixSynapseService.userExists("sam.support")).thenReturn(true);
    when(matrixSynapseService.loginAsUserAccessToken(GSA_MXID)).thenReturn("gsa-token");
    when(provisioning.reserve(
            eq("hs-1"),
            eq(GSA_ID),
            eq(GSA_MXID),
            eq(CONSULTANT_ID),
            eq(CONSULTANT_MXID),
            eq(1L),
            any(LocalDateTime.class)))
        .thenReturn(new SupportRoomProvisioningLedger.Reservation(intent, firstAttempt));
  }

  private void stubVerifiedRoom() {
    when(matrixSynapseService.verifySupportRoomOrigin(ROOM_ID, GSA_MXID, NONCE, "gsa-token"))
        .thenReturn(true);
    when(provisioning.bindForConfirmation(intent, ROOM_ID)).thenReturn(true);
    when(matrixSynapseService.isRoomEncrypted(ROOM_ID)).thenReturn(Optional.of(true));
    when(matrixSynapseService.ensureSupportRoomModeration(ROOM_ID, "gsa-token")).thenReturn(true);
  }

  private void stubProvisionalRoom() {
    var room = activeRoom();
    room.setId(INTENT_ID);
    room.setStatus(SupportRoom.SupportRoomStatus.PROVISIONING);
    when(supportRoomRepository.lockById(INTENT_ID)).thenReturn(Optional.of(room));
  }

  @Test
  void createsEncryptedRoomAfterReservationAndBindsItBeforeInvitation() throws Exception {
    stubAuthorizedReservation(true);
    when(matrixSynapseService.createSupportRoom(LOCAL_ALIAS, NONCE, "gsa-token"))
        .thenReturn(ROOM_ID);
    stubVerifiedRoom();
    when(matrixSynapseService.getRoomMembers(ROOM_ID))
        .thenReturn(
            Optional.of(List.of(GSA_MXID)), Optional.of(List.of(GSA_MXID, CONSULTANT_MXID)));
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MXID))
        .thenReturn("consultant-token");
    when(matrixSynapseService.joinRoom(ROOM_ID, "consultant-token")).thenReturn(true);
    stubProvisionalRoom();

    var beforeActivation = nowInUtc();
    var room = supportRoomService.createForConfirmedHandshake(confirmedSession());
    var afterActivation = nowInUtc();

    var ordered = inOrder(provisioning, matrixSynapseService, supportRoomRepository);
    ordered
        .verify(provisioning)
        .reserve(
            eq("hs-1"),
            eq(GSA_ID),
            eq(GSA_MXID),
            eq(CONSULTANT_ID),
            eq(CONSULTANT_MXID),
            eq(1L),
            any(LocalDateTime.class));
    ordered.verify(matrixSynapseService).createSupportRoom(LOCAL_ALIAS, NONCE, "gsa-token");
    ordered
        .verify(matrixSynapseService)
        .verifySupportRoomOrigin(ROOM_ID, GSA_MXID, NONCE, "gsa-token");
    ordered.verify(provisioning).bindForConfirmation(intent, ROOM_ID);
    ordered.verify(matrixSynapseService).ensureSupportRoomModeration(ROOM_ID, "gsa-token");
    ordered.verify(matrixSynapseService).inviteUserToRoom(ROOM_ID, CONSULTANT_MXID, "gsa-token");
    ordered.verify(matrixSynapseService).joinRoom(ROOM_ID, "consultant-token");
    ordered.verify(supportRoomRepository).lockById(INTENT_ID);
    ordered.verify(provisioning).forgetConfirmed("hs-1");
    assertThat(room.getMatrixRoomId()).isEqualTo(ROOM_ID);
    assertThat(room.getStatus()).isEqualTo(SupportRoom.SupportRoomStatus.ACTIVE);
    assertThat(room.getExpiryDate())
        .isBetween(beforeActivation.plusHours(4), afterActivation.plusHours(4));
    verify(handshakeAuditEventRepository).save(any());
  }

  @Test
  void recoversAValidRoomAfterProviderResponseLoss() throws Exception {
    stubAuthorizedReservation(true);
    when(matrixSynapseService.createSupportRoom(LOCAL_ALIAS, NONCE, "gsa-token"))
        .thenThrow(new IllegalStateException("synthetic lost acknowledgement"));
    when(matrixSynapseService.resolveRoomAlias(FULL_ALIAS))
        .thenReturn(new MatrixSynapseService.RoomAliasLookup(true, ROOM_ID));
    stubVerifiedRoom();
    when(matrixSynapseService.getRoomMembers(ROOM_ID))
        .thenReturn(
            Optional.of(List.of(GSA_MXID)), Optional.of(List.of(GSA_MXID, CONSULTANT_MXID)));
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MXID))
        .thenReturn("consultant-token");
    when(matrixSynapseService.joinRoom(ROOM_ID, "consultant-token")).thenReturn(true);
    stubProvisionalRoom();

    assertThat(supportRoomService.createForConfirmedHandshake(confirmedSession()).getMatrixRoomId())
        .isEqualTo(ROOM_ID);
    verify(matrixSynapseService).createSupportRoom(LOCAL_ALIAS, NONCE, "gsa-token");
    var ordered = inOrder(provisioning, matrixSynapseService);
    ordered.verify(provisioning).bindForConfirmation(intent, ROOM_ID);
    ordered.verify(matrixSynapseService).inviteUserToRoom(ROOM_ID, CONSULTANT_MXID, "gsa-token");
    ordered.verify(matrixSynapseService).joinRoom(ROOM_ID, "consultant-token");
  }

  @Test
  void resumesVerifiedReservationWithoutRepeatingCreationOrAdmission() throws Exception {
    stubAuthorizedReservation(false);
    when(matrixSynapseService.resolveRoomAlias(FULL_ALIAS))
        .thenReturn(new MatrixSynapseService.RoomAliasLookup(true, ROOM_ID));
    stubVerifiedRoom();
    when(matrixSynapseService.getRoomMembers(ROOM_ID))
        .thenReturn(Optional.of(List.of(GSA_MXID, CONSULTANT_MXID)));
    stubProvisionalRoom();

    assertThat(supportRoomService.createForConfirmedHandshake(confirmedSession()).getStatus())
        .isEqualTo(SupportRoom.SupportRoomStatus.ACTIVE);
    verify(matrixSynapseService, never()).createSupportRoom(any(), any(), any());
    verify(matrixSynapseService, never()).inviteUserToRoom(any(), any(), any());
  }

  @Test
  void anUnconfirmedAliasLeavesTheIntentPendingWithoutOtherProviderWrites() throws Exception {
    stubAuthorizedReservation(false);
    when(matrixSynapseService.resolveRoomAlias(FULL_ALIAS))
        .thenReturn(new MatrixSynapseService.RoomAliasLookup(true, ROOM_ID));

    assertThatThrownBy(() -> supportRoomService.createForConfirmedHandshake(confirmedSession()))
        .isInstanceOf(ServiceUnavailableException.class);
    verify(provisioning, never()).bindForConfirmation(any(), any());
    verify(provisioning, never()).forgetConfirmed(any());
    verify(matrixSynapseService, never()).createSupportRoom(any(), any(), any());
    verify(matrixSynapseService, never()).inviteUserToRoom(any(), any(), any());
  }

  @Test
  void aMissingAliasAfterAnUncertainAttemptDoesNotRestartCreation() throws Exception {
    stubAuthorizedReservation(false);
    when(matrixSynapseService.resolveRoomAlias(FULL_ALIAS))
        .thenReturn(new MatrixSynapseService.RoomAliasLookup(true, null));

    assertThatThrownBy(() -> supportRoomService.createForConfirmedHandshake(confirmedSession()))
        .isInstanceOf(ServiceUnavailableException.class);
    verify(provisioning, never()).bindForConfirmation(any(), any());
    verify(provisioning, never()).forgetConfirmed(any());
    verify(matrixSynapseService, never()).createSupportRoom(any(), any(), any());
  }

  @Test
  void creationDoesNotReportActiveWhenMatrixDoesNotConfirmTheJoin() throws Exception {
    stubAuthorizedReservation(true);
    when(matrixSynapseService.createSupportRoom(LOCAL_ALIAS, NONCE, "gsa-token"))
        .thenReturn(ROOM_ID);
    stubVerifiedRoom();
    when(matrixSynapseService.getRoomMembers(ROOM_ID)).thenReturn(Optional.of(List.of(GSA_MXID)));
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MXID))
        .thenReturn("consultant-token");

    assertThatThrownBy(() -> supportRoomService.createForConfirmedHandshake(confirmedSession()))
        .isInstanceOf(IllegalStateException.class);
    verify(provisioning).bindForConfirmation(intent, ROOM_ID);
    verify(provisioning, never()).forgetConfirmed(any());
    verify(supportRoomRepository, never()).lockById(anyString());
    verify(handshakeAuditEventRepository, never()).save(any());
  }

  @Test
  void aRoomDiscoveredAfterGraceIsNotAdmittedOrActivated() throws Exception {
    stubAuthorizedReservation(false);
    when(matrixSynapseService.resolveRoomAlias(FULL_ALIAS))
        .thenReturn(new MatrixSynapseService.RoomAliasLookup(true, ROOM_ID));
    when(matrixSynapseService.verifySupportRoomOrigin(ROOM_ID, GSA_MXID, NONCE, "gsa-token"))
        .thenReturn(true);

    assertThatThrownBy(() -> supportRoomService.createForConfirmedHandshake(confirmedSession()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Support room provisioning grace has expired");

    verify(provisioning).bindForConfirmation(intent, ROOM_ID);
    verify(provisioning, never()).forgetConfirmed(any());
    verify(matrixSynapseService, never()).inviteUserToRoom(any(), any(), any());
    verifyNoInteractions(supportRoomRepository, handshakeAuditEventRepository);
  }

  @Test
  void graceExpiryDuringVerificationPreventsConsultantAdmission() throws Exception {
    stubAuthorizedReservation(true);
    when(matrixSynapseService.createSupportRoom(LOCAL_ALIAS, NONCE, "gsa-token"))
        .thenReturn(ROOM_ID);
    stubVerifiedRoom();
    var deadline = nowInUtc().minusSeconds(1);
    when(intent.getProvisioningDeadline()).thenReturn(deadline);
    when(matrixSynapseService.getRoomMembers(ROOM_ID)).thenReturn(Optional.of(List.of(GSA_MXID)));

    assertThatThrownBy(() -> supportRoomService.createForConfirmedHandshake(confirmedSession()))
        .hasMessage("Support room provisioning grace has expired");

    verify(provisioning).bindForConfirmation(intent, ROOM_ID);
    verify(matrixSynapseService, never()).inviteUserToRoom(any(), any(), any());
    verify(provisioning, never()).forgetConfirmed(any());
  }

  @Test
  void closureClaimRechecksDueTimeAndStartsTheLeaseAfterLocking() {
    var beforeLock = nowInUtc();
    var afterLock = beforeLock.plusSeconds(25);
    var room = activeRoom();
    room.setStatus(SupportRoom.SupportRoomStatus.PROVISIONING);
    room.setExpiryDate(beforeLock.plusSeconds(10));
    var clock = mock(Clock.class);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(supportRoomRepository.findHandshakeIdByRoomIdAndStatus(
            room.getId(), SupportRoom.SupportRoomStatus.PROVISIONING))
        .thenReturn(Optional.of(room.getHandshakeId()));
    when(supportRoomRepository.lockById(room.getId()))
        .thenAnswer(
            invocation -> {
              when(clock.instant()).thenReturn(afterLock.toInstant(ZoneOffset.UTC));
              return Optional.of(room);
            });
    var lifecycle =
        new SupportRoomLifecycle(
            supportRoomRepository,
            handshakeAuditEventRepository,
            handshakeSessionRepository,
            clock);

    assertThat(lifecycle.claimClosure(room.getId())).isPresent();

    verify(handshakeSessionRepository).lockForSupportCleanup(room.getHandshakeId());
    assertThat(room.getRetryAt()).isEqualTo(afterLock.plusMinutes(1));
  }

  @Test
  void expiredRoomRecoveryOnlyTransfersVerifiedOwnershipToTheWithdrawalSweep() throws Exception {
    when(provisioning.expiredIds(any(), eq(200))).thenReturn(List.of("hs-1"));
    when(provisioning.claimExpired("hs-1")).thenReturn(Optional.of(intent));
    when(matrixSynapseService.resolveRoomAlias(FULL_ALIAS))
        .thenReturn(new MatrixSynapseService.RoomAliasLookup(true, ROOM_ID));
    when(matrixSynapseService.loginAsUserAccessToken(GSA_MXID)).thenReturn("gsa-token");
    when(matrixSynapseService.verifySupportRoomOrigin(ROOM_ID, GSA_MXID, NONCE, "gsa-token"))
        .thenReturn(true);

    supportRoomService.sweepExpired();

    verify(provisioning).bindExpiredForCleanup(intent, ROOM_ID);
    verify(provisioning, never()).deferUnresolvedRecovery(any());
    verify(provisioning, never()).forgetConfirmed(any());
    verify(matrixSynapseService, never()).createSupportRoom(any(), any(), any());
    verify(matrixSynapseService, never()).inviteUserToRoom(any(), any(), any());
  }

  @Test
  void expiredRoomRecoveryDefersAnUnreachableProviderWithoutAttemptingCreation() throws Exception {
    when(provisioning.expiredIds(any(), eq(200))).thenReturn(List.of("hs-1"));
    when(provisioning.claimExpired("hs-1")).thenReturn(Optional.of(intent));
    when(matrixSynapseService.resolveRoomAlias(FULL_ALIAS))
        .thenThrow(new IllegalStateException("synthetic provider failure"));

    supportRoomService.sweepExpired();

    verify(provisioning).deferUnresolvedRecovery(intent);
    verify(provisioning, never()).bindExpiredForCleanup(any(), any());
    verify(matrixSynapseService, never()).createSupportRoom(any(), any(), any());
  }

  @Test
  void creationRequiresAConsultantMatrixIdentity() {
    when(adminRepository.findById(GSA_ID)).thenReturn(Optional.of(gsa()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(new Consultant()));

    assertThatThrownBy(() -> supportRoomService.createForConfirmedHandshake(confirmedSession()))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(provisioning, matrixSynapseService);
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
  void terminationHidesTheLeaseAndAConfirmedSweepClosesIt() {
    var room = activeRoom();
    var consultant = new AuthenticatedUser();
    consultant.setUserId(CONSULTANT_ID);
    when(supportRoomRepository.lockById(room.getId())).thenReturn(Optional.of(room));

    supportRoomService.terminate(consultant, room.getId());

    assertThat(room.getStatus()).isEqualTo(SupportRoom.SupportRoomStatus.REVOCATION_PENDING);
    assertThat(room.getCloseReason()).isEqualTo("TERMINATED");
    assertThat(room.getClosedDate()).isNull();
    verifyNoInteractions(matrixSynapseService, handshakeAuditEventRepository);

    stubDue(room);
    when(matrixSynapseService.loginAsUserAccessToken(GSA_MXID)).thenReturn("gsa-token");
    when(matrixSynapseService.leaveRoom(ROOM_ID, "gsa-token")).thenReturn(true);
    when(matrixSynapseService.isOutOfRoom(ROOM_ID, GSA_MXID)).thenReturn(Optional.of(true));

    supportRoomService.sweepExpired();

    assertThat(room.getStatus()).isEqualTo(SupportRoom.SupportRoomStatus.CLOSED);
    verify(matrixSynapseService).leaveRoom(ROOM_ID, "gsa-token");
    verify(handshakeAuditEventRepository)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                event -> CONSULTANT_ID.equals(event.getActorId())));
  }

  @Test
  void terminate_Should_Forbid_When_CallerIsNotTheRoomsConsultant() {
    when(supportRoomRepository.lockById("sr-1")).thenReturn(Optional.of(activeRoom()));
    var stranger = new AuthenticatedUser();
    stranger.setUserId("someone-else");

    assertThatThrownBy(() -> supportRoomService.terminate(stranger, "sr-1"))
        .isInstanceOf(ForbiddenException.class);

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void expiryPersistsAnUnconfirmedWithdrawalAndRetriesIt() {
    var room = activeRoom();
    room.setExpiryDate(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    stubDue(room);
    when(matrixSynapseService.loginAsUserAccessToken(GSA_MXID)).thenReturn("gsa-token");
    when(matrixSynapseService.leaveRoom(ROOM_ID, "gsa-token")).thenReturn(false, true);
    when(matrixSynapseService.isOutOfRoom(ROOM_ID, GSA_MXID))
        .thenReturn(Optional.empty(), Optional.of(true));

    supportRoomService.sweepExpired();

    assertThat(room.getStatus()).isEqualTo(SupportRoom.SupportRoomStatus.REVOCATION_PENDING);
    assertThat(room.getCloseReason()).isEqualTo("EXPIRED");
    assertThat(room.getClosedDate()).isNull();
    assertThat(room.getRetryAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
    verifyNoInteractions(handshakeAuditEventRepository);

    room.setRetryAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    supportRoomService.sweepExpired();
    supportRoomService.sweepExpired();

    assertThat(room.getStatus()).isEqualTo(SupportRoom.SupportRoomStatus.CLOSED);
    assertThat(room.getClosedDate()).isNotNull();
    verify(matrixSynapseService, times(2)).leaveRoom(ROOM_ID, "gsa-token");
    verify(handshakeAuditEventRepository).save(any());
  }

  @Test
  void sweepExpiredKeepsCleanupPendingWhenMatrixFails() {
    var room = activeRoom();
    room.setExpiryDate(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    stubDue(room);
    when(matrixSynapseService.loginAsUserAccessToken(GSA_MXID))
        .thenThrow(new RuntimeException("unavailable"));

    supportRoomService.sweepExpired();

    assertThat(room.getStatus()).isEqualTo(SupportRoom.SupportRoomStatus.REVOCATION_PENDING);
    assertThat(room.getClosedDate()).isNull();
    verifyNoInteractions(handshakeAuditEventRepository);
  }

  @Test
  void cleanupSweepContinuesAfterFailuresToClaimIndividualRecords() {
    var room = activeRoom();
    room.setExpiryDate(nowInUtc().minusMinutes(1));
    when(supportRoomRepository.findClosureDue(any(), any(), any(), any()))
        .thenReturn(List.of("locked-room", room.getId()));
    when(supportRoomRepository.lockById("locked-room"))
        .thenThrow(
            new org.springframework.dao.CannotAcquireLockException("synthetic lock timeout"));
    lenient().when(supportRoomRepository.lockById(room.getId())).thenReturn(Optional.of(room));
    lenient()
        .when(provisioning.expiredIds(any(), eq(200)))
        .thenReturn(List.of("locked-intent", "due-intent"));
    lenient()
        .when(provisioning.claimExpired("locked-intent"))
        .thenThrow(
            new org.springframework.dao.CannotAcquireLockException("synthetic lock timeout"));
    lenient().when(provisioning.claimExpired("due-intent")).thenReturn(Optional.of(intent));

    supportRoomService.sweepExpired();

    assertThat(room.getStatus()).isEqualTo(SupportRoom.SupportRoomStatus.REVOCATION_PENDING);
    verify(provisioning).deferUnresolvedRecovery(intent);
  }

  private void stubDue(SupportRoom room) {
    when(supportRoomRepository.findClosureDue(
            eq(
                List.of(
                    SupportRoom.SupportRoomStatus.ACTIVE,
                    SupportRoom.SupportRoomStatus.PROVISIONING)),
            eq(SupportRoom.SupportRoomStatus.REVOCATION_PENDING),
            any(LocalDateTime.class),
            any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(List.of(room.getId()));
    when(supportRoomRepository.lockById(room.getId())).thenReturn(Optional.of(room));
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

  @Test
  void activeForDoesNotReturnAnExpiredLeaseBeforeCleanupRuns() {
    var user = new AuthenticatedUser();
    user.setUserId(CONSULTANT_ID);
    var expired = activeRoom();
    expired.setExpiryDate(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
    when(supportRoomRepository.findAllByStatusAndConsultantIdOrStatusAndSupportAdminId(
            SupportRoom.SupportRoomStatus.ACTIVE,
            CONSULTANT_ID,
            SupportRoom.SupportRoomStatus.ACTIVE,
            CONSULTANT_ID))
        .thenReturn(List.of(expired));

    assertThat(supportRoomService.activeFor(user)).isEmpty();
  }
}
