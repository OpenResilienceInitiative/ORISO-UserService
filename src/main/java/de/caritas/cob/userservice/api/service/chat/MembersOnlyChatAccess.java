package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one rule for a members-only chat (ORISO-UserService#1418): <b>current participation</b> is
 * decisive. Who may read it, list it, fetch its session, change who is in it or hand its case over
 * is answered by whether the counsellor <em>is</em> a participant now, and by nothing else: not the
 * counselling centre, not the Träger, not who the session is assigned to, not an active supervision
 * row, not an agency-admin role.
 *
 * <p>A members-only chat has two records, the {@code Chat} (the room) and the {@code Session} (its
 * technical case, assigned to the creator and not a team session). Both are answered here, so a
 * path that starts from either one cannot be forgotten. Every method answers "does this rule
 * refuse?", so a caller that does not know the chat — an ordinary chat, a one-to-one session — is
 * never affected.
 *
 * <p>The component reads the database on every call and keeps nothing in memory.
 */
@Component
@RequiredArgsConstructor
public class MembersOnlyChatAccess {

  private final ChatRepository chatRepository;
  private final GroupChatParticipantRepository participantRepository;

  /**
   * The one place for reading a members-only chat across Träger: its participants may work for
   * different Träger (recipient scope PLATFORM) and the tenant filter would hide the others from a
   * plain lookup. Call it only for data of a chat the caller was already admitted to, and only for
   * fields every participant sees; an ordinary chat is read exactly as before.
   */
  public <T> T readingAcrossTraeger(Chat chat, Supplier<T> read) {
    return concerns(chat) ? TenantContext.supplyAcrossTenants(read) : read.get();
  }

  /** Same for the technical session of a chat. */
  public <T> T readingAcrossTraeger(Session session, Supplier<T> read) {
    return TenantContext.supplyAcrossTenants(() -> concerns(session))
        ? TenantContext.supplyAcrossTenants(read)
        : read.get();
  }

  /** Same for a chat known by its id only. */
  public <T> T readingAcrossTraegerOfChat(Long chatId, Supplier<T> read) {
    var membersOnly =
        chatId != null && chatRepository.findById(chatId).map(Chat::isMembersOnly).orElse(false);
    return membersOnly ? TenantContext.supplyAcrossTenants(read) : read.get();
  }

  /** Whether the chat is a members-only chat. */
  public boolean concerns(Chat chat) {
    return chat != null && chat.isMembersOnly();
  }

  /** Whether the session is the technical case of a members-only chat. */
  public boolean concerns(Session session) {
    if (session == null
        || session.getMatrixRoomId() == null
        || session.getMatrixRoomId().isBlank()) {
      return false;
    }
    // A members-only chat is always an internal group chat; any other known type cannot be one.
    if (session.getConversationType() != null
        && session.getConversationType() != ConversationType.INTERNAL_GROUP) {
      return false;
    }
    return chatRepository.existsByMatrixRoomIdAndMembersOnlyTrue(session.getMatrixRoomId())
        || isOrphanedCase(session)
            && !chatRepository.existsByMatrixRoomId(session.getMatrixRoomId());
  }

  /**
   * The technical case of an internal chat that is no team session can only belong to a
   * members-only chat. When that chat is gone the case belongs to nobody: the rule fails closed and
   * does not fall back to who the session is assigned to.
   */
  private static boolean isOrphanedCase(Session session) {
    return session.getConversationType() == ConversationType.INTERNAL_GROUP
        && !session.isTeamSession();
  }

  /** Whether the counsellor takes part in the members-only chat right now. */
  public boolean isMember(Chat chat, String consultantId) {
    return chat != null
        && chat.getId() != null
        && consultantId != null
        && participantRepository
            .findBySeriesIdAndConsultantId(chat.getId(), consultantId)
            .isPresent();
  }

  /**
   * Whether the rule refuses the counsellor this chat: it is members-only and the counsellor is not
   * one of its participants.
   */
  public boolean excludes(Chat chat, Consultant consultant) {
    return concerns(chat) && (consultant == null || !isMember(chat, consultant.getId()));
  }

  /**
   * Whether the rule refuses the counsellor this session: it is the case of a members-only chat and
   * the counsellor is not one of its participants.
   */
  public boolean excludes(Session session, Consultant consultant) {
    return consultant != null && excludes(session, consultant.getId());
  }

  /**
   * Whether the session is the case of a members-only chat and the counsellor is one of its
   * participants. A participant admitted from another Träger is not its assigned advisor and is
   * invisible to the tenant filter; participation alone makes the session theirs.
   */
  public boolean admits(Session session, Consultant consultant) {
    return consultant != null && concerns(session) && isParticipantOf(session, consultant.getId());
  }

  /** Same as {@link #excludes(Session, Consultant)} for an account id. */
  public boolean excludes(Session session, String consultantId) {
    if (session == null || consultantId == null) {
      return false;
    }
    if (isParticipantOf(session, consultantId)) {
      return false;
    }
    return concerns(session);
  }

  /**
   * The sessions of {@code sessions} the rule refuses the counsellor, with one query for the
   * members-only chats and one for the counsellor's participations, whatever the list's length.
   */
  public Set<Long> excludedSessionIds(Collection<Session> sessions, Consultant consultant) {
    var membersOnly = membersOnlySessionIds(sessions);
    if (membersOnly.isEmpty() || consultant == null) {
      return membersOnly;
    }
    var memberOf =
        participantRepository.findByConsultantId(consultant.getId()).stream()
            .map(GroupChatParticipant::getChatId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    var excluded = new HashSet<>(membersOnly);
    excluded.removeAll(memberOf);
    return excluded;
  }

  /** The sessions of {@code sessions} that are the case of a members-only chat. */
  public Set<Long> membersOnlySessionIds(Collection<Session> sessions) {
    if (sessions == null || sessions.isEmpty()) {
      return Set.of();
    }
    var candidates =
        sessions.stream()
            .filter(Objects::nonNull)
            .filter(session -> session.getId() != null)
            .filter(
                session ->
                    session.getMatrixRoomId() != null && !session.getMatrixRoomId().isBlank())
            .filter(
                session ->
                    session.getConversationType() == null
                        || session.getConversationType() == ConversationType.INTERNAL_GROUP)
            .toList();
    if (candidates.isEmpty()) {
      return Set.of();
    }
    var roomIds =
        candidates.stream()
            .map(Session::getMatrixRoomId)
            .collect(Collectors.toCollection(HashSet::new));
    var membersOnlyRooms = chatRepository.findMembersOnlyMatrixRoomIds(roomIds);
    var possiblyOrphaned =
        candidates.stream()
            .filter(session -> !membersOnlyRooms.contains(session.getMatrixRoomId()))
            .filter(MembersOnlyChatAccess::isOrphanedCase)
            .map(Session::getMatrixRoomId)
            .collect(Collectors.toCollection(HashSet::new));
    var roomsWithAChat =
        possiblyOrphaned.isEmpty()
            ? Set.<String>of()
            : chatRepository.findExistingMatrixRoomIds(possiblyOrphaned);
    return candidates.stream()
        .filter(
            session ->
                membersOnlyRooms.contains(session.getMatrixRoomId())
                    || isOrphanedCase(session)
                        && !roomsWithAChat.contains(session.getMatrixRoomId()))
        .map(Session::getId)
        .collect(Collectors.toSet());
  }

  /** Refuses with 403 when {@link #excludes(Session, Consultant)} does. */
  public void requireAccess(Session session, Consultant consultant) {
    if (excludes(session, consultant)) {
      throw new ForbiddenException(
          String.format(
              "Consultant %s is not a participant of the members-only chat of session %s",
              consultant == null ? null : consultant.getId(), session.getId()));
    }
  }

  /** Same as {@link #requireAccess(Session, Consultant)} for an account id. */
  public void requireAccess(Session session, String consultantId) {
    if (excludes(session, consultantId)) {
      throw new ForbiddenException(
          String.format(
              "Consultant %s is not a participant of the members-only chat of session %s",
              consultantId, session.getId()));
    }
  }

  /** Refuses with 403 for the case of any members-only chat, whoever asks. */
  public void requireOrdinary(Session session, String reason) {
    if (concerns(session)) {
      throw new ForbiddenException(reason);
    }
  }

  private boolean isParticipantOf(Session session, String consultantId) {
    return session.getId() != null
        && consultantId != null
        && participantRepository.existsByChatIdAndConsultantId(session.getId(), consultantId);
  }
}
