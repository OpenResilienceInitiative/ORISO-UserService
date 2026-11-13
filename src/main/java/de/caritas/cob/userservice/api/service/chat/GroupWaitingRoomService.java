package de.caritas.cob.userservice.api.service.chat;

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
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who sits in a group's waiting room (ORISO-Frontend#2237, Frank 2026-11-12): an advice seeker who
 * gave consent waits; a moderator in the group's running call sees them in the call's knock list
 * and lets them in with one click. The entry grants nothing: once let in, the person's own page
 * joins the group as before ({@code PUT /users/chat/{chatId}/join}) and goes straight into the
 * call.
 *
 * <p>The entries share the call-knock queue ({@link Via#WAITING_ROOM}), so the call's existing
 * "Reinlassen" / "Alle reinlassen" admit them; see {@code DirectCallKnockService}.
 */
@Service
@RequiredArgsConstructor
public class GroupWaitingRoomService {

  /** The waiting page says "still here" every few seconds; older entries left the page. */
  static final long SEEN_SECONDS = 45;

  /** An admission the waiting page has not picked up yet is handed back on its next call. */
  static final long ADMISSION_HANDOVER_MINUTES = 10;

  private final @NonNull GroupChatJoinRequestRepository repository;
  private final @NonNull ChatRepository chats;
  private final @NonNull ChatMatrixRoomRepository occurrenceRooms;
  private final @NonNull ChatPermissionVerifier permissions;
  private final @NonNull GroupChatConsultantAccess consultantAccess;

  /**
   * "I am waiting": one open entry per person and group, marked seen on every call. A fresh
   * admission is returned as it is, so the page that asks again still goes in.
   */
  @Transactional
  public GroupChatJoinRequest waitIn(Long chatId, User user) {
    var chat =
        chats
            .findById(chatId)
            .orElseThrow(() -> new NotFoundException("Chat %s not found", chatId));
    permissions.verifyPermissionForChat(chat);
    var now = LocalDateTime.now();
    var latest =
        repository.findFirstBySeriesIdAndUserIdAndViaOrderByIdDesc(
            chatId, user.getUserId(), Via.WAITING_ROOM);
    if (latest.filter(this::freshAdmission).isPresent()) {
      return latest.get();
    }
    var open = latest.filter(GroupChatJoinRequest::isPending);
    if (open.isPresent()) {
      open.get().setWaitingSeenAt(now);
      return repository.save(open.get());
    }
    return repository.save(
        GroupChatJoinRequest.builder()
            .via(Via.WAITING_ROOM)
            .seriesId(chatId)
            .userId(user.getUserId())
            .medium(mediumOf(chat))
            .status(Status.PENDING)
            .requestedAt(now)
            .waitingSeenAt(now)
            .build());
  }

  /**
   * Who is still waiting for the group whose (occurrence) room this is; empty for anything else.
   */
  public List<GroupChatJoinRequest> waitingIn(Consultant consultant, String matrixRoomId) {
    return groupOf(matrixRoomId)
        .filter(chat -> consultantAccess.mayModerate(chat, consultant))
        .map(
            chat ->
                repository.findBySeriesIdAndViaAndStatusAndWaitingSeenAtAfter(
                    chat.getId(),
                    Via.WAITING_ROOM,
                    Status.PENDING,
                    LocalDateTime.now().minusSeconds(SEEN_SECONDS)))
        .orElse(List.of());
  }

  /**
   * The same list, locked in one query, for "Alle einlassen". Nothing in the calling transaction
   * has read these rows before, so the lock returns their committed state.
   */
  public List<GroupChatJoinRequest> waitingInForUpdate(Consultant consultant, String matrixRoomId) {
    return groupOf(matrixRoomId)
        .filter(chat -> consultantAccess.mayModerate(chat, consultant))
        .map(
            chat ->
                repository.findWaitingForUpdate(
                    chat.getId(),
                    Via.WAITING_ROOM,
                    Status.PENDING,
                    LocalDateTime.now().minusSeconds(SEEN_SECONDS)))
        .orElse(List.of());
  }

  /** Owner and Co-Moderators of the group, as for starting it. */
  public boolean mayAdmit(GroupChatJoinRequest entry, Consultant consultant) {
    return entry.getSeriesId() != null
        && chats
            .findById(entry.getSeriesId())
            .map(chat -> consultantAccess.mayModerate(chat, consultant))
            .orElse(false);
  }

  private Optional<Chat> groupOf(String matrixRoomId) {
    if (matrixRoomId == null || matrixRoomId.isBlank()) {
      return Optional.empty();
    }
    return chats
        .findByMatrixRoomId(matrixRoomId)
        .or(() -> occurrenceRooms.findByRoomId(matrixRoomId).map(ChatMatrixRoom::getSeries));
  }

  private boolean freshAdmission(GroupChatJoinRequest entry) {
    return entry.getStatus() == Status.ADMITTED
        && entry.getDecidedAt() != null
        && entry
            .getDecidedAt()
            .isAfter(LocalDateTime.now().minusMinutes(ADMISSION_HANDOVER_MINUTES));
  }

  private static String mediumOf(Chat chat) {
    return chat.getChatModality() == ChatModality.AUDIO ? "AUDIO" : "VIDEO";
  }
}
