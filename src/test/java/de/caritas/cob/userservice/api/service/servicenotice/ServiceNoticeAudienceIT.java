package de.caritas.cob.userservice.api.service.servicenotice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.ServiceNoticeCampaign;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ServiceNoticeCampaignRepository;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeAudience.DryRun;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeAudience.MailDecision;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeAudience.Member;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Runs the audience rule against real tables, because the rule is a query, not a calculation. */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfiguration.class, ServiceNoticeAudience.class})
@TestPropertySource(properties = "identity.email-dummy-suffix=@dummy.oriso.invalid")
class ServiceNoticeAudienceIT {

  @Autowired private ServiceNoticeAudience audience;
  @Autowired private AdminRepository admins;
  @Autowired private AdminAgencyRepository adminAgencies;
  @Autowired private ConsultantRepository consultants;
  @Autowired private ServiceNoticeCampaignRepository campaigns;
  @Autowired private EntityManager entityManager;

  @BeforeEach
  void startFromAnEmptyAdminTable() {
    // The shared test seed contains admins; this test owns the whole population.
    adminAgencies.deleteAll();
    admins.deleteAll();
    entityManager.flush();
  }

  @Test
  void anAgencyAdminOfACounsellingCentreReceivesTheNoticeByMail() {
    agencyAdmin("admin-a", 7L, "lead@centre-a.org", 101L);

    assertThat(audience.members()).containsExactly(new Member("admin-a", 7L, MailDecision.MAIL));
  }

  @Test
  void tenantAndPlatformAdminsAndAgencyAdminsWithoutACentreAreNotInTheNormalAudience() {
    agencyAdmin("admin-a", 7L, "lead@centre-a.org", 101L);
    admin("tenant-admin", Admin.AdminType.TENANT, 7L, "owner@traeger.org");
    admin("platform-admin", Admin.AdminType.SUPER, 0L, "ops@platform.org");
    admin("orphan-agency-admin", Admin.AdminType.AGENCY, 7L, "orphan@centre.org");

    assertThat(audience.members()).extracting(Member::userId).containsExactly("admin-a");
  }

  @Test
  void anAgencyAdminWhoAlsoCounselsKeepsTheFeedButNoMailWhenTheirServiceNoticeSwitchIsOff() {
    agencyAdmin("counselling-admin", 7L, "lead@centre-a.org", 101L);
    counsellor("counselling-admin", true, "{\"serviceNoticeNotificationEnabled\":false}", null);
    agencyAdmin("opted-in-admin", 7L, "second@centre-a.org", 101L);
    counsellor("opted-in-admin", true, "{\"serviceNoticeNotificationEnabled\":true}", null);
    agencyAdmin("all-mail-off-admin", 7L, "third@centre-a.org", 101L);
    counsellor("all-mail-off-admin", false, "{\"serviceNoticeNotificationEnabled\":true}", null);

    assertThat(audience.members())
        .containsExactly(
            new Member("all-mail-off-admin", 7L, MailDecision.FEED_ONLY_PREFERENCE_OFF),
            new Member("counselling-admin", 7L, MailDecision.FEED_ONLY_PREFERENCE_OFF),
            new Member("opted-in-admin", 7L, MailDecision.MAIL));
  }

  @Test
  void aDeletedCounsellingAccountIsNotNotifiedAtAll() {
    agencyAdmin("deleted-admin", 7L, "gone@centre-a.org", 101L);
    counsellor(
        "deleted-admin",
        true,
        "{\"serviceNoticeNotificationEnabled\":true}",
        LocalDateTime.of(2026, 9, 1, 8, 0));

    assertThat(audience.members()).isEmpty();
  }

  @Test
  void aPlaceholderAddressOrAMissingSenderTenantKeepsOnlyTheFeedEntry() {
    agencyAdmin("dummy-admin", 7L, "abc@dummy.oriso.invalid", 101L);
    agencyAdmin("tenantless-admin", null, "lead@legacy.org", 102L);

    assertThat(audience.members())
        .containsExactly(
            new Member("dummy-admin", 7L, MailDecision.FEED_ONLY_NO_ADDRESS),
            new Member("tenantless-admin", null, MailDecision.FEED_ONLY_NO_SENDER_TENANT));
  }

