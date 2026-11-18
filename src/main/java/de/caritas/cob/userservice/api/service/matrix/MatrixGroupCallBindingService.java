package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationEndRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.chat.GroupChatConsultantAccess;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #1662 (Frank, 17 Nov): group calls offer the guest link like 1:1 calls. The share link reads only
 * {@link MatrixCallBinding}s, and in an end-to-end encrypted group chat the server never reads the
 * call invite that normally creates one ({@link MatrixCallInviteNotificationService}), so every
 * share-link request answered "call not found". The counsellor who started the call registers it
 * from the browser ({@code POST /matrix/calls/observations}, the request the Frontend already sends
 * for encrypted group calls), and the server checks that claim on its own, as {@link
 * MatrixSessionCallBindingService} does for a 1:1 session:
 *
 * <ul>
 *   <li>the caller is an active counsellor of the conversation's Träger, whose Matrix id is theirs
 *       alone in the Träger ({@link SoleCounsellor}),
 *   <li>the conversation is a group chat, and the caller may moderate it ({@link
 *       GroupChatConsultantAccess#mayModerate}: Owner or Co-Moderator, or a same-Träger colleague
 *       of its Beratungsstelle; never a plain member admitted from elsewhere),
 *   <li>the caller is a current member of the group room,
 *   <li>the call room's binding state names exactly this call and this group room, and the caller
 *       created the call room and sent that state,
 *   <li>the call room is none of ORISO's own conversation rooms, and
 *   <li>the caller has not already ended the call; the moderator rule is read again inside the
 *       transaction.
 * </ul>
 *
 * Nothing is taken over: an existing binding of the call is confirmed only when it is the caller's
 * own live binding of the same call room. Lock order as on the session path: (binding,) the
 * conversation's call lock (none for a group), then observation and end rows; nothing here locks a
 * share link or a knock.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatrixGroupCallBindingService {
  /** Same grace as the 1:1 path: a call nobody has connected to yet counts as live this long. */
  static final long START_GRACE_MILLIS = MatrixSessionCallBindingService.START_GRACE_MILLIS;

  private final MatrixCallConversationResolver conversations;
  private final ChatRepository chats;
  private final ConsultantRepository consultants;
  private final UserRepository users;
  private final MatrixCallBindingRepository bindings;
  private final MatrixCallBindingService callBindings;
  private final MatrixSynapseService matrix;
  private final MatrixCallLifecycleService lifecycle;
  private final DedicatedCallRoom callRoom;
  private final GroupChatConsultantAccess groupAccess;
  private final MatrixCallObservationRepository observations;
  private final MatrixCallObservationEndRepository ends;
  private final PlatformTransactionManager transactionManager;

  private enum Result {
    CREATED,
    EXISTING,
    REFUSED
  }

  /**
   * Creates the group call's binding, or confirms the caller's own live one. {@code false} for
   * anybody and anything else (including every 1:1 session).
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
        || conversation.getChatId() == null
        || conversation.getSessionId() != null
        || !actor.getTenantId().equals(conversation.getTenantId())) {
      return false;
    }
    // The moderator rule reads the group's and the counsellor's Beratungsstellen (lazy rows; no
    // open-in-view), so it runs in a short read-only transaction, still before any Matrix call.
    var readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);
    var moderator = readOnly.execute(status -> moderator(actor, conversation).orElse(null));
    if (moderator == null) return false;
    String caller = moderator.getMatrixUserId();
    var known = bindings.findBySourceRoomIdAndCallId(sourceRoomId, callId).orElse(null);
    if (known != null) return confirms(known, conversation, mediaRoomId, caller);
    if (callRoom.isConversationRoom(sourceRoomId, mediaRoomId)) return false;

    // Matrix evidence first, with no transaction open (#1630).
    var members = matrix.getCallRoomMembers(sourceRoomId).orElse(null);
    if (members == null || !members.contains(caller)) return false;
    if (!callRoom.isBoundBy(sourceRoomId, callId, mediaRoomId, caller)) return false;
    Set<String> invitees =
        CallInvitees.of(users, consultants, members, caller, conversation.getTenantId());

    Result written =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> write(actor, conversation, callId, mediaRoomId, caller, video, invitees));
    if (written == Result.REFUSED) return false;
    if (written == Result.CREATED) observe(mediaRoomId, caller);
    return true;
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
    // The moderator rule and the account may have changed while Matrix was asked.
    if (moderator(actor, conversation).isEmpty()) return Result.REFUSED;
    String sourceRoomId = conversation.getMatrixRoomId();
    long now = System.currentTimeMillis();
    var existing = bindings.findBySourceRoomIdAndCallId(sourceRoomId, callId).orElse(null);
    if (existing != null) {
      return confirms(existing, conversation, mediaRoomId, caller)
          ? Result.EXISTING
          : Result.REFUSED;
    }
    if (bindings.findByMediaRoomId(mediaRoomId).isPresent()) return Result.REFUSED;
    // #1641 "ending wins": a call this counsellor already ended, or whose own registration ended or
    // lapsed, is never bound by a late registration.
    if (endedByCaller(sourceRoomId, callId, caller, now)) return Result.REFUSED;
    var proposed =
        MatrixCallBinding.builder()
            .sourceRoomId(sourceRoomId)
            .callId(callId)
            .mediaRoomId(mediaRoomId)
            .callerMatrixId(caller)
            .sessionId(null)
            .chatId(conversation.getChatId())
            .tenantId(conversation.getTenantId())
            .invitedAt(now)
            .inviteExpiresAt(now + START_GRACE_MILLIS)
            .video(video)
            .invitedMatrixIds(invitees)
            .build();
    return callBindings.registerWhileSourceLocked(proposed) ? Result.CREATED : Result.REFUSED;
  }

  /** The caller's own live binding of exactly this call room of this group. */
  private boolean confirms(
      MatrixCallBinding binding,
      MatrixCallConversation conversation,
      String mediaRoomId,
      String caller) {
    return mediaRoomId.equals(binding.getMediaRoomId())
        && caller.equals(binding.getCallerMatrixId())
        && binding.getSessionId() == null
        && Objects.equals(conversation.getChatId(), binding.getChatId())
        && Objects.equals(conversation.getTenantId(), binding.getTenantId())
        && binding.getEndedAt() == null
        && binding.isLive(System.currentTimeMillis());
  }

  private boolean endedByCaller(String sourceRoomId, String callId, String caller, long now) {
    var row = observations.findForUpdateBySourceRoomIdAndCallId(sourceRoomId, callId).orElse(null);
    if (ends.findForUpdateBySourceRoomIdAndCallIdAndActorMatrixId(sourceRoomId, callId, caller)
        .isPresent()) {
      return true;
    }
    return row != null
        && caller.equals(row.getCreatorMatrixId())
        && (row.getEndedAt() != null || now >= row.getExpiresAt());
  }

  /**
   * The caller as a counsellor who may moderate the group: not deleted, of the conversation's
   * Träger, active, with a Matrix id that is theirs alone in the Träger.
   */
  private Optional<Consultant> moderator(
      AuthenticatedUser actor, MatrixCallConversation conversation) {
    Long tenant = conversation.getTenantId();
    var consultant =
        consultants
            .findByIdAndDeleteDateIsNull(actor.getUserId())
            .filter(value -> tenant.equals(value.getTenantId()))
            .filter(value -> !blank(value.getMatrixUserId(), 255))
            .orElse(null);
    if (consultant == null) return Optional.empty();
    if (!SoleCounsellor.is(consultants, consultant.getMatrixUserId(), tenant, consultant.getId())) {
      return Optional.empty();
    }
    if (!lifecycle.isActiveRecipient(consultant.getId())) return Optional.empty();
    Chat chat = chats.findById(conversation.getChatId()).orElse(null);
    if (chat == null || !groupAccess.mayModerate(chat, consultant)) return Optional.empty();
    return Optional.of(consultant);
  }

  /** After the commit: the lifecycle observes the call room, as for a readable invite. */
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
