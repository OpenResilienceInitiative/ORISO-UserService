package de.caritas.cob.userservice.api.service.chat;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupChatParticipantReconciliationServiceTest {

  @Mock private GroupChatParticipantRepository participantRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private GroupChatMembershipService membershipService;
  @Mock private AgencySilentMembershipService consultantMembership;

  @Mock private GroupChatMatrixCleanupService matrixCleanup;

  private GroupChatParticipantReconciliationService service;
  private Chat series;
  private GroupChatParticipant owner;

  @BeforeEach
  void setUp() {
    var groupPolicy =
        de.caritas.cob.userservice.api.testHelper.PermittingDpaOwnerFixture.groupPolicy();
    service =
        new GroupChatParticipantReconciliationService(
            participantRepository,
            consultantRepository,
            membershipService,
            consultantMembership,
            groupPolicy,
            matrixCleanup);
    Mockito.lenient()
        .when(membershipService.removeLeavingMemberFromRoomAndConfirm(Mockito.any(), Mockito.any()))
        .thenReturn(true);
    Mockito.lenient()
        .when(membershipService.isMemberInRoom(Mockito.any(Chat.class), Mockito.anyString()))
        .thenReturn(Optional.of(false));
    var ownerConsultant = consultant("owner", "@owner:matrix");
    series = Mockito.mock(Chat.class);
    Mockito.lenient().when(series.getId()).thenReturn(42L);
    Mockito.lenient().when(series.getChatOwner()).thenReturn(ownerConsultant);
    owner = participant(7L, "owner", ParticipantRole.OWNER);
  }

  @Test
  void laterJoinFailureCompensatesOnlyNewRemoteAccessAndRetainsOldMembers() {
    var old = participant(7L, "returning", ParticipantRole.CO_MODERATOR);
    var returning = consultant("returning", "@returning:matrix");
    var first = consultant("first", "@first:matrix");
    var second = consultant("second", "@second:matrix");
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner, old));
    when(consultantRepository.findByIdAndDeleteDateIsNull("returning"))
        .thenReturn(Optional.of(returning));
    when(consultantRepository.findByIdAndDeleteDateIsNull("first")).thenReturn(Optional.of(first));
    when(consultantRepository.findByIdAndDeleteDateIsNull("second"))
        .thenReturn(Optional.of(second));
    when(membershipService.addMemberToRoom(series, "@returning:matrix")).thenReturn(true);
    when(membershipService.addMemberToRoom(series, "@first:matrix")).thenReturn(true);
    when(matrixCleanup.recordJoin(
            Mockito.any(), Mockito.eq("first"), Mockito.any(), Mockito.eq("@first:matrix")))
        .thenReturn(1L);
    when(matrixCleanup.recordJoin(
            Mockito.any(), Mockito.eq("second"), Mockito.any(), Mockito.eq("@second:matrix")))
        .thenReturn(2L);
    assertThrows(
        InternalServerErrorException.class,
        () -> service.reconcile(series, List.of("returning", "first", "second")));
    verify(matrixCleanup).compensate(1L);
    verify(matrixCleanup).compensate(2L);
    verify(matrixCleanup, never())
        .recordJoin(
            Mockito.any(), Mockito.eq("returning"), Mockito.any(), Mockito.eq("@returning:matrix"));
    verify(participantRepository, never()).save(Mockito.any());
    verify(participantRepository, never()).delete(Mockito.any());
    var order = Mockito.inOrder(matrixCleanup, membershipService);
    order
        .verify(matrixCleanup)
        .recordJoin(Mockito.any(), Mockito.eq("first"), Mockito.any(), Mockito.eq("@first:matrix"));
    order.verify(membershipService).addMemberToRoom(series, "@first:matrix");
  }

  @Test
  void preExistingRemoteMemberIsNeverCompensatedAfterLaterFailure() {
    var first = consultant("first", "@first:matrix");
    var second = consultant("second", "@second:matrix");
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner));
    when(consultantRepository.findByIdAndDeleteDateIsNull("first")).thenReturn(Optional.of(first));
    when(consultantRepository.findByIdAndDeleteDateIsNull("second"))
        .thenReturn(Optional.of(second));
    when(membershipService.isMemberInRoom(series, "@first:matrix")).thenReturn(Optional.of(true));
    when(membershipService.addMemberToRoom(series, "@first:matrix")).thenReturn(true);
    when(matrixCleanup.recordJoin(
            Mockito.any(), Mockito.eq("second"), Mockito.any(), Mockito.eq("@second:matrix")))
        .thenReturn(2L);
    assertThrows(
        InternalServerErrorException.class,
        () -> service.reconcile(series, List.of("first", "second")));
    verify(matrixCleanup, never())
        .recordJoin(Mockito.any(), Mockito.eq("first"), Mockito.any(), Mockito.eq("@first:matrix"));
    verify(matrixCleanup).compensate(2L);
  }

  @Test
  void unknownRemoteStateOrFailedJournalNeverAuthorizesANewJoin() {
    var first = consultant("first", "@first:matrix");
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner));
    when(consultantRepository.findByIdAndDeleteDateIsNull("first")).thenReturn(Optional.of(first));
    when(membershipService.isMemberInRoom(series, "@first:matrix")).thenReturn(Optional.empty());
    assertThrows(
        InternalServerErrorException.class, () -> service.reconcile(series, List.of("first")));
    verify(membershipService, never()).addMemberToRoom(Mockito.any(), Mockito.any());
    verify(matrixCleanup, never())
        .recordJoin(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    when(membershipService.isMemberInRoom(series, "@first:matrix")).thenReturn(Optional.of(false));
    when(matrixCleanup.recordJoin(
            Mockito.any(), Mockito.eq("first"), Mockito.any(), Mockito.eq("@first:matrix")))
        .thenThrow(new IllegalStateException("journal unavailable"));
    assertThrows(IllegalStateException.class, () -> service.reconcile(series, List.of("first")));
    verify(membershipService, never()).addMemberToRoom(Mockito.any(), Mockito.any());
  }

  @Test
  void softDeletedIdentityIsStillRemovedBeforeTrackingIsDeleted() {
    var removed = participant(7L, "removed", ParticipantRole.CO_MODERATOR);
    var identity = consultant("removed", "@removed:matrix");
    identity.setDeleteDate(java.time.LocalDateTime.now());
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner, removed));
    when(consultantRepository.findById("removed")).thenReturn(Optional.of(identity));
    service.reconcile(series, List.of());
    var order = Mockito.inOrder(membershipService, participantRepository);
    order
        .verify(membershipService)
        .removeLeavingMemberFromRoomAndConfirm(series, "@removed:matrix");
    order.verify(participantRepository).delete(removed);
    verify(consultantRepository, never()).findByIdAndDeleteDateIsNull("removed");
  }

  @Test
  void failedMatrixRemovalKeepsParticipantTracking() {
    when(membershipService.removeLeavingMemberFromRoomAndConfirm(Mockito.any(), Mockito.any()))
        .thenReturn(false);
    var removed = participant(7L, "removed", ParticipantRole.CO_MODERATOR);
    var leaver = consultant("removed", "@removed:matrix");
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner, removed));
    Mockito.lenient()
        .when(consultantRepository.findById("removed"))
        .thenReturn(Optional.of(leaver));
    Mockito.lenient()
        .when(consultantRepository.findById("removed"))
        .thenReturn(Optional.of(leaver));
    Mockito.lenient()
        .when(membershipService.isMemberInRoom(series, "@removed:matrix"))
        .thenReturn(Optional.of(true));
    assertThrows(InternalServerErrorException.class, () -> service.reconcile(series, List.of()));
    verify(participantRepository, never()).delete(Mockito.any());
  }

  @Test
  void reconcile_ShouldCheckNewActorAdmissionBeforeLazyProvisioningOrAnyMembershipChange() {
    var newcomer = consultant("newcomer", null);
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner));
    when(consultantRepository.findByIdAndDeleteDateIsNull("newcomer"))
        .thenReturn(Optional.of(newcomer));
    var admission = Mockito.mock(GroupCounsellingDpaPolicy.class);
    var refusal = new BadRequestException("Actor admission refused");
    Mockito.doThrow(refusal)
        .when(admission)
        .requireAuthorizedEnrolments(series, java.util.Collections.singletonList(null));
    var guarded =
        new GroupChatParticipantReconciliationService(
            participantRepository,
            consultantRepository,
            membershipService,
            consultantMembership,
            admission,
            matrixCleanup);

    org.junit.jupiter.api.Assertions.assertSame(
        refusal,
        assertThrows(
            BadRequestException.class, () -> guarded.reconcile(series, List.of("newcomer"))));
    verifyNoInteractions(membershipService, consultantMembership);
    verify(participantRepository, never()).save(Mockito.any());
    verify(participantRepository, never()).delete(Mockito.any());
  }

  @Test
  void reconcile_ShouldClassifyOnlyTheNewActorWithoutLettingAnExistingParticipantExemptThem() {
    var returning = consultant("returning", "@returning:matrix");
    var newcomer = consultant("newcomer", "@newcomer:matrix");
    when(participantRepository.findBySeriesIdForUpdate(42L))
        .thenReturn(List.of(owner, participant(7L, "returning", ParticipantRole.PARTICIPANT)));
    when(consultantRepository.findByIdAndDeleteDateIsNull("returning"))
        .thenReturn(Optional.of(returning));
    when(consultantRepository.findByIdAndDeleteDateIsNull("newcomer"))
        .thenReturn(Optional.of(newcomer));
    when(membershipService.addMemberToRoom(series, "@returning:matrix")).thenReturn(true);
    when(membershipService.addMemberToRoom(series, "@newcomer:matrix")).thenReturn(true);
    var admission = Mockito.mock(GroupCounsellingDpaPolicy.class);
    var guarded =
        new GroupChatParticipantReconciliationService(
            participantRepository,
            consultantRepository,
            membershipService,
            consultantMembership,
            admission,
            matrixCleanup);

    guarded.reconcile(series, List.of("returning", "newcomer"));

    var ordering = Mockito.inOrder(admission, membershipService);
    ordering.verify(admission).requireAuthorizedEnrolments(series, List.of("@newcomer:matrix"));
    ordering.verify(membershipService).addMemberToRoom(series, "@returning:matrix");
    ordering.verify(membershipService).addMemberToRoom(series, "@newcomer:matrix");
  }

  @Test
  void reconcile_ShouldPreserveParticipants_WhenIdsAreOmitted() {
    service.reconcile(series, null);

    verifyNoInteractions(
        participantRepository, consultantRepository, membershipService, consultantMembership);
  }

  @Test
  void reconcile_ShouldInviteAndPersistNewCoModerator_WithoutDuplicatingOwner() {
    var coModerator = consultant("co-moderator", "@co-moderator:matrix");
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner));
    when(consultantRepository.findByIdAndDeleteDateIsNull("co-moderator"))
        .thenReturn(Optional.of(coModerator));
    when(membershipService.addMemberToRoom(series, "@co-moderator:matrix")).thenReturn(true);

    service.reconcile(series, List.of("owner", "co-moderator", "co-moderator"));

    var saved = ArgumentCaptor.forClass(GroupChatParticipant.class);
    verify(participantRepository).save(saved.capture());
    verify(membershipService).addMemberToRoom(series, "@co-moderator:matrix");
    var participant = saved.getValue();
    org.junit.jupiter.api.Assertions.assertEquals(7L, participant.getChatId());
    org.junit.jupiter.api.Assertions.assertEquals(42L, participant.getSeriesId());
    org.junit.jupiter.api.Assertions.assertEquals("co-moderator", participant.getConsultantId());
    org.junit.jupiter.api.Assertions.assertEquals(
        ParticipantRole.CO_MODERATOR, participant.getRole());
  }

  @Test
  void reconcile_ShouldProvisionNewCoModeratorBeforeInvitingToExistingGroup() {
    var newcomer = consultant("newcomer", null);
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner));
    when(consultantRepository.findByIdAndDeleteDateIsNull("newcomer"))
        .thenReturn(Optional.of(newcomer));
    when(consultantMembership.ensureMatrixAccount(newcomer))
        .thenReturn("@newcomer=40example.org:matrix");
    when(membershipService.addMemberToRoom(series, "@newcomer=40example.org:matrix"))
        .thenReturn(true);

    service.reconcile(series, List.of("newcomer"));

    verify(consultantMembership).ensureMatrixAccount(newcomer);
    verify(membershipService).addMemberToRoom(series, "@newcomer=40example.org:matrix");
    verify(participantRepository).save(Mockito.any(GroupChatParticipant.class));
  }

  @Test
  void reconcile_ShouldNotInviteOrPersistWhenAccountProvisioningFails() {
    var newcomer = consultant("newcomer", null);
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner));
    when(consultantRepository.findByIdAndDeleteDateIsNull("newcomer"))
        .thenReturn(Optional.of(newcomer));

    assertThrows(
        InternalServerErrorException.class, () -> service.reconcile(series, List.of("newcomer")));

    verifyNoInteractions(membershipService);
    verify(participantRepository, never()).save(Mockito.any());
  }

  @Test
  void reconcile_ShouldRemoveDeselectedCoModerator_ButNeverOwner() {
    var removed = participant(7L, "removed", ParticipantRole.CO_MODERATOR);
    var removedConsultant = consultant("removed", "@removed:matrix");
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner, removed));
    when(consultantRepository.findById("removed")).thenReturn(Optional.of(removedConsultant));

    service.reconcile(series, List.of());

    verify(membershipService).removeLeavingMemberFromRoomAndConfirm(series, "@removed:matrix");
    verify(participantRepository).delete(removed);
    verify(participantRepository, never()).delete(owner);
  }

  @Test
  void reconcile_ShouldPreserveDeselectedRegularParticipant() {
    var participant = participant(7L, "participant", ParticipantRole.PARTICIPANT);
    when(participantRepository.findBySeriesIdForUpdate(42L))
        .thenReturn(List.of(owner, participant));

    service.reconcile(series, List.of());

    verify(participantRepository, never()).delete(participant);
    verifyNoInteractions(consultantRepository, membershipService);
  }

  @Test
  void reconcile_ShouldRejectConsultantFromAnotherTenant() {
    var coModerator = consultant("co-moderator", "@co-moderator:matrix");
    when(series.getChatOwner().getTenantId()).thenReturn(84L);
    when(coModerator.getTenantId()).thenReturn(1L);
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner));
    when(consultantRepository.findByIdAndDeleteDateIsNull("co-moderator"))
        .thenReturn(Optional.of(coModerator));

    assertThrows(
        BadRequestException.class, () -> service.reconcile(series, List.of("co-moderator")));

    verifyNoInteractions(membershipService);
  }

  @Test
  void reconcile_ShouldFailWithoutPersisting_WhenMatrixJoinFails() {
    var coModerator = consultant("co-moderator", "@co-moderator:matrix");
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner));
    when(consultantRepository.findByIdAndDeleteDateIsNull("co-moderator"))
        .thenReturn(Optional.of(coModerator));
    when(membershipService.addMemberToRoom(series, "@co-moderator:matrix")).thenReturn(false);

    assertThrows(
        InternalServerErrorException.class,
        () -> service.reconcile(series, List.of("co-moderator")));

    verify(participantRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void reconcile_ShouldRepairAnExistingSelectedCoModeratorWithoutMatrixAccount() {
    var existing = participant(7L, "existing", ParticipantRole.CO_MODERATOR);
    var consultant = consultant("existing", null);
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner, existing));
    when(consultantRepository.findByIdAndDeleteDateIsNull("existing"))
        .thenReturn(Optional.of(consultant));
    when(consultantMembership.ensureMatrixAccount(consultant)).thenReturn("@existing:matrix");
    when(membershipService.addMemberToRoom(series, "@existing:matrix")).thenReturn(true);

    service.reconcile(series, List.of("existing"));

    verify(consultantMembership).ensureMatrixAccount(consultant);
    verify(membershipService).addMemberToRoom(series, "@existing:matrix");
    verify(participantRepository, never()).save(Mockito.any());
  }

  @Test
  void reconcile_ShouldValidateWholeSelectionBeforeAnyMembershipSideEffects() {
    var removed = participant(7L, "removed", ParticipantRole.CO_MODERATOR);
    var valid = consultant("valid", "@valid:matrix");
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner, removed));
    var removedConsultant = consultant("removed", "@removed:matrix");
    Mockito.lenient()
        .when(consultantRepository.findById("removed"))
        .thenReturn(Optional.of(removedConsultant));
    when(consultantRepository.findByIdAndDeleteDateIsNull("valid")).thenReturn(Optional.of(valid));
    Mockito.lenient()
        .when(membershipService.addMemberToRoom(series, "@valid:matrix"))
        .thenReturn(true);
    when(consultantRepository.findByIdAndDeleteDateIsNull("deleted")).thenReturn(Optional.empty());

    assertThrows(
        BadRequestException.class, () -> service.reconcile(series, List.of("valid", "deleted")));

    verifyNoInteractions(membershipService, consultantMembership);
    verify(participantRepository, never()).delete(Mockito.any());
    verify(participantRepository, never()).save(Mockito.any());
  }

  @Test
  void reconcile_ShouldRevalidateExistingSelectionTenantBeforeJoining() {
    var existing = participant(7L, "existing", ParticipantRole.CO_MODERATOR);
    var consultant = consultant("existing", "@existing:matrix");
    when(consultant.getTenantId()).thenReturn(99L);
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner, existing));
    when(consultantRepository.findByIdAndDeleteDateIsNull("existing"))
        .thenReturn(Optional.of(consultant));

    assertThrows(BadRequestException.class, () -> service.reconcile(series, List.of("existing")));

    verifyNoInteractions(membershipService, consultantMembership);
  }

  @Test
  void reconcile_ShouldKeepDeselectedMembersWhenReplacementJoinFails() {
    var removed = participant(7L, "removed", ParticipantRole.CO_MODERATOR);
    var newcomer = consultant("newcomer", "@newcomer:matrix");
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner, removed));
    var removedConsultant = consultant("removed", "@removed:matrix");
    Mockito.lenient()
        .when(consultantRepository.findById("removed"))
        .thenReturn(Optional.of(removedConsultant));
    when(consultantRepository.findByIdAndDeleteDateIsNull("newcomer"))
        .thenReturn(Optional.of(newcomer));

    assertThrows(
        InternalServerErrorException.class, () -> service.reconcile(series, List.of("newcomer")));

    verify(membershipService, never())
        .removeLeavingMemberFromRoomAndConfirm(Mockito.any(), Mockito.any());
    verify(participantRepository, never()).delete(Mockito.any());
  }

  private GroupChatParticipant participant(
      Long sessionId, String consultantId, ParticipantRole role) {
    return GroupChatParticipant.builder()
        .id("owner".equals(consultantId) ? 1L : 2L)
        .chatId(sessionId)
        .seriesId(42L)
        .consultantId(consultantId)
        .role(role)
        .build();
  }

  private Consultant consultant(String id, String matrixUserId) {
    var consultant = Mockito.mock(Consultant.class);
    Mockito.lenient().when(consultant.getId()).thenReturn(id);
    Mockito.lenient().when(consultant.getMatrixUserId()).thenReturn(matrixUserId);
    return consultant;
  }
}