  @Test
  void anAdminOfSeveralCentresIsCountedOnce() {
    agencyAdmin("busy-admin", 7L, "lead@centre-a.org", 101L);
    adminAgencies.save(
        AdminAgency.builder()
            .admin(admins.findById("busy-admin").orElseThrow())
            .agencyId(102L)
            .build());

    assertThat(audience.members()).hasSize(1);
  }

  @Test
  void dryRunCountsTheAudienceWithoutChangingTheDraft() {
    agencyAdmin("admin-a", 7L, "lead@centre-a.org", 101L);
    agencyAdmin("admin-b", 8L, "lead@centre-b.org", 201L);
    agencyAdmin("dummy-admin", 8L, "abc@dummy.oriso.invalid", 202L);
    agencyAdmin("quiet-admin", 8L, "quiet@centre-b.org", 203L);
    counsellor("quiet-admin", true, "{\"serviceNoticeNotificationEnabled\":false}", null);
    draft("maintenance-1");

    assertThat(audience.dryRun("maintenance-1"))
        .isEqualTo(new DryRun("maintenance-1", "AGENCY_ADMINS", 4, 2, 1, 1, 0));
    assertThat(campaigns.findByCampaignKey("maintenance-1").orElseThrow().getStatus())
        .isEqualTo("DRAFT");
  }

  @Test
  void dryRunOfAnUnknownDraftIsRefused() {
    assertThatThrownBy(() -> audience.dryRun("missing-draft"))
        .isInstanceOf(NoSuchElementException.class);
  }

  private void agencyAdmin(String id, Long tenantId, String email, Long agencyId) {
    var admin = admin(id, Admin.AdminType.AGENCY, tenantId, email);
    adminAgencies.save(AdminAgency.builder().admin(admin).agencyId(agencyId).build());
    entityManager.flush();
  }

  private Admin admin(String id, Admin.AdminType type, Long tenantId, String email) {
    return admins.saveAndFlush(
        Admin.builder()
            .id(id)
            .type(type)
            .tenantId(tenantId)
            .username(id)
            .firstName("First")
            .lastName("Last")
            .email(email)
            .build());
  }

  private void counsellor(
      String id, boolean notificationsEnabled, String settings, LocalDateTime deleted) {
    consultants.deleteById(id);
    consultants.save(
        Consultant.builder()
            .id(id)
            .username(id)
            .firstName("First")
            .lastName("Last")
            .email(id + "@counsellor.org")
            .encourage2fa(true)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(true)
            .notifyNewChatMessageFromAdviceSeeker(true)
            .languageCode(LanguageCode.de)
            .walkThroughEnabled(true)
            .status(ConsultantStatus.CREATED)
            .notificationsEnabled(notificationsEnabled)
            .notificationsSettings(settings)
            .deleteDate(deleted)
            .tenantId(7L)
            .createDate(LocalDateTime.of(2026, 9, 1, 8, 0))
            .updateDate(LocalDateTime.of(2026, 9, 1, 8, 0))
            .build());
    entityManager.flush();
  }

  private void draft(String key) {
    var draft = new ServiceNoticeCampaign();
    draft.setCampaignKey(key);
    draft.setMaintenanceDate(LocalDate.of(2026, 10, 2));
    draft.setMaintenanceStart(LocalTime.of(14, 0));
    draft.setMaintenanceEnd(LocalTime.of(15, 0));
    draft.setStatusUrl("https://status.operator.dev/maintenance");
    draft.setCreatedByUserId("platform-operator-1");
    draft.setCreatedAt(LocalDateTime.of(2026, 10, 1, 12, 0));
    campaigns.saveAndFlush(draft);
  }
}
