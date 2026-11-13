package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FE#2209: the authoritative {@link MatrixCallBinding} of an encrypted 1:1 session call.
 *
 * <p>A readable {@code m.call.invite} is what normally creates a binding ({@link
 * MatrixCallInviteNotificationService}). In a 1:1 session the invite is end-to-end encrypted, so
 * the server never sees it, and share links, knocks and guest seats (which read bindings only)
 * answered "call not found". Decision of 13 Nov 2026: a call that the session's own counsellor
 * registers from their browser, and that the server verifies against Matrix, is proof enough.
 *
 * <p>The authorization rule, all of it checked here and none of it taken from an observation row:
 *
 * <ul>
 *   <li>the caller is a counsellor of the conversation's Träger,
 *   <li>the conversation is a 1:1 session (never a group chat) that may take new calls,
 *   <li>the caller is that session's assigned counsellor ({@code session.consultant}),
 *   <li>the caller is a current member of the session room,
 *   <li>the call room's binding state names exactly this call and this session room, and
 *   <li>the caller created the call room and sent its binding state,
 *   <li>the call room is none of ORISO's own conversation rooms, and
 *   <li>the call was not already ended (#1641: the creator's end tombstone, an ended or lapsed
 *       registration), and assignment and account are checked again under the session lock.
 * </ul>
 *
 * Once written, the binding is the readable-invite binding in every respect: the lifecycle observes
 * the call room and ends it, and share links, knocks and seats follow it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatrixSessionCallBindingService {
  /**
   * Until the lifecycle sees somebody in the call room, the binding counts as live this long. Same
   * grace the observation lease gives a call nobody has connected to yet (#1628).
   */
  static final long START_GRACE_MILLIS = 2 * 60 * 1000L;

  private final MatrixCallConversationResolver conversations;
  private final SessionRepository sessions;
  private final ConsultantRepository consultants;
  private final UserRepository users;
  private final MatrixCallBindingRepository bindings;
  private final MatrixCallBindingService callBindings;
  private final MatrixSynapseService matrix;
  private final MatrixCallLifecycleService lifecycle;
  private final OrisoRoomRegistry rooms;
  private final de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository
      observations;
  private final PlatformTransactionManager transactionManager;

  /**
   * Creates the binding, or confirms the one this counsellor already has for this call. {@code
   * false} for anybody and anything else; never replaces another binding.
   */
  public boolean bind(
      AuthenticatedUser actor,
      String sourceRoomId,
      String callId,
      String mediaRoomId,
      boolean video) {
    if (actor == null
        || !actor.isConsultant()
        || actor.getUserId() == null
        || actor.getTenantId() == null
        || blank(sourceRoomId, 255)
        || blank(callId, 191)
        || blank(mediaRoomId, 255)
        || sourceRoomId.equals(mediaRoomId)) {
      return false;
    }
    var conversation = conversations.resolveForWriting(sourceRoomId).orElse(null);
    if (conversation == null
        || conversation.getSessionId() == null
        || conversation.getChatId() != null
        || !actor.getTenantId().equals(conversation.getTenantId())) {
      return false;
    }
    var counsellor = assignedCounsellor(actor, conversation).orElse(null);
    if (counsellor == null) return false;
    // A suspended account keeps its rows and Matrix membership; it must not bind a call. Same check
    // the readable-invite path applies to its caller (MatrixCallInviteNotificationService).
    if (!lifecycle.isActiveRecipient(counsellor.getId())) return false;
    String caller = counsellor.getMatrixUserId();
    // A renewal of this counsellor's live binding needs no Matrix lookup; an ended or foreign
    // binding is never confirmed or replaced.
    var known = bindings.findBySourceRoomIdAndCallId(sourceRoomId, callId).orElse(null);
    if (known != null) {
      return sameCall(known, conversation, mediaRoomId, caller)
          && known.getEndedAt() == null
          && known.isLive(System.currentTimeMillis());
    }
    // Only a dedicated call room can be bound: never one of ORISO's own conversation rooms, or a
    // counsellor could bind one session's call to another session's room.
    if (rooms.isKnownConversationRoom(mediaRoomId)) return false;

    // Matrix evidence first, with no transaction open (#1630).
    var members = matrix.getCallRoomMembers(sourceRoomId).orElse(null);
    if (members == null || !members.contains(caller)) return false;
    var roomBinding = matrix.getCallRoomBinding(mediaRoomId, caller).orElse(null);
    if (roomBinding == null
        || !callId.equals(roomBinding.get("call_id"))
        || !sourceRoomId.equals(roomBinding.get("source_room_id"))) {
      return false;
    }
    var origin = matrix.getCallRoomOrigin(mediaRoomId, caller).orElse(null);
    if (origin == null
        || !caller.equals(origin.creatorMatrixId())
        || !caller.equals(origin.bindingSenderMatrixId())) {
      return false;
    }
    Set<String> invitees = invitees(members, caller, conversation.getTenantId());

    Result written =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> write(actor, conversation, callId, mediaRoomId, caller, video, invitees));
    if (written == Result.REFUSED) return false;
    if (written == Result.CREATED) observe(mediaRoomId, caller);
    return true;
  }

  private enum Result {
    CREATED,
    EXISTING,
    REFUSED
  }

  private Result write(
      AuthenticatedUser actor,
      MatrixCallConversation conversation,
      String callId,
      String mediaRoomId,
      String caller,
      boolean video,
      Set<String> invitees) {
    if (!conversations.lockCurrentForWriting(conversation)) return Result.REFUSED;
    // Under the session's call lock: the assignment and the account may have changed while Matrix
    // was asked.
    if (assignedCounsellor(actor, conversation).isEmpty()
        || !lifecycle.isActiveRecipient(actor.getUserId())) {
      return Result.REFUSED;
    }
    String sourceRoomId = conversation.getMatrixRoomId();
    long now = System.currentTimeMillis();
    var existing = bindings.findBySourceRoomIdAndCallId(sourceRoomId, callId).orElse(null);
    if (existing != null) {
      // Renewal of the same live call by the same counsellor; anything else is never replaced.
      return sameCall(existing, conversation, mediaRoomId, caller)
              && existing.getEndedAt() == null
              && existing.isLive(now)
          ? Result.EXISTING
          : Result.REFUSED;
    }
    if (bindings.findByMediaRoomId(mediaRoomId).isPresent()) return Result.REFUSED;
    // #1641 "ending wins": a call this counsellor already ended, or whose registration lease ran
    // out, is never bound by a late registration. Deny-only: the row grants nothing.
    if (endedByObservation(sourceRoomId, callId, caller, now)) return Result.REFUSED;
    var proposed =
        MatrixCallBinding.builder()
            .sourceRoomId(sourceRoomId)
            .callId(callId)
            .mediaRoomId(mediaRoomId)
            .callerMatrixId(caller)
            .sessionId(conversation.getSessionId())
            .chatId(null)
            .tenantId(conversation.getTenantId())
            .invitedAt(now)
            .inviteExpiresAt(now + START_GRACE_MILLIS)
            .video(video)
            .invitedMatrixIds(invitees)
            .build();
    return callBindings.registerWhileSourceLocked(proposed) ? Result.CREATED : Result.REFUSED;
  }

  private boolean endedByObservation(String sourceRoomId, String callId, String caller, long now) {
    var row = observations.findBySourceRoomIdAndCallId(sourceRoomId, callId).orElse(null);
    if (row == null) return false;
    if (row.isTombstone()) return caller.equals(row.getCreatorMatrixId());
    return row.getEndedAt() != null || now >= row.getExpiresAt();
  }

  private static boolean sameCall(
      MatrixCallBinding binding,
      MatrixCallConversation conversation,
      String mediaRoomId,
      String caller) {
    return mediaRoomId.equals(binding.getMediaRoomId())
        && caller.equals(binding.getCallerMatrixId())
        && Objects.equals(conversation.getSessionId(), binding.getSessionId())
        && binding.getChatId() == null
        && Objects.equals(conversation.getTenantId(), binding.getTenantId());
  }

  /** The session's assigned counsellor, and only when that is the caller. */
  private java.util.Optional<Consultant> assignedCounsellor(
      AuthenticatedUser actor, MatrixCallConversation conversation) {
    var assignedId =
        sessions
            .findById(conversation.getSessionId())
            .map(session -> session.getConsultant())
            .map(Consultant::getId)
            .orElse(null);
    if (assignedId == null || !assignedId.equals(actor.getUserId())) {
      return java.util.Optional.empty();
    }
    return consultants
        .findByIdAndDeleteDateIsNull(actor.getUserId())
        .filter(value -> conversation.getTenantId().equals(value.getTenantId()))
        .filter(value -> !blank(value.getMatrixUserId(), 255));
  }

  /** The other people of this session, from live membership, as the invite path does. */
  private Set<String> invitees(List<String> members, String caller, Long tenantId) {
    return members.stream()
        .filter(member -> !member.equals(caller))
        .filter(member -> isTenantPerson(member, tenantId))
        .collect(Collectors.toSet());
  }

  private boolean isTenantPerson(String matrixId, Long tenantId) {
    return users
            .findByMatrixUserIdAndDeleteDateIsNull(matrixId)
            .filter(value -> Objects.equals(value.getTenantId(), tenantId))
            .isPresent()
        || consultants
            .findByMatrixUserIdAndDeleteDateIsNull(matrixId)
            .filter(value -> Objects.equals(value.getTenantId(), tenantId))
            .isPresent();
  }

  /**
   * After the commit: let the lifecycle observe the call room, exactly as for a readable invite.
   */
  private void observe(String mediaRoomId, String caller) {
    try {
      matrix.ensureAdminInRoom(mediaRoomId, caller);
    } catch (RuntimeException unavailable) {
      // The committed binding is the recovery record; the reconcile loop retries the observer.
      log.warn("Call observer join unavailable ({})", unavailable.getClass().getSimpleName());
    }
  }

  private static boolean blank(String value, int max) {
    return value == null || value.isBlank() || value.length() > max;
  }
}
