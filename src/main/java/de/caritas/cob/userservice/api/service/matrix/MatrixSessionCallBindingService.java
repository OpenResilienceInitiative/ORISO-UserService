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
 *   <li>the caller created the call room and sent its binding state.
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
    String caller = counsellor.getMatrixUserId();

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
            .execute(status -> write(conversation, callId, mediaRoomId, caller, video, invitees));
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
      MatrixCallConversation conversation,
      String callId,
      String mediaRoomId,
      String caller,
      boolean video,
      Set<String> invitees) {
    if (!conversations.lockCurrentForWriting(conversation)) return Result.REFUSED;
    String sourceRoomId = conversation.getMatrixRoomId();
    var existing = bindings.findBySourceRoomIdAndCallId(sourceRoomId, callId).orElse(null);
    if (existing != null) {
      // Renewal of the same call by the same counsellor; anything else is never replaced.
      return mediaRoomId.equals(existing.getMediaRoomId())
              && caller.equals(existing.getCallerMatrixId())
              && Objects.equals(conversation.getSessionId(), existing.getSessionId())
              && Objects.equals(conversation.getTenantId(), existing.getTenantId())
          ? Result.EXISTING
          : Result.REFUSED;
    }
    if (bindings.findByMediaRoomId(mediaRoomId).isPresent()) return Result.REFUSED;
    long now = System.currentTimeMillis();
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
