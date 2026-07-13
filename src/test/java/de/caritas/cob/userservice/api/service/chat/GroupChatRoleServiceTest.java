package de.caritas.cob.userservice.api.service.chat;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupChatRoleServiceTest {

  @Mock private GroupChatParticipantRepository participantRepository;

  @Mock private ChatRepository chatRepository;

  @Mock private ConsultantRepository consultantRepository;

  @Mock private GroupChatMembershipService groupChatMembershipService;

  @InjectMocks private GroupChatRoleService roleService;

  @Test
  void changeRoleShouldRejectDemotingTheLastOwner() {
    var owner = participant(42L, "owner", ParticipantRole.OWNER);
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner));

    assertThrows(
        ConflictException.class,
        () -> roleService.changeRole(42L, "owner", "owner", ParticipantRole.CO_MODERATOR));

    verify(participantRepository, never()).save(owner);
  }

  @Test
  void transferPrimaryOwnershipShouldReplaceTheInitiatingOwnerAndUpdateTheChatProjection() {
    var actor = participant(42L, "owner", ParticipantRole.OWNER);
    var target = participant(42L, "target", ParticipantRole.CO_MODERATOR);
    var oldOwner = org.mockito.Mockito.mock(Consultant.class);
    var newOwner = org.mockito.Mockito.mock(Consultant.class);
    var series =
        Chat.builder()
            .id(42L)
            .topic("Peer support")
            .initialStartDate(java.time.LocalDateTime.now())
            .startDate(java.time.LocalDateTime.now())
            .chatOwner(oldOwner)
            .build();
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(actor, target));
    when(chatRepository.findById(42L)).thenReturn(java.util.Optional.of(series));
    when(consultantRepository.findById("target")).thenReturn(java.util.Optional.of(newOwner));

    roleService.transferPrimaryOwnership(42L, "owner", "target");

    org.junit.jupiter.api.Assertions.assertEquals(ParticipantRole.CO_MODERATOR, actor.getRole());
    org.junit.jupiter.api.Assertions.assertEquals(ParticipantRole.OWNER, target.getRole());
    org.junit.jupiter.api.Assertions.assertEquals(newOwner, series.getChatOwner());
    verify(participantRepository).save(actor);
    verify(participantRepository).save(target);
    verify(chatRepository).save(series);
  }

  @Test
  void removeParticipantShouldDeleteTheRoleAndRevokeMatrixRoomMembership() {
    var actor = participant(42L, "owner", ParticipantRole.OWNER);
    var target = participant(42L, "target", ParticipantRole.CO_MODERATOR);
    var series =
        Chat.builder()
            .id(42L)
            .topic("Peer support")
            .initialStartDate(java.time.LocalDateTime.now())
            .startDate(java.time.LocalDateTime.now())
            .matrixRoomId("!room:matrix.example")
            .build();
    var targetConsultant = org.mockito.Mockito.mock(Consultant.class);
    when(targetConsultant.getMatrixUserId()).thenReturn("@target:matrix.example");
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(actor, target));
    when(chatRepository.findById(42L)).thenReturn(java.util.Optional.of(series));
    when(consultantRepository.findById("target"))
        .thenReturn(java.util.Optional.of(targetConsultant));

    roleService.removeParticipant(42L, "owner", "target");

    verify(participantRepository).delete(target);
    verify(groupChatMembershipService)
        .removeLeavingMemberFromRoom(series, "@target:matrix.example");
  }

  @Test
  void leaveSeriesShouldRejectTheLastOwner() {
    var owner = participant(42L, "owner", ParticipantRole.OWNER);
    var series = org.mockito.Mockito.mock(Chat.class);
    var consultant = org.mockito.Mockito.mock(Consultant.class);
    when(series.getId()).thenReturn(42L);
    when(consultant.getId()).thenReturn("owner");
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(owner));

    assertThrows(ConflictException.class, () -> roleService.leaveSeries(series, consultant));

    verify(participantRepository, never()).delete(owner);
    verify(groupChatMembershipService, never()).removeLeavingMemberFromRoom(series, null);
  }

  @Test
  void leaveSeriesShouldRemoveACoModeratorFromTheSeriesAndCurrentMatrixRoom() {
    var coModerator = participant(42L, "co-mod", ParticipantRole.CO_MODERATOR);
    var series = org.mockito.Mockito.mock(Chat.class);
    var consultant = org.mockito.Mockito.mock(Consultant.class);
    when(series.getId()).thenReturn(42L);
    when(consultant.getId()).thenReturn("co-mod");
    when(consultant.getMatrixUserId()).thenReturn("@co-mod:matrix.example");
    when(participantRepository.findBySeriesIdForUpdate(42L)).thenReturn(List.of(coModerator));
    roleService.leaveSeries(series, consultant);

    verify(participantRepository).delete(coModerator);
    verify(groupChatMembershipService)
        .removeLeavingMemberFromRoom(series, "@co-mod:matrix.example");
  }

  private GroupChatParticipant participant(
      Long seriesId, String consultantId, ParticipantRole role) {
    return GroupChatParticipant.builder()
        .id("owner".equals(consultantId) ? 1L : 2L)
        .seriesId(seriesId)
        .chatId(200L)
        .consultantId(consultantId)
        .role(role)
        .build();
  }
}
