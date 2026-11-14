package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * FE#2209 review (decision of 13 Nov): any member's call in a 1:1 session rings and gets its
 * lifecycle and history, but only a binding whose caller is the session's assigned, active
 * counsellor of the Träger backs share links, knocks and guest seats. Checked when the binding is
 * used, so a later reassignment or suspension takes effect at once. Group chat bindings are not
 * affected.
 */
@Component
@RequiredArgsConstructor
public class SessionCallAuthority {
  private final SessionRepository sessions;
  private final ConsultantRepository consultants;
  private final AccountInactivityService accountLifecycle;

  /** Whether this binding may open its call to guests at all. */
  public boolean grantsGuestAuthority(MatrixCallBinding binding) {
    if (binding == null) return false;
    if (binding.getSessionId() == null) return true;
    return isAssignedCounsellor(
        binding.getSessionId(), binding.getTenantId(), binding.getCallerMatrixId());
  }

  /** Whether {@code matrixId} is the session's assigned counsellor, active, of its Träger. */
  public boolean isAssignedCounsellor(Long sessionId, Long tenantId, String matrixId) {
    if (sessionId == null || tenantId == null || matrixId == null || matrixId.isBlank()) {
      return false;
    }
    String assignedId =
        sessions
            .findById(sessionId)
            .filter(session -> Objects.equals(session.getTenantId(), tenantId))
            .map(session -> session.getConsultant())
            .map(Consultant::getId)
            .orElse(null);
    if (assignedId == null) return false;
    return consultants
        .findByIdAndDeleteDateIsNull(assignedId)
        .filter(consultant -> tenantId.equals(consultant.getTenantId()))
        .filter(consultant -> matrixId.equals(consultant.getMatrixUserId()))
        .filter(consultant -> isActive(consultant.getId()))
        .isPresent();
  }

  private boolean isActive(String identityId) {
    return accountLifecycle
        .snapshot(identityId)
        .map(account -> account.status() == AccountInactivityService.Status.ACTIVE)
        .orElse(true);
  }
}
