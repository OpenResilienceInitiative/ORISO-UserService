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
 * a counsellor started, in 1:1 sessions and group chats alike. The media gate asks this component
 * before it lets a non-counsellor into a call room.
 *
 * <p><b>Start versus join</b> is decided from server-side records only, never from a client flag. A
 * call room holds a <em>running counsellor call</em> of a conversation when the record the server
 * keeps for that exact call room names this conversation and Träger, is still live, and was created
 * by an active counsellor of the Träger:
 *
 * <ul>
 *   <li>its {@code MatrixCallBinding} (readable invite, or the assigned counsellor's verified
 *       registration of a 1:1 call): not ended, still ringing or with a live MatrixRTC membership
 *       ({@link de.caritas.cob.userservice.api.model.MatrixCallBinding#isLive}), caller a
 *       counsellor; when a binding exists it alone decides;
 *   <li>else its {@code MatrixCallObservation} (an encrypted group or 1:1 call registered by its
 *       creator, verified against Matrix: the creator made the call room and wrote its binding
 *       state): not ended, lease not run out, creator a counsellor.
 * </ul>
 *
 * Anything else is a start. Both records are written only by the server after it checked Matrix, so
 * an advice seeker cannot make their own call room count: the record of their room names them as
 * caller or creator. The check only reads (no lock), so it cannot take part in the binding,
 * session, observation lock order.
 */
@Component
@RequiredArgsConstructor
public class CounsellorStartedCall {
  private final MatrixCallBindingRepository bindings;
  private final MatrixCallObservationRepository observations;
  private final ConsultantRepository consultants;
  private final AccountInactivityService accountLifecycle;

  /**
   * Whether {@code matrixUserId} stands for exactly one not deleted counsellor of {@code tenantId}
   * (the {@link SoleCounsellor} rule). A counsellor of another Träger is no counsellor here.
   */
  @Transactional(readOnly = true)
  public boolean isCounsellor(String matrixUserId, Long tenantId) {
    return tenantId != null && SoleCounsellor.of(consultants, matrixUserId, tenantId).isPresent();
  }

  /**
   * Whether {@code callRoomId} holds a running call a counsellor started in {@code sourceRoomId}.
   */
  @Transactional(readOnly = true)
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
          && call.isLive(now)
          && isActiveCounsellor(call.getCallerMatrixId(), tenantId);
    }
    return observations
        .findByMediaRoomId(callRoomId)
        .filter(call -> sourceRoomId.equals(call.getSourceRoomId()))
        .filter(call -> tenantId.equals(call.getTenantId()))
        .filter(call -> call.isLive(now))
        .filter(call -> isActiveCounsellor(call.getCreatorMatrixId(), tenantId))
        .isPresent();
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
