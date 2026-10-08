package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.model.CaseHandoverConsentMode;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rechecks required personal consent without redirecting the committed recipient snapshot. */
@Service
@RequiredArgsConstructor
public class CaseHandoverRequiredConsentMailEligibility {
  private final @NonNull CaseHandoverRequestRepository requests;
  private final @NonNull AccountInactivityService lifecycle;

  @Transactional(readOnly = true)
  public boolean isEligible(CaseHandoverEmailNotification.Mail mail) {
    var request = requests.findById(mail.requestId()).orElse(null);
    if (request == null
        || !Objects.equals(request.getId(), mail.requestId())
        || !Objects.equals(request.getTenantId(), mail.tenantId())
        || !requiresPersonalConsent(request)) return false;
    var session = request.getSession();
    if (session == null
        || !Objects.equals(session.getId(), mail.sessionId())
        || !Objects.equals(session.getTenantId(), mail.tenantId())
        || !Objects.equals(session.getMatrixRoomId(), mail.matrixRoomId())) return false;
    var recipient = session.getUser();
    if (recipient == null
        || recipient.getDeleteDate() != null
        || !Objects.equals(recipient.getTenantId(), mail.tenantId())
        || mail.recipientUserId() == null
        || mail.recipientUserId().isBlank()
        || !Objects.equals(recipient.getUserId(), mail.recipientUserId())
        || mail.recipient() == null
        || mail.recipient().isBlank()
        || !Objects.equals(recipient.getEmail(), mail.recipient())) return false;
    var currentOwner = session.getConsultant();
    var originalOwner = request.getPreviousConsultant();
    if (!Objects.equals(
        currentOwner == null ? null : currentOwner.getId(),
        originalOwner == null ? null : originalOwner.getId())) return false;
    // A missing lifecycle row retains compatibility only for the current domain recipient.
    return lifecycle
        .snapshot(recipient.getUserId())
        .map(account -> account.status() == AccountInactivityService.Status.ACTIVE)
        .orElse(true);
  }

  static boolean requiresPersonalConsent(CaseHandoverRequest request) {
    return request != null
        && request.getAccessType() == CaseHandoverRequest.AccessType.TAKEOVER
        && request.getStatus() == CaseHandoverRequest.Status.PENDING_CLIENT_CONSENT
        && (request.getClientConsent() == CaseHandoverConsentMode.OPT_IN
            || (request.getClientConsent() == null
                && Boolean.TRUE.equals(request.getClientConsentRequired())));
  }
}
