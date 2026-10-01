package de.caritas.cob.userservice.api.service.servicenotice;

import static de.caritas.cob.userservice.api.helper.EmailNotificationUtils.deserializeNotificationSettingsOrDefaultIfNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ServiceNoticeCampaignRepository;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The planned-maintenance audience, computed only from the database: every counselling-centre
 * (agency) admin. The browser never supplies an address.
 *
 * <p>Everyone in the audience gets the in-app entry; mail additionally needs the person's
 * service-notice switch, a real address and a sender tenant. The switch lives on the counsellor
 * account (ADR-024). An agency admin who does not also counsel has no switch to set, so the mail is
 * not filtered for them.
 *
 * <p>This is the planned notice only. The outage notice (ADR-024 decision 3: never switchable,
 * every user with ongoing counselling) is a different audience and must not reuse this one.
 */
@Service
public class ServiceNoticeAudience {

  public static final String AGENCY_ADMINS = "AGENCY_ADMINS";

  public enum MailDecision {
    MAIL,
    FEED_ONLY_PREFERENCE_OFF,
    FEED_ONLY_NO_ADDRESS,
    FEED_ONLY_NO_SENDER_TENANT
  }

  public record Member(String userId, Long tenantId, MailDecision mail) {}

  public record DryRun(
      String campaignKey,
      String audience,
      int recipients,
      int mail,
      int feedOnlyPreferenceOff,
      int feedOnlyNoAddress,
      int feedOnlyNoSenderTenant) {}

  private final AdminRepository admins;
  private final ConsultantRepository consultants;
  private final ServiceNoticeCampaignRepository campaigns;
  private final String emailDummySuffix;

  public ServiceNoticeAudience(
      AdminRepository admins,
      ConsultantRepository consultants,
      ServiceNoticeCampaignRepository campaigns,
      @Value("${identity.email-dummy-suffix:}") String emailDummySuffix) {
    this.admins = admins;
    this.consultants = consultants;
    this.campaigns = campaigns;
    this.emailDummySuffix = emailDummySuffix;
  }

  @Transactional(readOnly = true)
  public List<Member> members() {
    var agencyAdmins = admins.findAssignedToAnAgencyByType(Admin.AdminType.AGENCY);
    Map<String, Consultant> counsellorAccounts =
        consultants.findAllByIdIn(agencyAdmins.stream().map(Admin::getId).toList()).stream()
            .collect(Collectors.toMap(Consultant::getId, Function.identity()));
    return agencyAdmins.stream()
        .filter(admin -> !deleted(counsellorAccounts.get(admin.getId())))
        .map(
            admin ->
                new Member(
                    admin.getId(),
                    admin.getTenantId(),
                    mailDecision(admin, counsellorAccounts.get(admin.getId()))))
        .toList();
  }

  /** Counts the audience a confirmation would reach right now. Reads only; sends nothing. */
  @Transactional(readOnly = true)
  public DryRun dryRun(String campaignKey) {
    campaigns
        .findByCampaignKey(campaignKey)
        .orElseThrow(() -> new NoSuchElementException("Service notice draft not found"));
    var members = members();
    return new DryRun(
        campaignKey,
        AGENCY_ADMINS,
        members.size(),
        count(members, MailDecision.MAIL),
        count(members, MailDecision.FEED_ONLY_PREFERENCE_OFF),
        count(members, MailDecision.FEED_ONLY_NO_ADDRESS),
        count(members, MailDecision.FEED_ONLY_NO_SENDER_TENANT));
  }

  private MailDecision mailDecision(Admin admin, Consultant counsellorAccount) {
    if (counsellorAccount != null && !serviceNoticeMailWanted(counsellorAccount)) {
      return MailDecision.FEED_ONLY_PREFERENCE_OFF;
    }
    if (isBlank(admin.getEmail())
        || (!isBlank(emailDummySuffix) && admin.getEmail().endsWith(emailDummySuffix))) {
      return MailDecision.FEED_ONLY_NO_ADDRESS;
    }
    if (admin.getTenantId() == null || admin.getTenantId() <= 0) {
      return MailDecision.FEED_ONLY_NO_SENDER_TENANT;
    }
    return MailDecision.MAIL;
  }

  private static boolean serviceNoticeMailWanted(Consultant counsellor) {
    return counsellor.isNotificationsEnabled()
        && deserializeNotificationSettingsOrDefaultIfNull(counsellor)
            .isServiceNoticeNotificationEnabled();
  }

  private static boolean deleted(Consultant counsellorAccount) {
    return counsellorAccount != null && counsellorAccount.getDeleteDate() != null;
  }

  private static int count(List<Member> members, MailDecision decision) {
    return (int) members.stream().filter(member -> member.mail() == decision).count();
  }
}
