package de.caritas.cob.userservice.api.service.servicenotice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.ServiceNoticeCampaign;
import de.caritas.cob.userservice.api.model.ServiceNoticeRecipient;
import de.caritas.cob.userservice.api.model.ServiceNoticeRecipient.MailStatus;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ServiceNoticeCampaignRepository;
import de.caritas.cob.userservice.api.port.out.ServiceNoticeRecipientRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeConfirmation.Confirmed;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Confirmation is the only step that records recipients; it must never do so twice. */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfiguration.class,
  ServiceNoticeAudience.class,
  ServiceNoticeConfirmation.class
})
@TestPropertySource(properties = "identity.email-dummy-suffix=@dummy.oriso.invalid")
class ServiceNoticeConfirmationIT {

  private static final String OPERATOR = "platform-operator-1";
  private static final LocalDate FUTURE_DAY = LocalDate.now().plusDays(7);

  @Autowired private ServiceNoticeConfirmation confirmation;
  @Autowired private AdminRepository admins;
  @Autowired private AdminAgencyRepository adminAgencies;
  @Autowired private ServiceNoticeCampaignRepository campaigns;
  @Autowired private ServiceNoticeRecipientRepository recipients;
  @Autowired private EntityManager entityManager;
  @MockitoBean private EventNotificationService feed;

  @BeforeEach
  void twoCentresWithOneAdminEachAndOneWithoutAnAddress() {
    // The platform admin's confirm request runs in the technical tenant 0.
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    adminAgencies.deleteAll();
    admins.deleteAll();
    agencyAdmin("admin-a", 7L, "lead@centre-a.org", 101L);
    agencyAdmin("admin-b", 8L, "lead@centre-b.org", 201L);
    agencyAdmin("admin-c", 8L, "abc@dummy.oriso.invalid", 202L);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void confirmingQueuesOneMailPerMailRecipientAndOneFeedEntryPerRecipient() {
    draft("maintenance-1", FUTURE_DAY);

    var result = confirmation.confirm("maintenance-1", 3, OPERATOR);

    assertThat(result).isEqualTo(new Confirmed("maintenance-1", "CONFIRMED", 3, 2, false));
    assertThat(recipients.findAll())
        .extracting(ServiceNoticeRecipient::getRecipientId, ServiceNoticeRecipient::getMailStatus)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("admin-a", MailStatus.PENDING),
            org.assertj.core.groups.Tuple.tuple("admin-b", MailStatus.PENDING),
            org.assertj.core.groups.Tuple.tuple("admin-c", MailStatus.NOT_SENT_NO_ADDRESS));
    var stored = campaigns.findByCampaignKey("maintenance-1").orElseThrow();
    assertThat(stored.getStatus()).isEqualTo("CONFIRMED");
    assertThat(stored.getConfirmedByUserId()).isEqualTo(OPERATOR);
    assertThat(stored.getConfirmedAt()).isNotNull();
    for (var recipient : new String[] {"admin-a", "admin-b", "admin-c"}) {
      verify(feed)
          .createEventOnce(
              eq("service-notice:maintenance-1"),
              eq(recipient),
              eq("service.notice.planned"),
              eq(EventNotificationService.CATEGORY_SYSTEM),
              anyString(),
              contains("https://status.operator.dev/maintenance"),
              contains("\"campaignKey\":\"maintenance-1\""),
              isNull(),
              isNull(),
              any());
    }
  }

  @Test
  void repeatingTheConfirmationRecordsNothingNew() {
    draft("maintenance-1", FUTURE_DAY);
    confirmation.confirm("maintenance-1", 3, OPERATOR);
    entityManager.flush();
    entityManager.clear();
    agencyAdmin("admin-late", 7L, "late@centre-a.org", 103L);

    var repeated = confirmation.confirm("maintenance-1", 3, OPERATOR);

    assertThat(repeated).isEqualTo(new Confirmed("maintenance-1", "CONFIRMED", 3, 2, true));
    assertThat(recipients.count()).isEqualTo(3);
    // The feed write is idempotent per key and recipient, so a repeat may heal a lost entry.
    verify(feed, times(2))
        .createEventOnce(
            eq("service-notice:maintenance-1"),
            eq("admin-a"),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any(),
            any());
    verify(feed, never())
        .createEventOnce(
            anyString(),
            eq("admin-late"),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any(),
            any());
  }

  @Test
  void aCountThatChangedSinceTheDryRunIsRefusedWithoutRecordingAnything() {
    draft("maintenance-1", FUTURE_DAY);

    assertThatThrownBy(() -> confirmation.confirm("maintenance-1", 2, OPERATOR))
        .isInstanceOf(ServiceNoticeConfirmation.Refused.class)
        .hasMessageContaining("3");

    assertThat(recipients.count()).isZero();
    assertThat(campaigns.findByCampaignKey("maintenance-1").orElseThrow().getStatus())
        .isEqualTo("DRAFT");
    verify(feed, never())
        .createEventOnce(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any(),
            any());
  }

  @Test
  void onlyTheOperatorWhoCreatedTheDraftDecidesToSend() {
    draft("maintenance-1", FUTURE_DAY);

    assertThatThrownBy(() -> confirmation.confirm("maintenance-1", 3, "another-operator"))
        .isInstanceOf(ServiceNoticeConfirmation.NotTheDraftOwner.class);
    assertThat(recipients.count()).isZero();
  }

  @Test
  void aMaintenanceWindowThatIsAlreadyOverCannotBeConfirmed() {
    draft("maintenance-old", LocalDate.now().minusDays(1));

    assertThatThrownBy(() -> confirmation.confirm("maintenance-old", 3, OPERATOR))
        .isInstanceOf(ServiceNoticeConfirmation.Refused.class);
    assertThat(recipients.count()).isZero();
  }

  @Test
  void anEmptyAudienceIsNotConfirmed() {
    adminAgencies.deleteAll();
    admins.deleteAll();
    draft("maintenance-1", FUTURE_DAY);

    assertThatThrownBy(() -> confirmation.confirm("maintenance-1", 0, OPERATOR))
        .isInstanceOf(ServiceNoticeConfirmation.Refused.class);
  }

  @Test
  void anUnknownDraftIsNotFound() {
    assertThatThrownBy(() -> confirmation.confirm("missing", 3, OPERATOR))
        .isInstanceOf(NoSuchElementException.class);
  }

  private void agencyAdmin(String id, Long tenantId, String email, Long agencyId) {
    var admin =
        admins.saveAndFlush(
            Admin.builder()
                .id(id)
                .type(Admin.AdminType.AGENCY)
                .tenantId(tenantId)
                .username(id)
                .firstName("First")
                .lastName("Last")
                .email(email)
                .build());
    adminAgencies.save(AdminAgency.builder().admin(admin).agencyId(agencyId).build());
    entityManager.flush();
  }

  private void draft(String key, LocalDate day) {
    var draft = new ServiceNoticeCampaign();
    draft.setCampaignKey(key);
    draft.setMaintenanceDate(day);
    draft.setMaintenanceStart(LocalTime.of(14, 0));
    draft.setMaintenanceEnd(LocalTime.of(15, 0));
    draft.setStatusUrl("https://status.operator.dev/maintenance");
    draft.setCreatedByUserId(OPERATOR);
    draft.setCreatedAt(LocalDateTime.of(2026, 10, 1, 12, 0));
    campaigns.saveAndFlush(draft);
  }
}
