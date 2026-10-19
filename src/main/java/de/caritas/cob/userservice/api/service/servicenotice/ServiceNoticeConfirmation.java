package de.caritas.cob.userservice.api.service.servicenotice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.ServiceNoticeCampaign;
import de.caritas.cob.userservice.api.model.ServiceNoticeRecipient;
import de.caritas.cob.userservice.api.model.ServiceNoticeRecipient.MailStatus;
import de.caritas.cob.userservice.api.port.out.ServiceNoticeCampaignRepository;
import de.caritas.cob.userservice.api.port.out.ServiceNoticeRecipientRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeAudience.MailDecision;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeAudience.Member;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The explicit platform-operator decision to send a planned notice. It records every recipient once
 * and queues their mail; it never sends anything itself. Repeating it changes nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceNoticeConfirmation {

  static final String FEED_EVENT_TYPE = "service.notice.planned";
  static final String CONFIRMED = "CONFIRMED";
  // The maintenance window is entered as wall-clock time of the operating organisation.
  private static final ZoneId OPERATOR_ZONE = ZoneId.of("Europe/Berlin");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

  private final ServiceNoticeCampaignRepository campaigns;
  private final ServiceNoticeRecipientRepository recipients;
  private final ServiceNoticeAudience audience;
  private final EventNotificationService feed;
  private final PlatformTransactionManager transactionManager;
  private final ObjectMapper json = new ObjectMapper();

  public record Confirmed(
      String campaignKey,
      String status,
      int recipients,
      int mailQueued,
      boolean alreadyConfirmed) {}

  /** The request cannot be confirmed as asked; nothing was recorded. */
  public static class Refused extends RuntimeException {
    public Refused(String message) {
      super(message);
    }
  }

  public static class NotTheDraftOwner extends RuntimeException {
    public NotTheDraftOwner() {
      super("Only the operator who created the draft may confirm it");
    }
  }

  private record Recorded(
      Confirmed result, ServiceNoticeCampaign campaign, List<ServiceNoticeRecipient> rows) {}

  public Confirmed confirm(String campaignKey, int expectedRecipients, String operatorUserId) {
    var recorded =
        new TransactionTemplate(transactionManager)
            .execute(status -> record(campaignKey, expectedRecipients, operatorUserId));
    // After the commit, so a rolled-back confirmation never leaves feed entries behind. The write
    // is idempotent per campaign and recipient: repeating the confirmation heals a lost entry.
    addFeedEntries(recorded.campaign(), recorded.rows());
    return recorded.result();
  }

  private Recorded record(String campaignKey, int expectedRecipients, String operatorUserId) {
    var campaign =
        campaigns
            .findByCampaignKeyForUpdate(campaignKey)
            .orElseThrow(() -> new NoSuchElementException("Service notice draft not found"));
    if (!campaign.getCreatedByUserId().equals(operatorUserId)) {
      throw new NotTheDraftOwner();
    }
    if (CONFIRMED.equals(campaign.getStatus())) {
      var rows = recipients.findByCampaignIdOrderById(campaign.getId());
      return new Recorded(summary(campaign, rows, true), campaign, rows);
    }
    if (!LocalDateTime.of(campaign.getMaintenanceDate(), campaign.getMaintenanceEnd())
        .isAfter(LocalDateTime.now(OPERATOR_ZONE))) {
      throw new Refused("The maintenance window is already over");
    }
    var members = audience.members();
    if (members.isEmpty()) {
      throw new Refused("Nobody would receive this notice");
    }
    if (members.size() != expectedRecipients) {
      throw new Refused(
          "The audience changed since the dry run: it now has "
              + members.size()
              + " recipients. Run the dry run again before confirming.");
    }

    var now = LocalDateTime.now(ZoneOffset.UTC);
    var rows = members.stream().map(member -> recipient(campaign.getId(), member, now)).toList();
    recipients.saveAll(rows);
    campaign.setStatus(CONFIRMED);
    campaign.setConfirmedByUserId(operatorUserId);
    campaign.setConfirmedAt(now);
    campaigns.save(campaign);
    return new Recorded(summary(campaign, rows, false), campaign, rows);
  }

  private static ServiceNoticeRecipient recipient(
      Long campaignId, Member member, LocalDateTime now) {
    var row = new ServiceNoticeRecipient();
    row.setCampaignId(campaignId);
    row.setRecipientId(member.userId());
    row.setTenantId(member.tenantId());
    row.setMailStatus(mailStatus(member.mail()));
    row.setCorrelationId(UUID.randomUUID().toString());
    row.setNextAttemptAtUtc(now);
    row.setCreatedAt(now);
    return row;
  }

  private static MailStatus mailStatus(MailDecision decision) {
    return switch (decision) {
      case MAIL -> MailStatus.PENDING;
      case FEED_ONLY_PREFERENCE_OFF -> MailStatus.NOT_SENT_PREFERENCE_OFF;
      case FEED_ONLY_NO_ADDRESS -> MailStatus.NOT_SENT_NO_ADDRESS;
      case FEED_ONLY_NO_SENDER_TENANT -> MailStatus.NOT_SENT_NO_SENDER_TENANT;
    };
  }

  private static Confirmed summary(
      ServiceNoticeCampaign campaign, List<ServiceNoticeRecipient> rows, boolean repeated) {
    int mail =
        (int) rows.stream().filter(row -> row.getMailStatus().mailQueuedAtConfirmation()).count();
    return new Confirmed(campaign.getCampaignKey(), CONFIRMED, rows.size(), mail, repeated);
  }

  private void addFeedEntries(ServiceNoticeCampaign campaign, List<ServiceNoticeRecipient> rows) {
    String start = campaign.getMaintenanceStart().format(TIME);
    String end = campaign.getMaintenanceEnd().format(TIME);
    String text =
        String.format(
            "Planned maintenance on %s from %s to %s. Current status: %s",
            campaign.getMaintenanceDate(), start, end, campaign.getStatusUrl());
    String params = params(campaign, start, end);
    for (var row : rows) {
      try {
        feed.createEventOnce(
            "service-notice:" + campaign.getCampaignKey(),
            row.getRecipientId(),
            FEED_EVENT_TYPE,
            EventNotificationService.CATEGORY_SYSTEM,
            "Planned maintenance",
            text,
            params,
            null,
            null,
            row.getTenantId());
      } catch (RuntimeException failure) {
        log.error(
            "Service notice {} feed entry for recipient row {} failed: {}",
            campaign.getCampaignKey(),
            row.getId(),
            failure.getClass().getSimpleName());
      }
    }
  }

  private String params(ServiceNoticeCampaign campaign, String start, String end) {
    var values = new LinkedHashMap<String, String>();
    values.put("campaignKey", campaign.getCampaignKey());
    values.put("maintenanceDate", campaign.getMaintenanceDate().toString());
    values.put("maintenanceStart", start);
    values.put("maintenanceEnd", end);
    values.put("statusUrl", campaign.getStatusUrl());
    try {
      return json.writeValueAsString(values);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
