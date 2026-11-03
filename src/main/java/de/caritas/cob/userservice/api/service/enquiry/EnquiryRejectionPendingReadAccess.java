package de.caritas.cob.userservice.api.service.enquiry;

import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Original rejector's current bounded access to their unverified closure, never a new audience. */
@Service
@RequiredArgsConstructor
public class EnquiryRejectionPendingReadAccess {
  private final EnquiryRejectionRepository rejections;
  private final ConsultantRepository consultants;
  private final ConsultantAgencyRepository agencies;
  private final AccountInactivityService lifecycle;

  @Transactional(readOnly = true)
  public boolean canRead(Session session, Consultant caller) {
    if (session == null || caller == null || session.getStatus() != Session.SessionStatus.REJECTED)
      return false;
    var decision = rejections.findById(session.getId()).orElse(null);
    if (decision == null
        || decision.getState() != EnquiryRejection.State.PENDING
        || !Objects.equals(decision.getActorId(), caller.getId())
        || session.getConsultant() != null
        || session.getUser() == null
        || !Objects.equals(session.getUser().getUserId(), decision.getSeekerId())
        || !Objects.equals(session.getMatrixRoomId(), decision.getPrimaryRoomId())
        || !Objects.equals(session.getAgencyId(), decision.getAgencyId())
        || !(Objects.equals(session.getTenantId(), decision.getTenantId())
            || (session.getTenantId() == null && Long.valueOf(1).equals(decision.getTenantId()))))
      return false;
    var current = consultants.findByIdAndDeleteDateIsNull(caller.getId()).orElse(null);
    return current != null
        && Objects.equals(current.getTenantId(), decision.getTenantId())
        && agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            current.getId(), decision.getAgencyId())
        && lifecycle
            .snapshot(current.getId())
            .map(state -> state.status() == AccountInactivityService.Status.ACTIVE)
            .orElse(true);
  }
}
