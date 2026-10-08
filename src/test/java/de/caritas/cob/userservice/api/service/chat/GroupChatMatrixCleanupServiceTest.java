package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.model.GroupChatMatrixCleanupTask.Action;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupChatMatrixCleanupServiceTest {
  @Mock GroupChatMatrixCleanupTaskRepository tasks;
  @Mock ConsultantRepository consultants;
  @Mock ChatRepository chats;
  @Mock GroupChatParticipantRepository participants;
  @Mock MatrixSynapseService matrix;
  @Mock GroupChatMembershipService membership;
  GroupChatMatrixCleanupService service;
  GroupChatMatrixCleanupTask task;
  Consultant owner;

  @BeforeEach
  void setup() {
    service =
        new GroupChatMatrixCleanupService(
            tasks, consultants, chats, participants, matrix, membership, Clock.systemUTC());
    owner = new Consultant();
    owner.setId("owner");
    owner.setTenantId(41L);
    task =
        GroupChatMatrixCleanupTask.builder()
            .id(1L)
            .ownerId("owner")
            .tenantId(41L)
            .seriesId(7L)
            .action(Action.PURGE_ROOM)
            .roomId("!new:matrix")
            .createdAt(LocalDateTime.now())
            .build();
    when(tasks.findById(1L)).thenReturn(Optional.of(task));
    when(tasks.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
    when(consultants.findPictureOwnerForUpdate("owner")).thenReturn(Optional.of(owner));
  }

  @Test
  void failedPurgeRetainsDurableRetry() {
    when(matrix.purgeRoomOrConfirmGone("!new:matrix"))
        .thenReturn(MatrixSynapseService.RoomPurgeOutcome.FAILED);
    service.retry(1L);
    verify(tasks, never()).delete(any());
    verify(tasks).save(task);
    assertThat(task.getAttemptCount()).isEqualTo(1);
  }

  @Test
  void alreadyGoneRoomCompletesCleanupIdempotently() {
    when(matrix.purgeRoomOrConfirmGone("!new:matrix"))
        .thenReturn(MatrixSynapseService.RoomPurgeOutcome.ALREADY_GONE);
    service.retry(1L);
    verify(tasks).delete(task);
  }

  @Test
  void committedOwnedRoomIsNeverPurged() {
    var chat = chat(7L);
    when(chats.findByIdForUpdate(7L)).thenReturn(Optional.of(chat));
    when(chats.findByMatrixRoomIdForUpdate("!new:matrix")).thenReturn(Optional.of(chat));
    service.retry(1L);
    verify(tasks).delete(task);
    verifyNoInteractions(matrix, membership);
  }

  @Test
  void foreignRoomOwnershipRetainsTaskWithoutPurge() {
    when(chats.findByMatrixRoomIdForUpdate("!new:matrix")).thenReturn(Optional.of(chat(8L)));
    service.retry(1L);
    verify(tasks).save(task);
    verifyNoInteractions(matrix, membership);
  }

  @Test
  void committedParticipantSupersedesOldRemovalIntent() {
    var chat = chat(7L);
    task.setAction(Action.REMOVE_MEMBER);
    task.setConsultantId("member");
    task.setMemberId("@member:matrix");
    when(chats.findByIdForUpdate(7L)).thenReturn(Optional.of(chat));
    when(chats.findByMatrixRoomIdForUpdate("!new:matrix")).thenReturn(Optional.of(chat));
    when(participants.findBySeriesIdAndConsultantIdForUpdate(7L, "member"))
        .thenReturn(Optional.of(new GroupChatParticipant()));
    service.retry(1L);
    verify(tasks).delete(task);
    var locks = inOrder(consultants, chats, tasks, participants);
    locks.verify(consultants).findPictureOwnerForUpdate("owner");
    locks.verify(chats).findByIdForUpdate(7L);
    locks.verify(tasks).findByIdForUpdate(1L);
    locks.verify(chats).findByMatrixRoomIdForUpdate("!new:matrix");
    locks.verify(participants).findBySeriesIdAndConsultantIdForUpdate(7L, "member");
    verify(chats, never()).findByMatrixRoomId(any());
    verify(participants, never()).findBySeriesIdAndConsultantId(any(), any());
    verifyNoInteractions(matrix, membership);
  }

  @Test
  void roomOwnershipLockFailurePropagatesBeforeAnyCleanupOrIntentDeletion() {
    when(chats.findByMatrixRoomIdForUpdate("!new:matrix"))
        .thenThrow(new org.springframework.dao.CannotAcquireLockException("synthetic contention"));
    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.dao.CannotAcquireLockException.class, () -> service.retry(1L));
    verify(tasks, never()).delete(any());
    verify(tasks, never()).save(any());
    verifyNoInteractions(matrix, membership);
  }

  @Test
  void participantLockFailurePropagatesBeforeAnyCleanupOrIntentDeletion() {
    task.setAction(Action.REMOVE_MEMBER);
    task.setConsultantId("member");
    when(chats.findByMatrixRoomIdForUpdate("!new:matrix")).thenReturn(Optional.of(chat(7L)));
    when(participants.findBySeriesIdAndConsultantIdForUpdate(7L, "member"))
        .thenThrow(new org.springframework.dao.CannotAcquireLockException("synthetic contention"));
    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.dao.CannotAcquireLockException.class, () -> service.retry(1L));
    verify(tasks, never()).delete(any());
    verify(tasks, never()).save(any());
    verifyNoInteractions(matrix, membership);
  }

  @Test
  void changedTenantNeverAuthorizesCleanup() {
    owner.setTenantId(42L);
    service.retry(1L);
    verify(tasks).save(task);
    verifyNoInteractions(matrix, membership);
  }

  @Test
  void unconfirmedCompensationRemainsRetryable() {
    task.setAction(Action.REMOVE_MEMBER);
    task.setMemberId("@member:matrix");
    when(membership.removeMemberFromRoomAndConfirm("!new:matrix", "@member:matrix"))
        .thenReturn(false);
    service.compensate(1L);
    verify(tasks, never()).delete(any());
  }

  @Test
  void writerDoesNotReadLockedOwnerOrChatDuringIndependentJournalInsert() {
    when(tasks.saveAndFlush(any()))
        .thenAnswer(
            i -> {
              var saved = (GroupChatMatrixCleanupTask) i.getArgument(0);
              saved.setId(2L);
              return saved;
            });
    assertThat(
            service.recordRoom(
                new GroupChatMatrixCleanupService.GroupOwner(7L, "owner", 41L), "!new:matrix"))
        .isEqualTo(2L);
    verifyNoInteractions(consultants, chats, participants, matrix, membership);
  }

  private Chat chat(Long id) {
    var chat = new Chat();
    chat.setId(id);
    chat.setChatOwner(owner);
    chat.setMatrixRoomId("!new:matrix");
    return chat;
  }
}
