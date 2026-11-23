package de.caritas.cob.userservice.api.service.chat;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupChatRoleLockOrderTest {
  @Mock GroupChatParticipantRepository participants;
  @Mock ChatRepository chats;
  @Mock ConsultantRepository consultants;
  @Mock GroupChatMembershipService membership;
  @Mock MembersOnlyRevocation revocation;
  @Mock GroupChatMatrixCleanupService cleanup;
  @Mock EntityManager entityManager;
  @InjectMocks GroupChatRoleService service;

  @ParameterizedTest
  @ValueSource(strings = {"change", "transfer", "remove", "leave"})
  void recurringWritersLockOwnerAndChatBeforeParticipants(String operation) {
    var series = series(0);
    if (!operation.equals("leave")) when(chats.findById(7L)).thenReturn(Optional.of(series));
    when(chats.findByIdForUpdate(7L)).thenReturn(Optional.of(series));
    var leaver = new Consultant();
    leaver.setId("member");
    if (operation.equals("leave")) {
      service.leaveSeries(series, leaver);
    } else {
      assertThrows(ForbiddenException.class, () -> invoke(operation, series, leaver));
    }
    var order = inOrder(cleanup, chats, entityManager, participants);
    order.verify(cleanup).lockOwner(series.getChatOwner());
    order.verify(chats).findByIdForUpdate(7L);
    order.verify(entityManager).refresh(series, LockModeType.PESSIMISTIC_WRITE);
    order.verify(participants).findBySeriesIdForUpdate(7L);
  }

  @Test
  void changedOccurrenceStopsBeforeParticipantLock() {
    var observed = series(0);
    var changed = series(1);
    when(chats.findById(7L)).thenReturn(Optional.of(observed));
    when(chats.findByIdForUpdate(7L)).thenReturn(Optional.of(changed));
    assertThrows(ConflictException.class, () -> service.removeParticipant(7L, "owner", "member"));
    verify(participants, never()).findBySeriesIdForUpdate(anyLong());
    verifyNoInteractions(membership, revocation);
  }

  private void invoke(String operation, Chat series, Consultant leaver) {
    switch (operation) {
      case "change" -> service.changeRole(7L, "owner", "member", ParticipantRole.CO_MODERATOR);
      case "transfer" -> service.transferPrimaryOwnership(7L, "owner", "member");
      case "remove" -> service.removeParticipant(7L, "owner", "member");
      default -> service.leaveSeries(series, leaver);
    }
  }

  private Chat series(int occurrence) {
    var owner = new Consultant();
    owner.setId("owner");
    owner.setTenantId(41L);
    return Chat.builder()
        .id(7L)
        .topic("Peer support")
        .initialStartDate(java.time.LocalDateTime.now())
        .startDate(java.time.LocalDateTime.now())
        .chatOwner(owner)
        .repetitive(true)
        .conversationType(ConversationType.SELF_HELP)
        .currentOccurrenceIndex(occurrence)
        .matrixRoomId("!room:matrix")
        .build();
  }
}
