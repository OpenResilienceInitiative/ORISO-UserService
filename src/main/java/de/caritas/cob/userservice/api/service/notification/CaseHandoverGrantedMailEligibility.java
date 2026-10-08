package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.helper.EmailNotificationUtils.deserializeNotificationSettingsDTOOrDefaultIfNull;

import de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.Status;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggle;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rechecks the current grant recipient without changing the committed mail snapshot. */
@Service
@RequiredArgsConstructor
public class CaseHandoverGrantedMailEligibility {
  private final @NonNull CaseHandoverRequestRepository requests;
  private final @NonNull ReleaseToggleService releaseToggles;
  private final @NonNull AccountInactivityService lifecycle;

  @Transactional(readOnly = true)
  public boolean isEligible(CaseHandoverEmailNotification.Mail mail) {
    var request = requests.findById(mail.requestId()).orElse(null);
    if (request == null
        || !Objects.equals(request.getId(), mail.requestId())
        || !Objects.equals(request.getTenantId(), mail.tenantId())
        || request.getAccessType() != AccessType.TAKEOVER
        || (request.getStatus() != Status.GRANTED
            && request.getStatus() != Status.GRANTED_PENDING_CLIENT_OPTOUT)) return false;
    var session = request.getSession();
    if (session == null
        || !Objects.equals(session.getId(), mail.sessionId())
        || !Objects.equals(session.getTenantId(), mail.tenantId())
        || !Objects.equals(session.getMatrixRoomId(), mail.matrixRoomId())) return false;
    var recipient = request.getRequesterConsultant();
    if (recipient == null
        || recipient.getDeleteDate() != null
        || !Objects.equals(recipient.getTenantId(), mail.tenantId())
        || mail.recipient() == null
        || mail.recipient().isBlank()
        || !Objects.equals(recipient.getEmail(), mail.recipient())
        || !session.isAdvisedBy(recipient.getId())) return false;
    // Legacy accounts without a lifecycle row retain their established eligibility behavior.
    if (lifecycle
        .snapshot(recipient.getId())
        .map(account -> account.status() != AccountInactivityService.Status.ACTIVE)
        .orElse(false)) return false;
    return !releaseToggles.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS)
        || (recipient.isNotificationsEnabled()
            && Boolean.TRUE.equals(
                deserializeNotificationSettingsDTOOrDefaultIfNull(recipient)
                    .getReassignmentNotificationEnabled()));
  }
}
