package de.caritas.cob.userservice.api.service.matrixgroup;

import static de.caritas.cob.userservice.api.service.matrixgroup.MatrixGroupParticipationHistory.unavailable;

import de.caritas.cob.userservice.api.facade.ChatConverter;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatMatrixRoom;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.ChatMatrixRoomRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Pure persisted Series/room provenance, without Matrix calls or group locks. */
@Service
@RequiredArgsConstructor
public class ChatMatrixRoomHistory {
  private final ChatRepository chats;
  private final ChatMatrixRoomRepository rooms;

  public record Context(
      Chat series, boolean applicable, List<String> roomIds, boolean historyComplete) {}

  @Transactional(readOnly = true)
  public Optional<Context> context(String room) {
    try {
      return TenantContext.supplyAcrossTenants(() -> resolve(room));
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private Optional<Context> resolve(String room) {
    if (!GroupMatrixPolicySettings.validRoom(room)) throw unavailable();
    var current =
        chats.findByMatrixRoomIdIn(Set.of(room)).stream()
            .filter(c -> room.equals(c.getMatrixRoomId()))
            .toList();
    if (current.size() > 1) throw unavailable();
    var historical = rooms.findById(room);
    if (current.isEmpty() && historical.isEmpty()) return Optional.empty();
    var chat = current.isEmpty() ? historical.orElseThrow().getSeries() : current.getFirst();
    if (chat.getId() == null
        || chat.getChatOwner() == null
        || chat.getChatOwner().getTenantId() == null
        || chat.getChatOwner().getTenantId() <= 0
        || chat.getCurrentOccurrenceIndex() < 0
        || !GroupMatrixPolicySettings.validRoom(chat.getMatrixRoomId())) throw unavailable();
    if (historical.isPresent() && !historical.get().getSeries().getId().equals(chat.getId()))
      throw unavailable();
    if (ChatConverter.conversationTypeOf(chat) != ConversationType.SELF_HELP)
      return Optional.of(new Context(chat, false, List.of(), true));
    var provenance = new HashMap<Integer, String>();
    for (var known : rooms.findBySeries_IdOrderByOccurrenceIndexAsc(chat.getId())) {
      if (!known.getSeries().getId().equals(chat.getId())
          || !chat.getChatOwner().getTenantId().equals(known.getOwnerTenantId())
          || known.getOccurrenceIndex() < 0
          || known.getOccurrenceIndex() > (long) chat.getCurrentOccurrenceIndex() + 1
          || !GroupMatrixPolicySettings.validRoom(known.getRoomId())
          || provenance.put(known.getOccurrenceIndex(), known.getRoomId()) != null)
        throw unavailable();
    }
    var persistedCurrent = provenance.put(chat.getCurrentOccurrenceIndex(), chat.getMatrixRoomId());
    if (persistedCurrent != null && !persistedCurrent.equals(chat.getMatrixRoomId()))
      throw unavailable();
    if (Set.copyOf(provenance.values()).size() != provenance.size()) throw unavailable();
    var targetIndex =
        historical.map(ChatMatrixRoom::getOccurrenceIndex).orElse(chat.getCurrentOccurrenceIndex());
    if (!room.equals(provenance.get(targetIndex))) throw unavailable();
    var selected = new ArrayList<String>();
    selected.add(room);
    provenance.entrySet().stream()
        .filter(e -> e.getKey() < targetIndex)
        .sorted(Map.Entry.comparingByKey())
        .map(Map.Entry::getValue)
        .forEach(selected::add);
    if (selected.size() > 512) throw unavailable();
    for (var mapped : chats.findByMatrixRoomIdIn(Set.copyOf(selected)))
      if (selected.contains(mapped.getMatrixRoomId()) && !chat.getId().equals(mapped.getId()))
        throw unavailable();
    return Optional.of(
        new Context(chat, true, List.copyOf(selected), selected.size() == (long) targetIndex + 1));
  }

  @Transactional(readOnly = true)
  public Optional<String> preparedNextRoom(Chat chat) {
    if (ChatConverter.conversationTypeOf(chat) != ConversationType.SELF_HELP)
      return Optional.empty();
    var next =
        rooms.findBySeries_IdOrderByOccurrenceIndexAsc(chat.getId()).stream()
            .filter(r -> r.getOccurrenceIndex() == (long) chat.getCurrentOccurrenceIndex() + 1)
            .toList();
    if (next.size() > 1) throw unavailable();
    if (next.isEmpty()) return Optional.empty();
    var room = next.getFirst().getRoomId();
    var bound = context(room).orElseThrow(MatrixGroupParticipationHistory::unavailable);
    if (!bound.applicable()
        || !chat.getId().equals(bound.series().getId())
        || !chat.getMatrixRoomId().equals(bound.series().getMatrixRoomId())) throw unavailable();
    return Optional.of(room);
  }

  /** Commit provenance before an ordinary native JOIN can occur in the prepared room. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String reserveNextRoom(Chat snapshot, String candidate) {
    var chat =
        chats
            .findByIdForUpdate(snapshot.getId())
            .orElseThrow(MatrixGroupParticipationHistory::unavailable);
    if (!chat.getMatrixRoomId().equals(snapshot.getMatrixRoomId())
        || chat.getCurrentOccurrenceIndex() != snapshot.getCurrentOccurrenceIndex()
        || !chat.getChatOwner().getTenantId().equals(snapshot.getChatOwner().getTenantId()))
      throw unavailable();
    var previous = preparedNextRoom(chat);
    if (previous.isPresent()) return previous.get();
    recordRotation(chat, candidate);
    return candidate;
  }

  @Transactional(readOnly = true)
  public List<String> allRecordedRooms(Chat chat) {
    if (!GroupMatrixPolicySettings.validRoom(chat.getMatrixRoomId())
        && rooms.findBySeries_IdOrderByOccurrenceIndexAsc(chat.getId()).isEmpty()) return List.of();
    var bound =
        context(chat.getMatrixRoomId()).orElseThrow(MatrixGroupParticipationHistory::unavailable);
    if (!chat.getId().equals(bound.series().getId())) throw unavailable();
    var result = new LinkedHashSet<>(bound.roomIds());
    preparedNextRoom(chat).ifPresent(result::add);
    return List.copyOf(result);
  }

  /** Called in the same transaction that advances the canonical Chat room. */
  @Transactional
  public void recordRotation(Chat chat, String nextRoom) {
    if (ChatConverter.conversationTypeOf(chat) != ConversationType.SELF_HELP) return;
    if (chat.getId() == null
        || chat.getChatOwner() == null
        || chat.getChatOwner().getTenantId() == null
        || chat.getChatOwner().getTenantId() <= 0
        || !GroupMatrixPolicySettings.validRoom(chat.getMatrixRoomId())
        || !GroupMatrixPolicySettings.validRoom(nextRoom)
        || chat.getMatrixRoomId().equals(nextRoom)) throw unavailable();
    var committed =
        chats
            .findByIdForUpdate(chat.getId())
            .orElseThrow(MatrixGroupParticipationHistory::unavailable);
    boolean unchanged =
        committed.getCurrentOccurrenceIndex() == chat.getCurrentOccurrenceIndex()
            && committed.getMatrixRoomId().equals(chat.getMatrixRoomId());
    boolean alreadyAdvanced =
        committed.getCurrentOccurrenceIndex() == (long) chat.getCurrentOccurrenceIndex() + 1
            && committed.getMatrixRoomId().equals(nextRoom);
    if ((!unchanged && !alreadyAdvanced)
        || !committed.getChatOwner().getTenantId().equals(chat.getChatOwner().getTenantId()))
      throw unavailable();
    record(chat, chat.getMatrixRoomId(), chat.getCurrentOccurrenceIndex());
    record(chat, nextRoom, Math.addExact(chat.getCurrentOccurrenceIndex(), 1));
  }

  private void record(Chat chat, String room, int occurrence) {
    var existing = rooms.findById(room);
    if (existing.isPresent()) {
      var bound = existing.get();
      if (!room.equals(bound.getRoomId())
          || !chat.getId().equals(bound.getSeries().getId())
          || bound.getOccurrenceIndex() != occurrence
          || !chat.getChatOwner().getTenantId().equals(bound.getOwnerTenantId()))
        throw unavailable();
    } else {
      var current =
          TenantContext.supplyAcrossTenants(() -> chats.findByMatrixRoomIdIn(Set.of(room)));
      if (current.stream()
          .anyMatch(c -> room.equals(c.getMatrixRoomId()) && !chat.getId().equals(c.getId())))
        throw unavailable();
      rooms.save(new ChatMatrixRoom(room, chat, occurrence, chat.getChatOwner().getTenantId()));
    }
  }
}
