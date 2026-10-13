package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.helper.EmailNotificationUtils.deserializeNotificationSettingsDTOOrDefaultIfNull;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.Status;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.NotificationsAware;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggle;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.mailservice.generated.web.model.Dialect;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Captures a committed takeover outcome before handing immutable mail facts to the sender. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaseHandoverEmailNotification {
  public enum Outcome {
    CONSENT_REQUESTED,
    GRANTED
  }

  public record Mail(
      Long requestId,
      Long sessionId,
      String matrixRoomId,
      Outcome outcome,
      long tenantId,
      String recipient,
      LanguageCode language,
      Dialect dialect,
      AccessType accessType) {
    /** Legacy queued records were exclusively takeover notifications. */
    public Mail(
        Long requestId,
        Long sessionId,
        String matrixRoomId,
        Outcome outcome,
        long tenantId,
        String recipient,
        LanguageCode language,
        Dialect dialect) {
      this(
          requestId,
          sessionId,
          matrixRoomId,
          outcome,
          tenantId,
          recipient,
          language,
          dialect,
          AccessType.TAKEOVER);
    }
  }

  private record DeliveryKey(Long requestId, Outcome outcome) {}

  private final @NonNull CaseHandoverMailSender sender;
  private final @NonNull ReleaseToggleService releaseToggles;
  private final @NonNull IdentityClientConfig identityClientConfig;

  public void consentRequested(CaseHandoverRequest request) {
    if (request == null
        || (request.getAccessType() != AccessType.TAKEOVER
            && request.getAccessType() != AccessType.CO_ACCESS)
        || request.getStatus() != Status.PENDING_CLIENT_CONSENT) return;
    User recipient = request.getSession().getUser();
    if (!eligible(recipient)) return;
    schedule(
        snapshot(
            request,
            Outcome.CONSENT_REQUESTED,
            recipient.getEmail(),
            recipient.getLanguageCode(),
            recipient.getDialect()));
  }

  public void ownershipGranted(CaseHandoverRequest request) {
    if (!isTakeover(request)
        || (request.getStatus() != Status.GRANTED
            && request.getStatus() != Status.GRANTED_PENDING_CLIENT_OPTOUT)) return;
    Consultant recipient = request.getRequesterConsultant();
    if (!eligible(recipient)) return;
    schedule(
        snapshot(
            request,
            Outcome.GRANTED,
            recipient.getEmail(),
            recipient.getLanguageCode(),
            recipient.getDialect()));
  }

  private boolean isTakeover(CaseHandoverRequest request) {
    return request != null && request.getAccessType() == AccessType.TAKEOVER;
  }

  private boolean eligible(NotificationsAware recipient) {
    if (recipient == null) return false;
    String email =
        recipient instanceof User user ? user.getEmail() : ((Consultant) recipient).getEmail();
    String dummySuffix = identityClientConfig.getEmailDummySuffix();
    if (email == null || email.isBlank() || (dummySuffix != null && email.endsWith(dummySuffix)))
      return false;
    if (!releaseToggles.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS)) return true;
    return recipient.isNotificationsEnabled()
        && deserializeNotificationSettingsDTOOrDefaultIfNull(recipient)
            .getReassignmentNotificationEnabled();
  }

  private Mail snapshot(
      CaseHandoverRequest request,
      Outcome outcome,
      String email,
      LanguageCode language,
      Dialect dialect) {
    Long tenantId = request.getTenantId();
    if (request.getId() == null
        || request.getSession() == null
        || request.getSession().getId() == null
        || tenantId == null
        || tenantId <= 0
        || !Objects.equals(tenantId, request.getSession().getTenantId())) {
      throw new IllegalStateException(
          "Takeover mail requires a persisted request and matching tenant");
    }
    String roomId = request.getSession().getMatrixRoomId();
    return new Mail(
        request.getId(),
        request.getSession().getId(),
        roomId,
        outcome,
        tenantId,
        email,
        language,
        dialect,
        request.getAccessType());
  }

  private void schedule(Mail mail) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Takeover mail requires an active domain transaction");
    }
    DeliveryKey key = new DeliveryKey(mail.requestId(), mail.outcome());
    boolean duplicate =
        TransactionSynchronizationManager.getSynchronizations().stream()
            .anyMatch(sync -> sync instanceof Delivery delivery && delivery.key.equals(key));
    if (!duplicate) {
      TransactionSynchronizationManager.registerSynchronization(new Delivery(key, mail));
    }
  }

  private final class Delivery implements TransactionSynchronization {
    private final DeliveryKey key;
    private final Mail mail;

    private Delivery(DeliveryKey key, Mail mail) {
      this.key = key;
      this.mail = mail;
    }

    @Override
    public void afterCommit() {
      // The asynchronous sender owns error reporting: a committed takeover must not be undone
      // or returned as a failed API operation because SMTP later rejects the notification.
      try {
        sender.send(mail);
      } catch (RuntimeException failure) {
        log.error(
            "Takeover mail dispatch failed after commit for tenant {}: {}",
            mail.tenantId(),
            failure.getClass().getSimpleName());
      }
    }
  }
}
