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

  private GroupChatParticipantReconciliationService service;
  private Chat series;
  private GroupChatParticipant owner;

  @BeforeEach
  void setUp() {
    service =
        new GroupChatParticipantReconciliationService(
            participantRepository, consultantRepository, membershipService);
    var ownerConsultant = consultant("owner", "@owner:matrix");
    series = Mockito.mock(Chat.class);
    Mockito.lenient().when(series.getId()).thenReturn(42L);
    Mockito.lenient().when(series.getChatOwner()).thenReturn(ownerConsultant);
    owner = participant(7L, "owner", ParticipantRole.OWNER);
  }

  @Test
  void reconcile_ShouldPreserveParticipants_WhenIdsAreOmitted() {
    service.reconcile(series, null);

    verifyNoInteractions(participantRepository, consultantRepository, membershipService);
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
  void reconcile_ShouldRemoveDeselectedCoModerator_ButNeverOwner() {
    var removed = participant(7L, "removed", ParticipantRole.CO_MODERATOR);
    var removedConsultant = consultant("removed", "@removed:matrix");
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner, removed));
    when(consultantRepository.findByIdAndDeleteDateIsNull("removed"))
        .thenReturn(Optional.of(removedConsultant));

    service.reconcile(series, List.of());

    verify(membershipService).removeLeavingMemberFromRoom(series, "@removed:matrix");
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
