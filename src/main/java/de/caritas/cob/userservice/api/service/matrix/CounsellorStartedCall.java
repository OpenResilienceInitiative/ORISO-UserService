package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Frank, 17 Nov 2026: only counsellors start calls. Advice seekers and guests may only join a call
 * a counsellor started, and only while a counsellor of the Träger is in it right now (the rule
 * guest seats in 1:1 calls already follow). In 1:1 sessions and group chats alike. The media gate
 * asks this component before it lets a non-counsellor into a call room.
 *
 * <p><b>Start versus join</b> is decided from server-side records and Matrix state only, never from
 * a client flag. A call room holds a <em>joinable counsellor call</em> of a conversation when:
 *
 * <ul>
 *   <li>its {@code MatrixCallBinding} (readable invite, or the assigned counsellor's verified
 *       registration of a 1:1 call) names this conversation and Träger, is not ended, its caller is
 *       an active counsellor, the call room is observed, and an active counsellor of the Träger
 *       holds a live MatrixRTC membership in it ({@link
 *       de.caritas.cob.userservice.api.model.MatrixCallBinding#isInCall}); when a binding exists it
 *       alone decides;
 *   <li>else its {@code MatrixCallObservation} (an encrypted group or 1:1 call registered by its
 *       creator, verified against Matrix: the creator made the call room and wrote its binding
 *       state) names this conversation and Träger, is live, its creator is an active counsellor,
 *       and the call room's current Matrix state holds a live MatrixRTC membership of an active
 *       counsellor of the Träger.
 * </ul>
 *
 * Anything else is a start (or a call no counsellor is in any more). Both records are written only
 * by the server after it checked Matrix, so an advice seeker cannot make their own call room count.
 * The check takes no lock, so it cannot take part in the binding, session, observation lock order;
 * and no database transaction stays open while Matrix is asked (#1630). Matrix unavailable means no
 * join (fail closed).
 */
@Component
@RequiredArgsConstructor
public class CounsellorStartedCall {
  private final MatrixCallBindingRepository bindings;
  private final MatrixCallObservationRepository observations;
  private final ConsultantRepository consultants;
  private final AccountInactivityService accountLifecycle;
  private final de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService matrix;

  /**
   * Whether {@code matrixUserId} stands for exactly one not deleted counsellor of {@code tenantId}
   * (the {@link SoleCounsellor} rule). A counsellor of another Träger is no counsellor here.
   */
  @Transactional(readOnly = true)
  public boolean isCounsellor(String matrixUserId, Long tenantId) {
    return tenantId != null && SoleCounsellor.of(consultants, matrixUserId, tenantId).isPresent();
  }

  /**
   * Whether {@code callRoomId} holds a call a counsellor started in {@code sourceRoomId} with a
   * counsellor in it right now. Not transactional: Matrix may be asked (#1630).
   */
  public boolean isRunning(String sourceRoomId, String callRoomId, Long tenantId) {
    return isRunning(sourceRoomId, callRoomId, tenantId, System.currentTimeMillis());
  }

  boolean isRunning(String sourceRoomId, String callRoomId, Long tenantId, long now) {
    if (blank(sourceRoomId) || blank(callRoomId) || tenantId == null) return false;
    var binding = bindings.findByMediaRoomId(callRoomId);
    if (binding.isPresent()) {
      // The binding is the authority of its call room; a lingering observation never reopens it.
      var call = binding.get();
      return sourceRoomId.equals(call.getSourceRoomId())
          && tenantId.equals(call.getTenantId())
          && call.getEndedAt() == null
          && isActiveCounsellor(call.getCallerMatrixId(), tenantId)
          // Once the call room's observation is lost its memberships may be stale (round 10).
          && call.getMediaObservedAt() != null
          && call.getDevices().values().stream()
              .map(device -> device.getSenderMatrixId())
              .distinct()
              .filter(member -> call.isInCall(member, now))
              .anyMatch(member -> isActiveCounsellor(member, tenantId));
    }
    var observed =
        observations
            .findByMediaRoomId(callRoomId)
            .filter(call -> sourceRoomId.equals(call.getSourceRoomId()))
            .filter(call -> tenantId.equals(call.getTenantId()))
            .filter(call -> call.isLive(now))
            .filter(call -> isActiveCounsellor(call.getCreatorMatrixId(), tenantId))
            .orElse(null);
    return observed != null
        && liveMembers(callRoomId, observed.getCreatorMatrixId(), now).stream()
            .anyMatch(member -> isActiveCounsellor(member, tenantId));
  }

  /**
   * Who holds a live MatrixRTC membership in the call room now, read from its current state as one
   * of its joined members (the creator first). Empty when Matrix cannot tell (fail closed).
   */
  private java.util.Set<String> liveMembers(String callRoomId, String creator, long now) {
    try {
      var members = matrix.getCallRoomMembers(callRoomId).orElse(null);
      if (members == null || members.isEmpty()) return java.util.Set.of();
      String reader = members.contains(creator) ? creator : members.get(0);
      var state = matrix.getCallRoomState(callRoomId, reader).orElse(null);
      if (state == null) return java.util.Set.of();
      var live = new java.util.HashSet<String>();
      for (var event : state) {
        MatrixRtcMembershipEvent.parse(event)
            .filter(membership -> !membership.departed() && membership.expiresAt() > now)
            .filter(membership -> members.contains(membership.sender()))
            .ifPresent(membership -> live.add(membership.sender()));
      }
      return live;
    } catch (
        de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService
                .CallLookupUnavailableException
            unavailable) {
      return java.util.Set.of();
    }
  }

  private boolean isActiveCounsellor(String matrixUserId, Long tenantId) {
    return SoleCounsellor.of(consultants, matrixUserId, tenantId)
        .map(consultant -> consultant.getId())
        .filter(Objects::nonNull)
        .filter(this::isActive)
        .isPresent();
  }

  private boolean isActive(String identityId) {
    return accountLifecycle
        .snapshot(identityId)
        .map(account -> account.status() == AccountInactivityService.Status.ACTIVE)
        .orElse(true);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
