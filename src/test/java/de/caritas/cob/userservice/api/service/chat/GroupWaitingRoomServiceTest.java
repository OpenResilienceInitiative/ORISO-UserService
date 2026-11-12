package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import de.caritas.cob.userservice.api.model.ChatMatrixRoom;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Via;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ChatMatrixRoomRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatJoinRequestRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** ORISO-Frontend#2237: who sits in a group's waiting room, and who may let them in. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupWaitingRoomServiceTest {

  private static final long CHAT_ID = 42L;
  private static final String ROOM = "!group:oriso";

  @Mock private GroupChatJoinRequestRepository repository;
  @Mock private ChatRepository chats;
  @Mock private ChatMatrixRoomRepository occurrenceRooms;
  @Mock private ChatPermissionVerifier permissions;
  @Mock private GroupChatConsultantAccess consultantAccess;

  private GroupWaitingRoomService service;
  private Chat chat;
  private User asker;
  private Consultant host;

  @BeforeEach
  void setUp() {
    service =
        new GroupWaitingRoomService(
            repository, chats, occurrenceRooms, permissions, consultantAccess);
    chat = new Chat();
    chat.setId(CHAT_ID);
    chat.setMatrixRoomId(ROOM);
    chat.setChatModality(ChatModality.VIDEO);
    asker = new User();
    asker.setUserId("asker-1");
    host = new Consultant();
    host.setId("host-1");
    when(chats.findById(CHAT_ID)).thenReturn(Optional.of(chat));
    when(chats.findByMatrixRoomId(ROOM)).thenReturn(Optional.of(chat));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void waitOpensOnePendingEntryForTheChat() {
    var entry = service.waitIn(CHAT_ID, asker);

    assertThat(entry.getVia()).isEqualTo(Via.WAITING_ROOM);
    assertThat(entry.getSeriesId()).isEqualTo(CHAT_ID);
    assertThat(entry.getUserId()).isEqualTo("asker-1");
    assertThat(entry.getStatus()).isEqualTo(Status.PENDING);
    assertThat(entry.getMedium()).isEqualTo("VIDEO");
    assertThat(entry.getWaitingSeenAt()).isNotNull();
    verify(permissions).verifyPermissionForChat(chat);
  }

  @Test
  void waitingAgainKeepsTheSameEntryAndMarksItSeen() {
    var open = pending(LocalDateTime.now().minusMinutes(3));
    open.setWaitingSeenAt(LocalDateTime.now().minusSeconds(20));
    when(repository.findFirstBySeriesIdAndUserIdAndViaOrderByIdDesc(
            CHAT_ID, "asker-1", Via.WAITING_ROOM))
        .thenReturn(Optional.of(open));

    var entry = service.waitIn(CHAT_ID, asker);

    assertThat(entry).isSameAs(open);
    assertThat(entry.getWaitingSeenAt()).isAfter(LocalDateTime.now().minusSeconds(2));
  }

  @Test
  void aFreshAdmissionIsReturnedSoTheWaitingPersonGoesIn() {
    var admitted = pending(LocalDateTime.now().minusMinutes(1));
    admitted.setStatus(Status.ADMITTED);
    admitted.setDecidedAt(LocalDateTime.now().minusSeconds(10));
    when(repository.findFirstBySeriesIdAndUserIdAndViaOrderByIdDesc(
            CHAT_ID, "asker-1", Via.WAITING_ROOM))
        .thenReturn(Optional.of(admitted));

    assertThat(service.waitIn(CHAT_ID, asker)).isSameAs(admitted);
    verify(repository, never()).save(any());
  }

  @Test
  void anOldAdmissionOpensANewWait() {
    var admitted = pending(LocalDateTime.now().minusHours(3));
    admitted.setStatus(Status.ADMITTED);
    admitted.setDecidedAt(LocalDateTime.now().minusHours(2));
    when(repository.findFirstBySeriesIdAndUserIdAndViaOrderByIdDesc(
            CHAT_ID, "asker-1", Via.WAITING_ROOM))
        .thenReturn(Optional.of(admitted));

    var entry = service.waitIn(CHAT_ID, asker);

    assertThat(entry).isNotSameAs(admitted);
    assertThat(entry.getStatus()).isEqualTo(Status.PENDING);
  }

  @Test
  void waitIsRefusedWithoutChatPermission() {
    doThrow(new ForbiddenException("no")).when(permissions).verifyPermissionForChat(chat);

    assertThatThrownBy(() -> service.waitIn(CHAT_ID, asker)).isInstanceOf(ForbiddenException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void waitOnAnUnknownChatIsNotFound() {
    when(chats.findById(CHAT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.waitIn(CHAT_ID, asker)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void aModeratorInTheGroupRoomSeesWhoIsStillWaiting() {
    when(consultantAccess.mayModerate(chat, host)).thenReturn(true);
    var waiting = pending(LocalDateTime.now().minusMinutes(2));
    var since = ArgumentCaptor.forClass(LocalDateTime.class);
    when(repository.findBySeriesIdAndViaAndStatusAndWaitingSeenAtAfter(
            eq(CHAT_ID), eq(Via.WAITING_ROOM), eq(Status.PENDING), since.capture()))
        .thenReturn(List.of(waiting));

    assertThat(service.waitingIn(host, ROOM)).containsExactly(waiting);
    // Somebody who closed the page drops off the list within a minute.
    assertThat(since.getValue()).isAfter(LocalDateTime.now().minusMinutes(2));
  }

  @Test
  void anOccurrenceRoomLeadsToItsSeries() {
    when(chats.findByMatrixRoomId("!occurrence:oriso")).thenReturn(Optional.empty());
    var occurrence = org.mockito.Mockito.mock(ChatMatrixRoom.class);
    when(occurrence.getSeries()).thenReturn(chat);
    when(occurrenceRooms.findByRoomId("!occurrence:oriso")).thenReturn(Optional.of(occurrence));
    when(consultantAccess.mayModerate(chat, host)).thenReturn(true);
    when(repository.findBySeriesIdAndViaAndStatusAndWaitingSeenAtAfter(
            eq(CHAT_ID), eq(Via.WAITING_ROOM), eq(Status.PENDING), any()))
        .thenReturn(List.of(pending(LocalDateTime.now())));

    assertThat(service.waitingIn(host, "!occurrence:oriso")).hasSize(1);
  }

  @Test
  void aCounsellorWhoMayNotModerateSeesNobody() {
    when(consultantAccess.mayModerate(chat, host)).thenReturn(false);

    assertThat(service.waitingIn(host, ROOM)).isEmpty();
    verify(repository, never())
        .findBySeriesIdAndViaAndStatusAndWaitingSeenAtAfter(any(), any(), any(), any());
  }

  @Test
  void aRoomWithoutGroupHasNoWaitingRoom() {
    when(chats.findByMatrixRoomId("!one-to-one:oriso")).thenReturn(Optional.empty());
    when(occurrenceRooms.findByRoomId("!one-to-one:oriso")).thenReturn(Optional.empty());

    assertThat(service.waitingIn(host, "!one-to-one:oriso")).isEmpty();
    assertThat(service.waitingIn(host, null)).isEmpty();
  }

  @Test
  void onlyAModeratorOfTheGroupMayLetIn() {
    var waiting = pending(LocalDateTime.now());
    when(consultantAccess.mayModerate(chat, host)).thenReturn(true);
    assertThat(service.mayAdmit(waiting, host)).isTrue();

    when(consultantAccess.mayModerate(chat, host)).thenReturn(false);
    assertThat(service.mayAdmit(waiting, host)).isFalse();
  }

  private GroupChatJoinRequest pending(LocalDateTime requestedAt) {
    return GroupChatJoinRequest.builder()
        .id(9L)
        .via(Via.WAITING_ROOM)
        .seriesId(CHAT_ID)
        .userId("asker-1")
        .medium("VIDEO")
        .status(Status.PENDING)
        .requestedAt(requestedAt)
        .waitingSeenAt(requestedAt)
        .build();
  }
}
