package de.caritas.cob.userservice.api.service.servicenotice;

import de.caritas.cob.userservice.api.model.ServiceNoticeRecipient;
import de.caritas.cob.userservice.api.model.ServiceNoticeRecipient.MailStatus;
import de.caritas.cob.userservice.api.port.out.ServiceNoticeCampaignRepository;
import de.caritas.cob.userservice.api.port.out.ServiceNoticeRecipientRepository;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailDelivery;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailRouteService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Sends due planned-notice mail, a bounded batch per run. Before each mail it checks the person
 * again (still a counselling-centre admin, switch still on, address still real), so a change after
 * confirmation is honoured. A mail whose transport outcome is unknown is never repeated.
 */
@Service
@Slf4j
public class ServiceNoticeMailWorker {

  private final ServiceNoticeRecipientRepository recipients;
  private final ServiceNoticeCampaignRepository campaigns;
  private final ServiceNoticeAudience audience;
  private final ServiceNoticeMailComposer composer;
  private final ServiceNoticeMailClaims claims;
  private final TenantSystemEmailDelivery delivery;
  private final int batchSize;
  private final int maxAttempts;

  public ServiceNoticeMailWorker(
      ServiceNoticeRecipientRepository recipients,
      ServiceNoticeCampaignRepository campaigns,
      ServiceNoticeAudience audience,
      ServiceNoticeMailComposer composer,
      ServiceNoticeMailClaims claims,
      TenantSystemEmailDelivery delivery,
      @Value("${service.notice.mail.batch-size:20}") int batchSize,
      @Value("${service.notice.mail.max-attempts:5}") int maxAttempts) {
    if (batchSize < 1 || batchSize > 500 || maxAttempts < 1) {
      throw new IllegalArgumentException("Service-notice mail batch size or retry limit invalid");
    }
    this.recipients = recipients;
    this.campaigns = campaigns;
    this.audience = audience;
    this.composer = composer;
    this.claims = claims;
    this.delivery = delivery;
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
  }

  public void dispatchDue() {
    var now = LocalDateTime.now(ZoneOffset.UTC);
    for (var row : recipients.findDue(MailStatus.PENDING, now, PageRequest.of(0, batchSize))) {
      try {
        dispatch(row);
      } catch (RuntimeException failure) {
        // Nothing reached a transport: count the attempt and back off, up to the retry limit.
        try {
          boolean givenUp =
              claims.recordFailedAttempt(
                  row.getId(), now.plusSeconds(backoffSeconds(row.getFailureCount())), maxAttempts);
          log.error(
              "Service-notice mail {} {}: {}",
              row.getId(),
              givenUp ? "failed for good" : "will be retried",
              failure.getClass().getSimpleName());
        } catch (RuntimeException recordFailure) {
          log.error(
              "Service-notice mail {} could not record a failed attempt: {}",
              row.getId(),
              recordFailure.getClass().getSimpleName());
        }
      }
    }
  }

  private static long backoffSeconds(int failures) {
    return Math.min(3600L, 300L << Math.min(Math.max(failures, 0), 4));
  }

  private void dispatch(ServiceNoticeRecipient row) {
    var campaign =
        campaigns
            .findById(row.getCampaignId())
            .orElseThrow(() -> new IllegalStateException("Service-notice campaign is missing"));
    var target = audience.mailTarget(row.getRecipientId());
    if (target.isEmpty()) {
      suppress(row);
      return;
    }
    var composed = composer.compose(campaign, target.get());
    if (composed.isEmpty()) {
      suppress(row);
      return;
    }
    var mail = composed.get();
    UUID correlationId = UUID.fromString(row.getCorrelationId());
    delivery.requireConfigured(mail.route());
    if (!claims.claim(row.getId())) {
      return;
    }
    boolean sent;
    try {
      sent =
          delivery.sendConfirmed(
              mail.tenantId(),
              mail.route(),
              TenantSystemEmailDelivery.Purpose.SERVICE_NOTICE,
              mail.recipient(),
              mail.email(),
              correlationId);
    } catch (PlatformSmtpSettingsProvider.ConfigurationException
        | ApplicationSettingsService.SmtpSettingsUnavailableException
        | TenantSystemEmailRouteService.ConfigurationException beforeHandoff) {
      // These fail before any SMTP or relay attempt, so a retry cannot duplicate a mail.
      claims.releaseBeforeHandoff(row.getId());
      throw beforeHandoff;
    } catch (RuntimeException unknownOutcome) {
      claims.finish(row.getId(), MailStatus.UNCERTAIN);
      log.error(
          "Service-notice mail {} needs delivery reconciliation: {}",
          row.getId(),
          unknownOutcome.getClass().getSimpleName());
      return;
    }
    claims.finish(row.getId(), sent ? MailStatus.SENT : MailStatus.UNCERTAIN);
  }

  private void suppress(ServiceNoticeRecipient row) {
    if (claims.claim(row.getId())) {
      claims.finish(row.getId(), MailStatus.SUPPRESSED);
    }
  }
}
