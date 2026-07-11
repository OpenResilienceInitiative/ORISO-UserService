package de.caritas.cob.userservice.api.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.supervision.SupervisionConsent;
import de.caritas.cob.userservice.api.supervision.SupervisionNotes;
import de.caritas.cob.userservice.api.supervision.SupervisionReason;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.test.util.ReflectionTestUtils;

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
  @Mock private de.caritas.cob.userservice.api.helper.AuthenticatedUser authenticatedUser;

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
    SessionSupervisor saved =
        facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason");

    assertThat(saved.getMatrixRoomId())
        .as("entity must hold the supervision SIDE room, never the client room")
        .isEqualTo(SIDE_ROOM)
        .isNotEqualTo(CLIENT_ROOM);
  }

  @Test
  void addSupervisor_Should_provisionSideRoom_andNeverInviteIntoItAsClientRoom() throws Exception {
    facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason");

    // A side room was created...
    verify(matrixSynapseService).createRoom(any(), any(), any());
    // ...and the supervisor was invited to BOTH the client room (observation) and the side room.
    ArgumentCaptor<String> rooms = ArgumentCaptor.forClass(String.class);
    verify(matrixSynapseService, org.mockito.Mockito.atLeast(2))
        .inviteUserToRoom(rooms.capture(), eq(SUPERVISOR_MXID), any());
    assertThat(rooms.getAllValues()).contains(CLIENT_ROOM, SIDE_ROOM);
  }

  @Test
  void addSupervisor_Should_continue_when_supervisorAlreadyInClientRoom() throws Exception {
    when(matrixSynapseService.inviteUserToRoom(eq(CLIENT_ROOM), eq(SUPERVISOR_MXID), any()))
        .thenThrow(
            new MatrixInviteUserException(
                "Could not invite user (@sup:oriso) to room (!clientroom:oriso) in Matrix: "
                    + "{\"errcode\":\"M_FORBIDDEN\",\"error\":\"@sup:oriso is already in the room.\"}"));

    SessionSupervisor saved =
        facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason");

    assertThat(saved.getMatrixRoomId()).isEqualTo(SIDE_ROOM);
    verify(matrixSynapseService).setUserPowerLevel(CLIENT_ROOM, SUPERVISOR_MXID, 10, "tok");
    verify(sessionSupervisorRepository).save(any(SessionSupervisor.class));
  }

  @Test
  void addSupervisor_Should_reuseExistingSideRoom_when_anotherSupervisorAlreadyHasOne()
      throws Exception {
    SessionSupervisor existing = SessionSupervisor.builder().matrixRoomId(SIDE_ROOM).build();
    when(sessionSupervisorRepository.findBySessionIdAndIsActiveTrue(SESSION_ID))
        .thenReturn(List.of(existing));

    SessionSupervisor saved =
        facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason");

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

    SessionSupervisor saved =
        facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason");

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

    facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason");

    ArgumentCaptor<String> invitedUsers = ArgumentCaptor.forClass(String.class);
    verify(matrixSynapseService, org.mockito.Mockito.atLeastOnce())
        .inviteUserToRoom(any(), invitedUsers.capture(), any());
    assertThat(invitedUsers.getAllValues())
        .as("only the supervisor is ever invited; the client is never invited into any room")
        .containsOnly(SUPERVISOR_MXID)
        .doesNotContain(CLIENT_MXID);
    verify(matrixSynapseService, never()).inviteUserToRoom(eq(SIDE_ROOM), eq(CLIENT_MXID), any());
  }

  // ---------------------------------------------------------------------------
  // ADR-008 item 4 (DRAFT): reason + justification + consent + authority flag
  // ---------------------------------------------------------------------------

  @Test
  void addSupervisor_Should_throwBadRequest_when_reasonCodeIsPresentButInvalid() {
    assertThatThrownBy(
            () ->
                facade.addSupervisor(
                    SESSION_ID, SUPERVISOR_ID, addedBy, "NOT_A_REAL_REASON", "justified"))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("valid supervision reason");
  }

  @Test
  void addSupervisor_Should_throwBadRequest_when_reasonSuppliedButJustificationBlank() {
    assertThatThrownBy(
            () -> facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, "PEER_SUPPORT", "  "))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("justification");
  }

  @Test
  void addSupervisor_Should_notThrow_when_reasonCodeAbsent_legacyPath() {
    // Legacy path: the running FE sends no reasonCode. Must NOT 400, even with blank justification.
    SessionSupervisor saved = facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "");

    assertThat(saved).isNotNull();
    // Stored uniformly as JSON with a null reason and NOT_REQUIRED consent.
    assertThat(saved.getNotes()).contains(SupervisionConsent.NOT_REQUIRED.name());
  }

  @Test
  void addSupervisor_Should_computePendingConsent_for_safeguardingU25() {
    SessionSupervisor saved =
        facade.addSupervisor(
            SESSION_ID, SUPERVISOR_ID, addedBy, "SAFEGUARDING_U25", "minor at risk");

    assertThat(saved.getNotes())
        .contains(SupervisionConsent.PENDING.name())
        .contains("SAFEGUARDING_U25")
        .contains("minor at risk");
  }

  @Test
  void addSupervisor_Should_computePendingConsent_for_clinicalOversight() {
    SessionSupervisor saved =
        facade.addSupervisor(
            SESSION_ID, SUPERVISOR_ID, addedBy, "CLINICAL_OVERSIGHT", "stuck case");

    assertThat(saved.getNotes()).contains(SupervisionConsent.PENDING.name());
  }

  @Test
  void addSupervisor_Should_computeNotRequiredConsent_for_peerSupport() {
    SessionSupervisor saved =
        facade.addSupervisor(
            SESSION_ID, SUPERVISOR_ID, addedBy, "PEER_SUPPORT", "shared experience");

    assertThat(saved.getNotes()).contains(SupervisionConsent.NOT_REQUIRED.name());
  }

  @Test
  void addSupervisor_Should_computeNotRequiredConsent_for_training() {
    SessionSupervisor saved =
        facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, "TRAINING", "onboarding");

    assertThat(saved.getNotes()).contains(SupervisionConsent.NOT_REQUIRED.name());
  }

  @Test
  void addSupervisor_Should_storeReasonAndJustificationAsJson() {
    SessionSupervisor saved =
        facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, "PEER_SUPPORT", "why we did it");

    assertThat(saved.getNotes()).startsWith("{").contains("PEER_SUPPORT").contains("why we did it");
  }

  @Test
  void addSupervisor_Should_rejectSameAgencyNonAssignedConsultant_when_restrictionEnabled() {
    ReflectionTestUtils.setField(facade, "restrictAddToAssignedConsultant", true);
    Consultant otherSameAgency = sameAgencyButNotAssignedConsultant();

    assertThatThrownBy(
            () ->
                facade.addSupervisor(
                    SESSION_ID, SUPERVISOR_ID, otherSameAgency, "PEER_SUPPORT", "justified"))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void addSupervisor_Should_allowSameAgencyNonAssignedConsultant_when_restrictionDisabled() {
    ReflectionTestUtils.setField(facade, "restrictAddToAssignedConsultant", false);
    Consultant otherSameAgency = sameAgencyButNotAssignedConsultant();

    SessionSupervisor saved =
        facade.addSupervisor(
            SESSION_ID, SUPERVISOR_ID, otherSameAgency, "PEER_SUPPORT", "justified");

    assertThat(saved).isNotNull();
  }

  @Test
  void addSupervisor_Should_allowAgencyAdmin_evenWhen_restrictionEnabled() {
    // Frank 2026-07-04: a Berater-Admin (agency admin) may manage supervisors for their own
    // agency's
    // sessions, even with the assigned-only tightening on.
    ReflectionTestUtils.setField(facade, "restrictAddToAssignedConsultant", true);
    when(authenticatedUser.isRestrictedAgencyAdmin()).thenReturn(true);
    Consultant agencyAdmin = sameAgencyButNotAssignedConsultant();

    SessionSupervisor saved =
        facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, agencyAdmin, "PEER_SUPPORT", "justified");

    assertThat(saved).isNotNull();
  }

  @Test
  void addSupervisor_Should_denyAgencyAdmin_forDifferentAgency_when_restrictionEnabled() {
    // Agency-admin authority is scoped to the admin's OWN agency — a session in another agency is
    // still denied (no cross-agency supervisor management).
    ReflectionTestUtils.setField(facade, "restrictAddToAssignedConsultant", true);
    when(authenticatedUser.isAgencySuperAdmin()).thenReturn(true);
    Consultant adminOfOtherAgency = new Consultant();
    adminOfOtherAgency.setId("con-3");
    adminOfOtherAgency.setMatrixUserId("@con3:oriso");
    adminOfOtherAgency.setConsultantAgencies(
        Set.of(ConsultantAgency.builder().agencyId(9L).build()));

    assertThatThrownBy(
            () ->
                facade.addSupervisor(
                    SESSION_ID, SUPERVISOR_ID, adminOfOtherAgency, "PEER_SUPPORT", "justified"))
        .isInstanceOf(ForbiddenException.class);
  }

  // ---------------------------------------------------------------------------
  // ADR-008 item 4: consent GATE — no room access until the client approves
  // ---------------------------------------------------------------------------

  @Test
  void addSupervisor_Should_notProvisionAnyRoom_when_consentRequiredAndPending() throws Exception {
    SessionSupervisor saved =
        facade.addSupervisor(
            SESSION_ID, SUPERVISOR_ID, addedBy, "SAFEGUARDING_U25", "minor at risk");

    // The consent gate: for a consent-required reason with no consent yet, the supervisor gets
    // NO Matrix access at all — no side room, no client-room invite — until the client approves.
    verify(matrixSynapseService, never()).createRoom(any(), any(), any());
    verify(matrixSynapseService, never()).inviteUserToRoom(any(), any(), any());
    assertThat(saved.getIsActive()).isFalse();
    assertThat(saved.getMatrixRoomId()).isNull();
    assertThat(saved.getNotes()).contains(SupervisionConsent.PENDING.name());
  }

  @Test
  void decideSupervisionConsent_Should_provisionAndActivate_when_approved() throws Exception {
    SessionSupervisor pending = pendingSupervisor(99L);
    when(sessionSupervisorRepository.findById(99L)).thenReturn(Optional.of(pending));

    SessionSupervisor result = facade.decideSupervisionConsent(SESSION_ID, 99L, true);

    // Approval runs the deferred provisioning: side room created + supervisor invited to the
    // client room, and the row flips to active with APPROVED consent.
    verify(matrixSynapseService).createRoom(any(), any(), any());
    verify(matrixSynapseService).inviteUserToRoom(eq(CLIENT_ROOM), eq(SUPERVISOR_MXID), any());
    assertThat(result.getIsActive()).isTrue();
    assertThat(result.getMatrixRoomId()).isEqualTo(SIDE_ROOM);
    assertThat(result.getNotes()).contains(SupervisionConsent.APPROVED.name());
  }

  @Test
  void decideSupervisionConsent_Should_recordDeclined_andNeverProvision_when_declined()
      throws Exception {
    SessionSupervisor pending = pendingSupervisor(98L);
    when(sessionSupervisorRepository.findById(98L)).thenReturn(Optional.of(pending));

    SessionSupervisor result = facade.decideSupervisionConsent(SESSION_ID, 98L, false);

    verify(matrixSynapseService, never()).createRoom(any(), any(), any());
    verify(matrixSynapseService, never()).inviteUserToRoom(any(), any(), any());
    assertThat(result.getIsActive()).isFalse();
    assertThat(result.getNotes()).contains(SupervisionConsent.DECLINED.name());
  }

  @Test
  void decideSupervisionConsent_Should_throwBadRequest_when_noPendingRequest() {
    SessionSupervisor active =
        SessionSupervisor.builder()
            .id(97L)
            .session(session)
            .supervisorConsultant(supervisor)
            .addedByConsultant(addedBy)
            .isActive(true)
            .matrixRoomId(SIDE_ROOM)
            .notes(
                SupervisionNotes.encode(
                    SupervisionReason.PEER_SUPPORT, "x", SupervisionConsent.NOT_REQUIRED))
            .build();
    when(sessionSupervisorRepository.findById(97L)).thenReturn(Optional.of(active));

    assertThatThrownBy(() -> facade.decideSupervisionConsent(SESSION_ID, 97L, true))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void addSupervisor_Should_throwBadRequest_when_aPendingRequestAlreadyExists() {
    when(sessionSupervisorRepository.findBySessionId(SESSION_ID))
        .thenReturn(List.of(pendingSupervisor(1L)));

    assertThatThrownBy(
            () ->
                facade.addSupervisor(
                    SESSION_ID, SUPERVISOR_ID, addedBy, "SAFEGUARDING_U25", "again"))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("already pending");
  }

  /** A PENDING-consent (inactive, no room) supervisor row for the mocked session/supervisor. */
  private SessionSupervisor pendingSupervisor(Long id) {
    return SessionSupervisor.builder()
        .id(id)
        .session(session)
        .supervisorConsultant(supervisor)
        .addedByConsultant(addedBy)
        .isActive(false)
        .matrixRoomId(null)
        .notes(
            SupervisionNotes.encode(
                SupervisionReason.SAFEGUARDING_U25, "minor at risk", SupervisionConsent.PENDING))
        .build();
  }

  /**
   * A consultant in the SAME agency (7L) as the session but who is NOT the assigned consultant.
   * Used to exercise the ADR-008 authority flag: allowed when off, denied when on.
   */
  private Consultant sameAgencyButNotAssignedConsultant() {
    Consultant other = new Consultant();
    other.setId("con-2");
    other.setMatrixUserId("@con2:oriso");
    other.setConsultantAgencies(Set.of(ConsultantAgency.builder().agencyId(7L).build()));
    return other;
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-10
  // ---------------------------------------------------------------------------

  private SessionSupervisor activeSupervisorRow(Long id) {
    return SessionSupervisor.builder()
        .id(id)
        .session(session)
        .supervisorConsultant(supervisor)
        .addedByConsultant(addedBy)
        .isActive(true)
        .matrixRoomId(SIDE_ROOM)
        .notes(
            SupervisionNotes.encode(
                SupervisionReason.PEER_SUPPORT, "x", SupervisionConsent.NOT_REQUIRED))
        .build();
  }

  // --- removeSupervisor ---

  @Test
  void removeSupervisor_Should_throwNotFound_When_sessionNotFound() {
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> facade.removeSupervisor(SESSION_ID, 1L, addedBy))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException.class);
  }

  @Test
  void removeSupervisor_Should_throwForbidden_When_noPermission() {
    Consultant stranger = new Consultant();
    stranger.setId("con-stranger");
    stranger.setConsultantAgencies(Set.of(ConsultantAgency.builder().agencyId(999L).build()));

    assertThatThrownBy(() -> facade.removeSupervisor(SESSION_ID, 1L, stranger))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void removeSupervisor_Should_throwNotFound_When_supervisorNotFound() {
    when(sessionSupervisorRepository.findById(1L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> facade.removeSupervisor(SESSION_ID, 1L, addedBy))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException.class);
  }

  @Test
  void removeSupervisor_Should_throwBadRequest_When_supervisorBelongsToDifferentSession() {
    Session otherSession = new Session();
    otherSession.setId(999L);
    SessionSupervisor mismatched =
        SessionSupervisor.builder()
            .id(1L)
            .session(otherSession)
            .supervisorConsultant(supervisor)
            .isActive(true)
            .build();
    when(sessionSupervisorRepository.findById(1L)).thenReturn(Optional.of(mismatched));

    assertThatThrownBy(() -> facade.removeSupervisor(SESSION_ID, 1L, addedBy))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void removeSupervisor_Should_throwBadRequest_When_alreadyRemoved() {
    SessionSupervisor inactive = activeSupervisorRow(1L);
    inactive.setIsActive(false);
    when(sessionSupervisorRepository.findById(1L)).thenReturn(Optional.of(inactive));

    assertThatThrownBy(() -> facade.removeSupervisor(SESSION_ID, 1L, addedBy))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("already removed");
  }

  @Test
  void removeSupervisor_Should_removeFromBothRoomsAndDeactivate_When_happyPath() {
    SessionSupervisor active = activeSupervisorRow(1L);
    when(sessionSupervisorRepository.findById(1L)).thenReturn(Optional.of(active));
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MXID)).thenReturn("tok");
    when(matrixSynapseService.removeUserFromRoom(any(), any(), any())).thenReturn(true);

    facade.removeSupervisor(SESSION_ID, 1L, addedBy);

    verify(matrixSynapseService).removeUserFromRoom(SIDE_ROOM, SUPERVISOR_MXID, "tok");
    verify(matrixSynapseService).removeUserFromRoom(CLIENT_ROOM, SUPERVISOR_MXID, "tok");
    assertThat(active.getIsActive()).isFalse();
    assertThat(active.getRemovedDate()).isNotNull();
    verify(sessionSupervisorRepository).save(active);
  }

  @Test
  void removeSupervisor_Should_logWarn_When_removeFromRoomReturnsFalse() {
    // ACCEPTED BEHAVIOUR: the supervisor row is always deactivated in the DB even when
    // the Matrix room-removal call returns false (e.g. the user was already absent from
    // the room). Matrix cleanup is best-effort; the authoritative access-control record
    // is the DB row. An operator can re-run Matrix cleanup manually if required.
    SessionSupervisor active = activeSupervisorRow(1L);
    when(sessionSupervisorRepository.findById(1L)).thenReturn(Optional.of(active));
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MXID)).thenReturn("tok");
    when(matrixSynapseService.removeUserFromRoom(any(), any(), any())).thenReturn(false);

    facade.removeSupervisor(SESSION_ID, 1L, addedBy);

    assertThat(active.getIsActive()).isFalse();
    assertThat(active.getRemovedDate()).isNotNull();
    verify(sessionSupervisorRepository).save(active);
  }

  @Test
  void removeSupervisor_Should_skipRoomRemoval_When_supervisorMatrixUserIdBlank() {
    supervisor.setMatrixUserId("");
    SessionSupervisor active = activeSupervisorRow(1L);
    when(sessionSupervisorRepository.findById(1L)).thenReturn(Optional.of(active));

    facade.removeSupervisor(SESSION_ID, 1L, addedBy);

    verify(matrixSynapseService, never()).removeUserFromRoom(any(), any(), any());
    assertThat(active.getIsActive()).isFalse();
  }

  @Test
  void removeSupervisor_Should_skipRoomRemoval_When_removedByConsultantHasNoMatrixId() {
    addedBy.setMatrixUserId(null);
    SessionSupervisor active = activeSupervisorRow(1L);
    when(sessionSupervisorRepository.findById(1L)).thenReturn(Optional.of(active));

    facade.removeSupervisor(SESSION_ID, 1L, addedBy);

    verify(matrixSynapseService, never()).removeUserFromRoom(any(), any(), any());
  }

  @Test
  void removeSupervisor_Should_skipRoomRemoval_When_consultantTokenNull() {
    SessionSupervisor active = activeSupervisorRow(1L);
    when(sessionSupervisorRepository.findById(1L)).thenReturn(Optional.of(active));
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MXID)).thenReturn(null);

    facade.removeSupervisor(SESSION_ID, 1L, addedBy);

    verify(matrixSynapseService, never()).removeUserFromRoom(any(), any(), any());
    assertThat(active.getIsActive()).isFalse();
  }

  @Test
  void removeSupervisor_Should_skipRoomCall_When_roomIdBlank() {
    SessionSupervisor active = activeSupervisorRow(1L);
    active.setMatrixRoomId("");
    session.setMatrixRoomId(null);
    when(sessionSupervisorRepository.findById(1L)).thenReturn(Optional.of(active));
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MXID)).thenReturn("tok");

    facade.removeSupervisor(SESSION_ID, 1L, addedBy);

    verify(matrixSynapseService, never()).removeUserFromRoom(any(), any(), any());
  }

  // --- getSupervisors / getPendingConsentSupervisors ---

  @Test
  void getSupervisors_Should_delegateToRepository() {
    when(sessionSupervisorRepository.findBySessionIdAndIsActiveTrue(SESSION_ID))
        .thenReturn(List.of(activeSupervisorRow(1L)));

    List<SessionSupervisor> result = facade.getSupervisors(SESSION_ID);

    assertThat(result).hasSize(1);
  }

  @Test
  void getPendingConsentSupervisors_Should_returnOnlyPendingConsentRows() {
    SessionSupervisor pending = pendingSupervisor(1L);
    SessionSupervisor active = activeSupervisorRow(2L);
    when(sessionSupervisorRepository.findBySessionId(SESSION_ID))
        .thenReturn(List.of(pending, active));

    List<SessionSupervisor> result = facade.getPendingConsentSupervisors(SESSION_ID);

    assertThat(result).containsExactly(pending);
  }

  // --- hasPermissionToManageSupervisors: uncovered final branch ---

  @Test
  void addSupervisor_Should_throwForbidden_When_differentAgencyAndRestrictionDisabled() {
    ReflectionTestUtils.setField(facade, "restrictAddToAssignedConsultant", false);
    Consultant differentAgency = new Consultant();
    differentAgency.setId("con-4");
    differentAgency.setMatrixUserId("@con4:oriso");
    differentAgency.setConsultantAgencies(
        Set.of(ConsultantAgency.builder().agencyId(999L).build()));

    assertThatThrownBy(
            () ->
                facade.addSupervisor(
                    SESSION_ID, SUPERVISOR_ID, differentAgency, "PEER_SUPPORT", "justified"))
        .isInstanceOf(ForbiddenException.class);
  }

  // --- provisionSupervisorRooms: uncovered branches ---

  @Test
  void addSupervisor_Should_throwInternalServerError_When_consultantMatrixTokenNull() {
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MXID)).thenReturn(null);

    assertThatThrownBy(() -> facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "r"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException
                .class);
  }

  @Test
  void addSupervisor_Should_continueWithWarning_When_powerLevelNotSet() throws Exception {
    when(matrixSynapseService.setUserPowerLevel(any(), any(), any(Integer.class), any()))
        .thenReturn(false);

    SessionSupervisor saved =
        facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason");

    assertThat(saved).isNotNull();
  }

  @Test
  void addSupervisor_Should_skipJoin_When_supervisorTokenNull() throws Exception {
    // First call: side-room inviteAndJoin needs a non-null token to succeed.
    // Second call: provisionSupervisorRooms' own final client-room join lookup — null here
    // is the branch under test (join is skipped, no exception).
    when(matrixSynapseService.loginAsUserAccessToken(SUPERVISOR_MXID))
        .thenReturn("tok", (String) null);

    SessionSupervisor saved =
        facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason");

    verify(matrixSynapseService, never()).joinRoom(eq(CLIENT_ROOM), any());
    assertThat(saved).isNotNull();
  }

  @Test
  void addSupervisor_Should_continue_When_supervisorJoinReturnsFalse() throws Exception {
    when(matrixSynapseService.joinRoom(any(), any())).thenReturn(false);

    SessionSupervisor saved =
        facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason");

    assertThat(saved).isNotNull();
  }

  // --- ensureSupervisionSideRoom: uncovered branches ---

  @Test
  void addSupervisor_Should_throwInternalServerError_When_createRoomThrows() throws Exception {
    when(matrixSynapseService.createRoom(any(), any(), any()))
        .thenThrow(new RuntimeException("matrix down"));

    assertThatThrownBy(
            () -> facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException
                .class);
  }

  @Test
  void addSupervisor_Should_throwInternalServerError_When_createRoomReturnsNoRoomId()
      throws Exception {
    when(matrixSynapseService.createRoom(any(), any(), any()))
        .thenReturn(ResponseEntity.ok(new MatrixCreateRoomResponseDTO()));

    assertThatThrownBy(
            () -> facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException
                .class);
  }

  @Test
  void addSupervisor_Should_alsoInviteAssignedConsultant_When_differentFromAddedBy()
      throws Exception {
    Consultant assigned = new Consultant();
    assigned.setId("con-assigned");
    assigned.setMatrixUserId("@assigned:oriso");
    session.setConsultant(assigned);
    // addedBy is now a same-agency (not assigned) consultant — allow via restriction disabled.
    ReflectionTestUtils.setField(facade, "restrictAddToAssignedConsultant", false);
    addedBy.setConsultantAgencies(Set.of(ConsultantAgency.builder().agencyId(7L).build()));

    facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason");

    verify(matrixSynapseService).inviteUserToRoom(any(), eq("@assigned:oriso"), any());
  }

  // --- inviteAndJoin (side room): uncovered branches ---

  @Test
  void addSupervisor_Should_continue_When_sideRoomInviteAlreadyInRoom() throws Exception {
    when(matrixSynapseService.inviteUserToRoom(eq(SIDE_ROOM), eq(SUPERVISOR_MXID), any()))
        .thenThrow(
            new MatrixInviteUserException(
                "Could not invite user: {\"errcode\":\"M_FORBIDDEN\",\"error\":\""
                    + SUPERVISOR_MXID
                    + " is already in the room.\"}"));

    SessionSupervisor saved =
        facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason");

    assertThat(saved.getMatrixRoomId()).isEqualTo(SIDE_ROOM);
  }

  @Test
  void addSupervisor_Should_throwInternalServerError_When_sideRoomInviteFailsOtherMatrixError()
      throws Exception {
    when(matrixSynapseService.inviteUserToRoom(eq(SIDE_ROOM), eq(SUPERVISOR_MXID), any()))
        .thenThrow(new MatrixInviteUserException("some other Matrix failure"));

    assertThatThrownBy(
            () -> facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException
                .class);
  }

  @Test
  void addSupervisor_Should_throwInternalServerError_When_sideRoomInviteThrowsGenericException()
      throws Exception {
    when(matrixSynapseService.inviteUserToRoom(eq(SIDE_ROOM), eq(SUPERVISOR_MXID), any()))
        .thenThrow(new RuntimeException("boom"));

    assertThatThrownBy(
            () -> facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException
                .class);
  }

  @Test
  void addSupervisor_Should_throwInternalServerError_When_sideRoomUserTokenNull() {
    when(matrixSynapseService.loginAsUserAccessToken(SUPERVISOR_MXID)).thenReturn(null);

    // Supervisor token is used both for the side-room join (inviteAndJoin) and the client-room
    // join later; a null token at the side-room stage must fail fast.
    assertThatThrownBy(
            () -> facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException
                .class);
  }

  // --- inviteSupervisorToClientRoom: generic exception branch ---

  @Test
  void addSupervisor_Should_throwInternalServerError_When_clientRoomInviteThrowsGenericException()
      throws Exception {
    when(matrixSynapseService.inviteUserToRoom(eq(CLIENT_ROOM), eq(SUPERVISOR_MXID), any()))
        .thenThrow(new RuntimeException("network blip"));

    assertThatThrownBy(
            () -> facade.addSupervisor(SESSION_ID, SUPERVISOR_ID, addedBy, null, "reason"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException
                .class);
  }

  // --- decideSupervisionConsent: uncovered guard branches ---

  @Test
  void decideSupervisionConsent_Should_activateSupervisorAndProvisionMatrix_When_approved() {
    SessionSupervisor pending =
        SessionSupervisor.builder()
            .id(7L)
            .session(session)
            .supervisorConsultant(supervisor)
            .addedByConsultant(addedBy)
            .isActive(false)
            .matrixRoomId(null)
            .notes(
                SupervisionNotes.encode(
                    SupervisionReason.SAFEGUARDING_U25,
                    "needs oversight",
                    SupervisionConsent.PENDING))
            .build();
    when(sessionSupervisorRepository.findById(7L)).thenReturn(Optional.of(pending));

    SessionSupervisor result = facade.decideSupervisionConsent(SESSION_ID, 7L, true);

    assertThat(result.getIsActive()).isTrue();
    assertThat(result.getMatrixRoomId()).isEqualTo(SIDE_ROOM);
    assertThat(SupervisionNotes.decode(result.getNotes()).consent)
        .isEqualTo(SupervisionConsent.APPROVED.name());
  }

  @Test
  void decideSupervisionConsent_Should_keepInactiveAndRecordDeclined_When_declined() {
    SessionSupervisor pending =
        SessionSupervisor.builder()
            .id(8L)
            .session(session)
            .supervisorConsultant(supervisor)
            .addedByConsultant(addedBy)
            .isActive(false)
            .matrixRoomId(null)
            .notes(
                SupervisionNotes.encode(
                    SupervisionReason.SAFEGUARDING_U25,
                    "needs oversight",
                    SupervisionConsent.PENDING))
            .build();
    when(sessionSupervisorRepository.findById(8L)).thenReturn(Optional.of(pending));

    SessionSupervisor result = facade.decideSupervisionConsent(SESSION_ID, 8L, false);

    assertThat(result.getIsActive()).isFalse();
    assertThat(result.getMatrixRoomId()).isNull();
    assertThat(SupervisionNotes.decode(result.getNotes()).consent)
        .isEqualTo(SupervisionConsent.DECLINED.name());
  }

  @Test
  void decideSupervisionConsent_Should_throwBadRequest_When_sessionIsNull() {
    SessionSupervisor noSession =
        SessionSupervisor.builder()
            .id(5L)
            .session(null)
            .supervisorConsultant(supervisor)
            .isActive(false)
            .notes(
                SupervisionNotes.encode(
                    SupervisionReason.SAFEGUARDING_U25, "x", SupervisionConsent.PENDING))
            .build();
    when(sessionSupervisorRepository.findById(5L)).thenReturn(Optional.of(noSession));

    assertThatThrownBy(() -> facade.decideSupervisionConsent(SESSION_ID, 5L, true))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void decideSupervisionConsent_Should_throwBadRequest_When_sessionIdMismatch() {
    Session otherSession = new Session();
    otherSession.setId(777L);
    SessionSupervisor mismatched =
        SessionSupervisor.builder()
            .id(6L)
            .session(otherSession)
            .supervisorConsultant(supervisor)
            .isActive(false)
            .notes(
                SupervisionNotes.encode(
                    SupervisionReason.SAFEGUARDING_U25, "x", SupervisionConsent.PENDING))
            .build();
    when(sessionSupervisorRepository.findById(6L)).thenReturn(Optional.of(mismatched));

    assertThatThrownBy(() -> facade.decideSupervisionConsent(SESSION_ID, 6L, true))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void decideSupervisionConsent_Should_throwNotFound_When_supervisorNotFound() {
    when(sessionSupervisorRepository.findById(123L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> facade.decideSupervisionConsent(SESSION_ID, 123L, true))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException.class);
  }
}
