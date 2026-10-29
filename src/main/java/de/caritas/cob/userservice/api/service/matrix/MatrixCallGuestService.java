package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.MatrixCallGuest;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallGuestRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Second call entry path (US#1477): the counsellor's admission is the authorisation. The guest is
 * invited into the call room only; the source conversation is never consulted for the guest and
 * never joined. When the call ends, the guest's seat and any explicit power level are removed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatrixCallGuestService {
  private final MatrixCallBindingRepository bindings;
  private final MatrixCallGuestRepository guests;
  private final MatrixCallConversationResolver conversations;
  private final UserRepository users;
  private final ConsultantRepository consultants;
  private final MatrixSynapseService matrix;
  private final AccountInactivityService accountLifecycle;

  /** Failed removals after which every further failure is logged at ERROR for alerting. */
  public static final int REVOKE_ALERT_THRESHOLD = 10;

  public enum Outcome {
    ADMITTED,
    NOT_FOUND,
    ALREADY_PARTICIPANT,
    MATRIX_UNAVAILABLE
  }

  public record Admission(
      Outcome outcome, String callRoomId, String guestMatrixUserId, Long admittedAt) {
    static Admission of(Outcome outcome) {
      return new Admission(outcome, null, null, null);
    }
  }

  @Transactional
  public Admission admit(
      AuthenticatedUser actor, String sourceRoomId, String callId, String guestUserId) {
    if (!actor.isConsultant()
        || actor.getUserId() == null
        || actor.getTenantId() == null
        || blank(sourceRoomId)
        || blank(callId)
        || blank(guestUserId)) return Admission.of(Outcome.NOT_FOUND);
    Long tenant = actor.getTenantId();
    var conversation =
        conversations
            .resolve(sourceRoomId)
            .filter(value -> tenant.equals(value.getTenantId()))
            .orElse(null);
    String admitter =
        consultants
            .findByIdAndDeleteDateIsNull(actor.getUserId())
            .filter(value -> tenant.equals(value.getTenantId()))
            .map(value -> value.getMatrixUserId())
            .filter(value -> !blank(value))
            .orElse(null);
    if (conversation == null || admitter == null) return Admission.of(Outcome.NOT_FOUND);
    var members = matrix.getCallRoomMembers(sourceRoomId).orElse(null);
    if (members == null) return Admission.of(Outcome.MATRIX_UNAVAILABLE);
    // Only a counsellor who is in the conversation may open its call to somebody else.
    if (!members.contains(admitter)) return Admission.of(Outcome.NOT_FOUND);
    var binding =
        bindings
            .findBySourceRoomIdAndCallId(sourceRoomId, callId)
            .filter(
                value ->
                    Objects.equals(value.getTenantId(), conversation.getTenantId())
                        && Objects.equals(value.getSessionId(), conversation.getSessionId())
                        && Objects.equals(value.getChatId(), conversation.getChatId()))
            // Serialise with the lifecycle, so a call cannot end between the check and the invite.
            .flatMap(value -> bindings.lockByMediaRoomId(value.getMediaRoomId()))
            .filter(value -> value.getEndedAt() == null)
            .orElse(null);
    if (binding == null) return Admission.of(Outcome.NOT_FOUND);
    var guest = identity(guestUserId, tenant).orElse(null);
    if (guest == null || !isActive(guest.id())) return Admission.of(Outcome.NOT_FOUND);
    // A member of the conversation already has the normal join path; never record them as a
    // guest, or the end of the call would take a seat that belongs to the conversation.
    if (members.contains(guest.matrixId())) return Admission.of(Outcome.ALREADY_PARTICIPANT);
    var existing = guests.findByBindingIdAndMatrixUserId(binding.getId(), guest.matrixId());
    if (existing.isPresent() && existing.get().getRevokedAt() != null)
      return Admission.of(Outcome.NOT_FOUND);
    String token = matrix.loginAsUserAccessToken(admitter);
    if (token == null) return Admission.of(Outcome.MATRIX_UNAVAILABLE);
    // Seat first: a write that fails must never leave an invited guest the cleanup cannot find.
    var reserved =
        existing.isEmpty()
            ? guests.saveAndFlush(
                MatrixCallGuest.builder()
                    .bindingId(binding.getId())
                    .matrixUserId(guest.matrixId())
                    .admittedByMatrixId(admitter)
                    .admittedAt(System.currentTimeMillis())
                    .build())
            : null;
    try {
      matrix.inviteUserToRoom(binding.getMediaRoomId(), guest.matrixId(), token);
    } catch (MatrixInviteUserException failed) {
      // The invite may have landed anyway: only a confirmed leave or ban withdraws the seat; an
      // unreadable state keeps it, so the call-end cleanup still retracts whatever is there.
      var membership =
          matrix.getRoomMembership(binding.getMediaRoomId(), guest.matrixId(), token).orElse(null);
      if (!"join".equals(membership) && !"invite".equals(membership)) {
        if (reserved != null && ("leave".equals(membership) || "ban".equals(membership)))
          guests.delete(reserved);
        return Admission.of(Outcome.MATRIX_UNAVAILABLE);
      }
    }
    var row = reserved != null ? reserved : existing.get();
    return new Admission(
        Outcome.ADMITTED, binding.getMediaRoomId(), guest.matrixId(), row.getAdmittedAt());
  }

  /** True while the call runs and the person holds an unrevoked guest seat in it. */
  public boolean isActiveGuest(MatrixCallBinding binding, String matrixUserId) {
    return binding.getId() != null
        && binding.getEndedAt() == null
        && !blank(matrixUserId)
        && guests.existsByBindingIdAndMatrixUserIdAndRevokedAtIsNull(binding.getId(), matrixUserId);
  }

  /**
   * Takes every outstanding guest seat back once the call has ended. A removal that fails stays
   * outstanding and is retried with the next event the listener sees for the call room.
   */
  public void revokeOutstanding(MatrixCallBinding binding) {
    if (binding == null || binding.getEndedAt() == null || binding.getId() == null) return;
    List<MatrixCallGuest> outstanding = guests.findByBindingIdAndRevokedAtIsNull(binding.getId());
    for (MatrixCallGuest guest : outstanding) {
      String room = binding.getMediaRoomId();
      String guestId = guest.getMatrixUserId();
      // The caller first, then the admitting counsellor, whose token may hold the needed rights.
      String token = null;
      boolean gone = false;
      for (String moderator :
          java.util.stream.Stream.of(binding.getCallerMatrixId(), guest.getAdmittedByMatrixId())
              .filter(value -> !blank(value))
              .distinct()
              .toList()) {
        String candidate = matrix.loginAsUserAccessToken(moderator);
        if (candidate == null) continue;
        token = candidate;
        if (remove(room, guestId, candidate)) {
          gone = true;
          break;
        }
      }
      if (token == null) {
        deferRevocation(guest, room, "no moderator token");
      } else if (!gone) {
        deferRevocation(guest, room, "membership is not leave or ban");
      } else if (!matrix.clearUserPowerLevel(room, guestId, token)) {
        // A kept power level would apply again if the account were ever invited back.
        deferRevocation(guest, room, "power level not cleared");
      } else {
        guest.revoke(System.currentTimeMillis());
      }
    }
  }

  /**
   * The returned flags are not trusted: only the guest's m.room.member state proves removal. A
   * pending invite never shows in the joined list, so that list cannot prove it either.
   */
  private boolean remove(String room, String guestId, String token) {
    matrix.removeUserFromRoom(room, guestId, token);
    if (isGone(room, guestId, token)) return true;
    // Kick also retracts a pending invite.
    matrix.kickUserFromRoom(room, guestId, token);
    return isGone(room, guestId, token);
  }

  private void deferRevocation(MatrixCallGuest guest, String room, String reason) {
    guest.recordFailedRevokeAttempt(System.currentTimeMillis());
    if (guest.getRevokeAttempts() >= REVOKE_ALERT_THRESHOLD) {
      log.error(
          "CALL_GUEST_REVOCATION_STUCK call room {}: removal failed {} times ({})",
          room,
          guest.getRevokeAttempts(),
          reason);
    } else {
      log.warn("Call guest removal deferred for call room {} ({})", room, reason);
    }
  }

  /** Media rooms of ended calls that still hold an unrevoked guest seat (reconcile sweep). */
  public List<String> endedRoomsWithOutstandingGuests() {
    return guests.findEndedMediaRoomsWithOutstandingGuests(
        org.springframework.data.domain.PageRequest.of(0, 100));
  }

  private boolean isGone(String room, String matrixId, String token) {
    return matrix
        .getRoomMembership(room, matrixId, token)
        .map(membership -> "leave".equals(membership) || "ban".equals(membership))
        .orElse(false);
  }

  private boolean isActive(String identityId) {
    return accountLifecycle
        .snapshot(identityId)
        .map(account -> account.status() == AccountInactivityService.Status.ACTIVE)
        .orElse(true);
  }

  private Optional<Identity> identity(String id, Long tenant) {
    return users
        .findByUserIdAndDeleteDateIsNull(id)
        .filter(user -> tenant.equals(user.getTenantId()) && !blank(user.getMatrixUserId()))
        .map(user -> new Identity(user.getUserId(), user.getMatrixUserId()))
        .or(
            () ->
                consultants
                    .findByIdAndDeleteDateIsNull(id)
                    .filter(
                        user -> tenant.equals(user.getTenantId()) && !blank(user.getMatrixUserId()))
                    .map(user -> new Identity(user.getId(), user.getMatrixUserId())));
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private record Identity(String id, String matrixId) {}
}
