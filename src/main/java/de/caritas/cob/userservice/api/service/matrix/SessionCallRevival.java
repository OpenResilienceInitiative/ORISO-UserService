package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationEndRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * FE#2209 round 5, the one exception to R4 (an ended call stays ended): an ended binding may still
 * be taken over by the session's assigned counsellor when it never had authority and the server
 * closed it unseen. Otherwise an asker who decrypted the counsellor's call id could post a
 * plaintext invite with a few seconds of lifetime and, once the server closed it, block that call
 * id for good.
 *
 * <p>Round 6: one predicate for both sides. {@link MatrixSessionCallBindingService} revives exactly
 * these bindings, and {@link MatrixCallStateService} hides exactly these from the assigned
 * counsellor, so the UI never offers a start that the bind refuses.
 */
@Component
@RequiredArgsConstructor
public class SessionCallRevival {
  private final ConsultantRepository consultants;
  private final MatrixCallObservationEndRepository ends;

  /**
   * Whether the session's assigned counsellor {@code takerMatrixId} may take over this ended
   * binding as a fresh live call. All of these hold:
   *
   * <ul>
   *   <li>the server closed it ({@code end_cause = LIFECYCLE}); an end through the endpoint, or an
   *       end recorded before the cause was stored (null), keeps it ended;
   *   <li>it never ran: never started, never observed, no device;
   *   <li>its caller is no counsellor (deleted ones included, and an unknown caller counts as one),
   *       so it never had authority;
   *   <li>the taker did not store an end of this call (matched by Matrix id, no tenant lookup).
   *       Round 6: only the taker's own end counts. A colleague of the Träger who is a room member
   *       but not in the call may decline it, and the asker may end their own invite; neither keeps
   *       the assigned counsellor's call id ended.
   * </ul>
   *
   * The caller decides who the taker is (the session's assigned counsellor); this predicate does
   * not check the assignment.
   */
  public boolean revivableBy(MatrixCallBinding binding, String takerMatrixId) {
    return binding != null
        && takerMatrixId != null
        && !takerMatrixId.isBlank()
        && binding.getEndedAt() != null
        && MatrixCallBinding.END_CAUSE_LIFECYCLE.equals(binding.getEndCause())
        && binding.getStartedAt() == null
        && binding.getMediaObservedAt() == null
        && binding.getDevices().isEmpty()
        && !isCounsellor(binding.getCallerMatrixId())
        && !ends.findActorMatrixIdsBySourceRoomIdAndCallId(
                binding.getSourceRoomId(), binding.getCallId())
            .contains(takerMatrixId);
  }

  /**
   * Any tenant, soft-deleted rows included (CodeRabbit 4564532702): the tenant-filtered lookup
   * would miss a counsellor moved to another tenant, who keeps their Matrix id, and the revival
   * would fail open. An unknown (null) caller counts as a counsellor (fail closed).
   */
  private boolean isCounsellor(String matrixId) {
    return matrixId == null || consultants.countAnyTenantByMatrixUserId(matrixId) > 0;
  }
}
