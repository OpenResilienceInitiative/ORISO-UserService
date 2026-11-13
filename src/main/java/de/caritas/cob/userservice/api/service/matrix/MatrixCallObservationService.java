package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.MatrixCallObservation;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Observation-only registry for encrypted calls whose invite UserService cannot read: internal
 * groups (#2166/#1624) and 1:1 sessions (#2237). Feeds the RTC state lookup and the Activity
 * Timeline only. It must never be read by guest admission, share links, knocks or any other access
 * decision; those read {@code MatrixCallBinding} exclusively.
 */
@Service
@RequiredArgsConstructor
public class MatrixCallObservationService {
  /** A registration is live for this long unless the creator renews or ends it. */
  static final long LEASE_MILLIS = 10 * 60 * 1000L;

  public enum Outcome {
    OK,
    NOT_FOUND
  }

  private final MatrixCallObservationRepository observations;
  private final MatrixCallBindingRepository bindings;
  private final MatrixCallConversationResolver conversations;
  private final UserRepository users;
  private final ConsultantRepository consultants;
  private final MatrixSynapseService matrix;
  private final EventNotificationService notifications;
  private final MatrixCallLifecycleService lifecycle;

  @Transactional
  public Outcome register(
      AuthenticatedUser actor,
      String sourceRoomId,
      String callId,
      String mediaRoomId,
      boolean video) {
    if (blank(sourceRoomId, 255) || blank(callId, 191) || blank(mediaRoomId, 255)) {
      return Outcome.NOT_FOUND;
    }
    var conversation = conversations.resolveForWriting(sourceRoomId).orElse(null);
    // Exactly one source: an internal group (chat) or a 1:1 session (#2237). The conversation
    // type already enforces this; the explicit check keeps a row from ever naming both or neither.
    if (conversation == null
        || (conversation.getChatId() == null) == (conversation.getSessionId() == null)
        || actor.getTenantId() == null
        || !actor.getTenantId().equals(conversation.getTenantId())) {
      return Outcome.NOT_FOUND;
    }
    // A readable invite already produced the authoritative binding; notifications of both paths
    // share one dedupe key per (room, call), so an observation must never pre-empt that call.
    if (bindings.findBySourceRoomIdAndCallId(sourceRoomId, callId).isPresent()) {
      return Outcome.NOT_FOUND;
    }
    String creator = matrixIdOf(actor, conversation.getTenantId()).orElse(null);
    var members = matrix.getCallRoomMembers(sourceRoomId).orElse(null);
    if (creator == null || members == null || !members.contains(creator)) {
      return Outcome.NOT_FOUND;
    }
    var binding = matrix.getCallRoomBinding(mediaRoomId, creator).orElse(null);
    if (binding == null
        || !callId.equals(binding.get("call_id"))
        || !sourceRoomId.equals(binding.get("source_room_id"))) {
      return Outcome.NOT_FOUND;
    }
    var origin = matrix.getCallRoomOrigin(mediaRoomId, creator).orElse(null);
    if (origin == null
        || !creator.equals(origin.creatorMatrixId())
        || !creator.equals(origin.bindingSenderMatrixId())) {
      return Outcome.NOT_FOUND;
    }
    if (!conversations.lockCurrentForWriting(conversation)) return Outcome.NOT_FOUND;
    long now = System.currentTimeMillis();
    var existing =
        observations.findForUpdateBySourceRoomIdAndCallId(sourceRoomId, callId).orElse(null);
    if (existing != null) {
      if (!creator.equals(existing.getCreatorMatrixId())
          || !mediaRoomId.equals(existing.getMediaRoomId())
          || existing.getEndedAt() != null
          || now >= existing.getExpiresAt()) {
        return Outcome.NOT_FOUND;
      }
      existing.renew(now, now + LEASE_MILLIS);
      observations.save(existing);
      return Outcome.OK;
    }
    if (observations.findByMediaRoomId(mediaRoomId).isPresent()) return Outcome.NOT_FOUND;
    observations.saveAndFlush(
        MatrixCallObservation.builder()
            .origin(MatrixCallObservation.ORIGIN_CREATOR_REGISTRATION)
            .sourceRoomId(sourceRoomId)
            .callId(callId)
            .mediaRoomId(mediaRoomId)
            .creatorMatrixId(creator)
            .tenantId(conversation.getTenantId())
            .chatId(conversation.getChatId())
            .sessionId(conversation.getSessionId())
            .video(video)
            .registeredAt(now)
            .lastSeenAt(now)
            .expiresAt(now + LEASE_MILLIS)
            .build());
    // Verified evidence: creator is a current member, the media room binding and creator match.
    // Audience: current members of this exact room, same tenant, active accounts, not the creator.
    var creatorIdentity = identity(creator, conversation.getTenantId()).orElse(null);
    if (creatorIdentity != null) {
      for (String member : members) {
        if (member.equals(creator)) continue;
        var recipient = identity(member, conversation.getTenantId()).orElse(null);
        if (recipient == null || !lifecycle.isActiveRecipient(recipient.id())) continue;
        notifications.createCallInvitationNotification(
            conversation,
            recipient.id(),
            recipient.consultant(),
            creatorIdentity.id(),
            callId,
            mediaRoomId,
            video);
      }
    }
    return Outcome.OK;
  }

  /** Only the registering creator may end the registration. */
  @Transactional
  public Outcome end(AuthenticatedUser actor, String sourceRoomId, String callId) {
    if (blank(sourceRoomId, 255) || blank(callId, 191) || actor.getTenantId() == null) {
      return Outcome.NOT_FOUND;
    }
    var conversation = conversations.resolve(sourceRoomId).orElse(null);
    if (conversation == null || !actor.getTenantId().equals(conversation.getTenantId())) {
      return Outcome.NOT_FOUND;
    }
    String caller = matrixIdOf(actor, conversation.getTenantId()).orElse(null);
    var row = observations.findForUpdateBySourceRoomIdAndCallId(sourceRoomId, callId).orElse(null);
    if (caller == null
        || row == null
        || !caller.equals(row.getCreatorMatrixId())
        || !Objects.equals(row.getTenantId(), conversation.getTenantId())) {
      return Outcome.NOT_FOUND;
    }
    if (row.getEndedAt() == null) {
      // Unknown audience: fail before recording the end, so a retry can still notify.
      List<String> members =
          matrix
              .getCallRoomMembers(sourceRoomId)
              .orElseThrow(
                  () ->
                      new MatrixSynapseService.CallLookupUnavailableException(
                          "call audience unavailable"));
      long now = System.currentTimeMillis();
      row.finish(now);
      observations.save(row);
      for (String member : members) {
        var recipient = identity(member, row.getTenantId()).orElse(null);
        if (recipient == null || !lifecycle.isActiveRecipient(recipient.id())) continue;
        notifications.createCallEndedNotification(
            conversation,
            recipient.id(),
            recipient.consultant(),
            callId,
            row.getMediaRoomId(),
            row.getRegisteredAt(),
            row.getEndedAt());
      }
    }
    return Outcome.OK;
  }

  /** State for an authorized reader (conversation member). The caller checks membership. */
  Optional<MatrixCallStateService.CallState> readState(
      MatrixCallConversation conversation, String callId) {
    if ((conversation.getChatId() == null) == (conversation.getSessionId() == null)) {
      return Optional.empty();
    }
    // Exact source match on both ids: a session row is never readable through a chat that happens
    // to share its numeric id, nor through another session, and vice versa.
    return observations
        .findBySourceRoomIdAndCallId(conversation.getMatrixRoomId(), callId)
        .filter(row -> Objects.equals(row.getTenantId(), conversation.getTenantId()))
        .filter(row -> Objects.equals(row.getChatId(), conversation.getChatId()))
        .filter(row -> Objects.equals(row.getSessionId(), conversation.getSessionId()))
        .map(
            row -> {
              long now = System.currentTimeMillis();
              boolean live = row.isLive(now);
              Long ended =
                  live ? null : (row.getEndedAt() != null ? row.getEndedAt() : row.getExpiresAt());
              long duration = Math.max(0, (live ? now : ended) - row.getRegisteredAt()) / 1000;
              return new MatrixCallStateService.CallState(
                  row.getSourceRoomId(),
                  row.getCallId(),
                  row.getMediaRoomId(),
                  row.isVideo() ? "video" : "audio",
                  live ? "running" : "ended",
                  row.getRegisteredAt(),
                  row.getRegisteredAt(),
                  ended,
                  duration,
                  List.of());
            });
  }

  private Optional<String> matrixIdOf(AuthenticatedUser actor, Long tenantId) {
    if (actor.getUserId() == null) return Optional.empty();
    Optional<String> id =
        actor.isConsultant()
            ? consultants
                .findByIdAndDeleteDateIsNull(actor.getUserId())
                .filter(u -> Objects.equals(u.getTenantId(), tenantId))
                .map(u -> u.getMatrixUserId())
            : users
                .findByUserIdAndDeleteDateIsNull(actor.getUserId())
                .filter(u -> Objects.equals(u.getTenantId(), tenantId))
                .map(u -> u.getMatrixUserId());
    return id.filter(value -> !value.isBlank());
  }

  private Optional<Identity> identity(String matrixId, Long tenantId) {
    return users
        .findByMatrixUserIdAndDeleteDateIsNull(matrixId)
        .filter(u -> Objects.equals(u.getTenantId(), tenantId) && u.getUserId() != null)
        .map(u -> new Identity(u.getUserId(), false))
        .or(
            () ->
                consultants
                    .findByMatrixUserIdAndDeleteDateIsNull(matrixId)
                    .filter(c -> Objects.equals(c.getTenantId(), tenantId) && c.getId() != null)
                    .map(c -> new Identity(c.getId(), true)));
  }

  private static boolean blank(String value, int max) {
    return value == null || value.isBlank() || value.length() > max;
  }

  private record Identity(String id, boolean consultant) {}
}
