package de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.service;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.InactiveAccountNotificationAuditLog;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.model.InactiveAccountRole;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailsDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Security-06 inactivity threshold monitor for askers, consultants and admins. */
@Service
@RequiredArgsConstructor
@Slf4j
public class InactiveAccountNotificationService {

  private static final String TEMPLATE_FREE_TEXT = "free_text";

  private final @NonNull UserRepository userRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull AskerActivityCalculator askerActivityCalculator;
  private final @NonNull ConsultantActivityCalculator consultantActivityCalculator;
  private final @NonNull AdminActivityCalculator adminActivityCalculator;
  private final @NonNull InactiveAccountNotificationRecipientResolver recipientResolver;
  private final @NonNull InactiveAccountNotificationClaimWriter claimWriter;
  private final @NonNull MailService mailService;

  @Value("${inactive.account.notification.threshold.days:365}")
  private long inactivityThresholdDays;

  @Value("${inactive.account.notification.email-dispatch.enabled:false}")
  private boolean emailDispatchEnabled;

  @Value("${inactive.account.notification.idempotent-recovery.enabled:false}")
  private boolean idempotentRecoveryEnabled;

  @Value("${inactive.account.notification.email-dispatch.recovery-after:PT5M}")
  private Duration emailDispatchRecoveryAfter;

  @Value("${inactive.account.notification.app-base-url:${app.base.url}}")
  private String appBaseUrl;

  @Transactional
  public void scanAndNotifyInactiveAccounts() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    LocalDateTime cutoff = now.minusDays(inactivityThresholdDays);
    notifyInactiveAskers(cutoff, now);
    notifyInactiveConsultants(cutoff, now);
    notifyInactiveAdmins(cutoff, now);
  }

  private void notifyInactiveAskers(LocalDateTime cutoff, LocalDateTime now) {
    for (User user : userRepository.findAllByDeleteDateIsNull()) {
      Optional<LocalDateTime> lastActivity = askerActivityCalculator.lastActivity(user);
      if (isInactive(lastActivity, cutoff)) {
        emitForRecipients(
            InactiveAccountRole.ASKER,
            user.getUserId(),
            user.getTenantId(),
            lastActivity.orElse(null),
            now);
      }
    }
  }

  private void notifyInactiveConsultants(LocalDateTime cutoff, LocalDateTime now) {
    for (Consultant consultant : consultantRepository.findByDeleteDateIsNull()) {
      Optional<LocalDateTime> lastActivity = consultantActivityCalculator.lastActivity(consultant);
      if (isInactive(lastActivity, cutoff)) {
        emitForRecipients(
            InactiveAccountRole.CONSULTANT,
            consultant.getId(),
            consultant.getTenantId(),
            lastActivity.orElse(null),
            now);
      }
    }
  }

  private void notifyInactiveAdmins(LocalDateTime cutoff, LocalDateTime now) {
    for (Admin admin : adminRepository.findAll()) {
      Optional<LocalDateTime> lastActivity = adminActivityCalculator.lastActivity(admin);
      if (isInactive(lastActivity, cutoff)) {
        emitForRecipients(
            InactiveAccountRole.ADMIN,
            admin.getId(),
            admin.getTenantId(),
            lastActivity.orElse(null),
            now);
      }
    }
  }

  private boolean isInactive(Optional<LocalDateTime> lastActivity, LocalDateTime cutoff) {
    return lastActivity.isPresent() && lastActivity.get().isBefore(cutoff);
  }

  private void emitForRecipients(
      InactiveAccountRole role,
      String accountId,
      Long accountTenantId,
      LocalDateTime lastActivityAt,
      LocalDateTime now) {
    for (Admin recipient : recipientResolver.resolveRecipients(accountTenantId)) {
      String fingerprint =
          buildFingerprint(
              role, accountId, recipient.getId(), lastActivityAt, inactivityThresholdDays);
      String subject = "Inactive account threshold reached (12 months)";
      String body =
          buildBody(role, accountId, accountTenantId, lastActivityAt, inactivityThresholdDays);
      InactiveAccountNotificationAuditLog auditLog;
      try {
        auditLog =
            claimWriter.claim(
                InactiveAccountNotificationAuditLog.builder()
                    .notificationFingerprint(fingerprint)
                    .accountRole(role)
                    .accountId(accountId)
                    .accountTenantId(accountTenantId)
                    .lastActivityAt(lastActivityAt)
                    .thresholdDays((int) inactivityThresholdDays)
                    .recipientAdminId(recipient.getId())
                    .recipientEmail(recipient.getEmail())
                    .emailIdempotencyKey(buildIdempotencyKey(fingerprint))
                    .emailTemplate(TEMPLATE_FREE_TEXT)
                    .emailSubject(subject)
                    .emailBody(body)
                    .emailUrl(appBaseUrl)
                    .emailDispatched(false)
                    .createDate(now)
                    .tenantId(accountTenantId)
                    .build());
      } catch (DataIntegrityViolationException duplicateClaim) {
        log.debug("Inactive account notification {} already claimed", fingerprint);
        if (!idempotentRecoveryEnabled) {
          continue;
        }
        auditLog = claimWriter.findByFingerprint(fingerprint).orElse(null);
        if (auditLog == null) {
          continue;
        }
      }
      if (emailDispatchEnabled) {
        if (auditLog.getEmailIdempotencyKey() == null) {
          log.debug(
              "Inactive account notification auditLogId={} predates idempotent recovery",
              auditLog.getId());
          continue;
        }
        if (claimWriter
            .tryStartEmailDispatch(auditLog.getId(), now, emailDispatchRecoveryAfter)
            .isEmpty()) {
          continue;
        }
        if (dispatchEmail(auditLog)) {
          claimWriter.markEmailDispatched(auditLog.getId());
        }
      }
    }
  }

  private boolean dispatchEmail(InactiveAccountNotificationAuditLog auditLog) {
    MailDTO mail =
        new MailDTO()
            .template(auditLog.getEmailTemplate())
            .email(auditLog.getRecipientEmail())
            .templateData(
                List.of(
                    new TemplateDataDTO().key("subject").value(auditLog.getEmailSubject()),
                    new TemplateDataDTO().key("text").value(auditLog.getEmailBody()),
                    new TemplateDataDTO().key("url").value(auditLog.getEmailUrl())));
    boolean accepted =
        mailService.sendEmailNotification(
            new MailsDTO().mails(List.of(mail)), auditLog.getEmailIdempotencyKey());
    if (accepted) {
      log.info(
          "Inactive account notification dispatched to adminId={} role={} accountId={}",
          auditLog.getRecipientAdminId(),
          auditLog.getAccountRole(),
          auditLog.getAccountId());
    } else {
      log.warn(
          "Inactive account notification was not accepted by MailService for adminId={} role={} accountId={}",
          auditLog.getRecipientAdminId(),
          auditLog.getAccountRole(),
          auditLog.getAccountId());
    }
    return accepted;
  }

  private String buildBody(
      InactiveAccountRole role,
      String accountId,
      Long accountTenantId,
      LocalDateTime lastActivityAt,
      long thresholdDays) {
    return String.format(
        Locale.ROOT,
        "Account role=%s, accountId=%s, tenantId=%s crossed inactivity threshold (%d days). Last activity=%s.",
        role.name(),
        accountId,
        accountTenantId,
        thresholdDays,
        String.valueOf(lastActivityAt));
  }

  private String buildIdempotencyKey(String fingerprint) {
    return "inactive-account-" + DigestUtils.sha256Hex(fingerprint);
  }

  private String buildFingerprint(
      InactiveAccountRole role,
      String accountId,
      String recipientAdminId,
      LocalDateTime lastActivityAt,
      long thresholdDays) {
    return String.format(
        Locale.ROOT,
        "%s|%s|%s|%s|%d",
        role.name(),
        accountId,
        recipientAdminId,
        String.valueOf(lastActivityAt),
        thresholdDays);
  }
}
