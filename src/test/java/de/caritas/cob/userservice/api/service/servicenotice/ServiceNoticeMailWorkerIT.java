package de.caritas.cob.userservice.api.service.servicenotice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.ServiceNoticeCampaign;
import de.caritas.cob.userservice.api.model.ServiceNoticeRecipient;
import de.caritas.cob.userservice.api.model.ServiceNoticeRecipient.MailStatus;
import de.caritas.cob.userservice.api.port.out.ServiceNoticeCampaignRepository;
import de.caritas.cob.userservice.api.port.out.ServiceNoticeRecipientRepository;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailDelivery;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailRouteService;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeAudience.MailTarget;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeMailComposer.Composed;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sender against real outbox rows. Its claims commit in their own transactions, so the test
 * does not wrap itself in one. Only the mail transport, the tenant mail content and the live
 * recipient lookup are stubbed.
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfiguration.class,
  ServiceNoticeMailWorker.class,
  ServiceNoticeMailClaims.class
})
@TestPropertySource(
    properties = {"service.notice.mail.batch-size=2", "service.notice.mail.max-attempts=3"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ServiceNoticeMailWorkerIT {

  private static final TenantSystemEmailRouteService.Route ROUTE =
      new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
  private static final OrisoEmailRenderer.RenderedEmail EMAIL =
      new OrisoEmailRenderer.RenderedEmail("Maintenance", "<p>Maintenance</p>", "Maintenance");

  @Autowired private ServiceNoticeMailWorker worker;
  @Autowired private ServiceNoticeCampaignRepository campaigns;
  @Autowired private ServiceNoticeRecipientRepository recipients;
  @MockitoBean private ServiceNoticeAudience audience;
  @MockitoBean private ServiceNoticeMailComposer composer;
  @MockitoBean private TenantSystemEmailDelivery delivery;

  private ServiceNoticeCampaign campaign;

  @BeforeEach
  void aConfirmedCampaign() {
    clean();
    campaign = campaign("maintenance-1", "CONFIRMED");
    when(delivery.sendConfirmed(anyLong(), any(), any(), anyString(), any(), any(UUID.class)))
        .thenReturn(true);
  }

  @AfterEach
  void clean() {
    recipients.deleteAll();
    campaigns.deleteAll();
  }

  @Test
  void aDueMailIsSentOnceWithItsOwnCorrelationAndRecorded() {
    var row = recipient(campaign, "admin-a", MailStatus.PENDING);
    reachable("admin-a", "lead@centre-a.org");

    worker.dispatchDue();
    worker.dispatchDue();

    verify(delivery, times(1))
        .sendConfirmed(
            7L,
            ROUTE,
            TenantSystemEmailDelivery.Purpose.SERVICE_NOTICE,
            "lead@centre-a.org",
            EMAIL,
            UUID.fromString(row.getCorrelationId()));
    var stored = recipients.findById(row.getId()).orElseThrow();
    assertThat(stored.getMailStatus()).isEqualTo(MailStatus.SENT);
    assertThat(stored.getSentAt()).isNotNull();
  }

  @Test
  void inAppOnlyRowsAndUnconfirmedCampaignsAreNeverMailed() {
    recipient(campaign, "admin-off", MailStatus.NOT_SENT_PREFERENCE_OFF);
    recipient(campaign, "admin-dummy", MailStatus.NOT_SENT_NO_ADDRESS);
    var draft = campaign("maintenance-draft", "DRAFT");
    recipient(draft, "admin-a", MailStatus.PENDING);
    reachable("admin-a", "lead@centre-a.org");

    worker.dispatchDue();

    verify(delivery, never())
        .sendConfirmed(anyLong(), any(), any(), anyString(), any(), any(UUID.class));
  }

  @Test
  void someoneWhoSwitchedTheNoticeOffAfterConfirmationGetsNoMail() {
    var row = recipient(campaign, "admin-a", MailStatus.PENDING);
    when(audience.mailTarget("admin-a")).thenReturn(Optional.empty());

    worker.dispatchDue();

    verify(delivery, never())
        .sendConfirmed(anyLong(), any(), any(), anyString(), any(), any(UUID.class));
    assertThat(recipients.findById(row.getId()).orElseThrow().getMailStatus())
        .isEqualTo(MailStatus.SUPPRESSED);
  }

  @Test
  void eachRunSendsAtMostTheConfiguredBatch() {
    recipient(campaign, "admin-a", MailStatus.PENDING);
    recipient(campaign, "admin-b", MailStatus.PENDING);
    recipient(campaign, "admin-c", MailStatus.PENDING);
    reachable("admin-a", "a@centre.org");
    reachable("admin-b", "b@centre.org");
    reachable("admin-c", "c@centre.org");

    worker.dispatchDue();
    verify(delivery, times(2))
        .sendConfirmed(anyLong(), any(), any(), anyString(), any(), any(UUID.class));

    worker.dispatchDue();
    verify(delivery, times(3))
        .sendConfirmed(anyLong(), any(), any(), anyString(), any(), any(UUID.class));
  }

  @Test
  void aMailServerThatIsNotConfiguredIsRetriedLaterAndGivenUpAfterTheLimit() {
    var row = recipient(campaign, "admin-a", MailStatus.PENDING);
    reachable("admin-a", "lead@centre-a.org");
    doThrow(new PlatformSmtpSettingsProvider.ConfigurationException("not configured"))
        .when(delivery)
        .requireConfigured(ROUTE);

    worker.dispatchDue();
    var deferred = recipients.findById(row.getId()).orElseThrow();
    assertThat(deferred.getMailStatus()).isEqualTo(MailStatus.PENDING);
    assertThat(deferred.getFailureCount()).isEqualTo(1);
    assertThat(deferred.getNextAttemptAtUtc()).isAfter(LocalDateTime.now(ZoneOffset.UTC));

    worker.dispatchDue();
    assertThat(recipients.findById(row.getId()).orElseThrow().getFailureCount()).isEqualTo(1);

    makeDue(row);
    worker.dispatchDue();
    makeDue(row);
    worker.dispatchDue();

    var given = recipients.findById(row.getId()).orElseThrow();
    assertThat(given.getMailStatus()).isEqualTo(MailStatus.FAILED);
    assertThat(given.getFailureCount()).isEqualTo(3);
    verify(delivery, never())
        .sendConfirmed(anyLong(), any(), any(), anyString(), any(), any(UUID.class));
  }

  @Test
  void aTransportErrorWithUnknownOutcomeIsNeverRepeated() {
    var row = recipient(campaign, "admin-a", MailStatus.PENDING);
    reachable("admin-a", "lead@centre-a.org");
    when(delivery.sendConfirmed(anyLong(), any(), any(), anyString(), any(), any(UUID.class)))
        .thenThrow(new RuntimeException("connection reset after DATA"));

    worker.dispatchDue();
    makeDue(row);
    worker.dispatchDue();

    verify(delivery, times(1))
        .sendConfirmed(anyLong(), any(), any(), anyString(), any(), any(UUID.class));
    assertThat(recipients.findById(row.getId()).orElseThrow().getMailStatus())
        .isEqualTo(MailStatus.UNCERTAIN);
  }

  private void reachable(String userId, String email) {
    var target = new MailTarget(email, 7L, OrisoEmailRenderer.Tone.EN);
    when(audience.mailTarget(userId)).thenReturn(Optional.of(target));
    when(composer.compose(any(), eq(target)))
        .thenReturn(Optional.of(new Composed(7L, ROUTE, email, EMAIL)));
  }

  private void makeDue(ServiceNoticeRecipient row) {
    var stored = recipients.findById(row.getId()).orElseThrow();
    stored.setNextAttemptAtUtc(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
    recipients.save(stored);
  }

  private ServiceNoticeCampaign campaign(String key, String status) {
    var draft = new ServiceNoticeCampaign();
    draft.setCampaignKey(key);
    draft.setStatus(status);
    draft.setMaintenanceDate(LocalDate.now().plusDays(7));
    draft.setMaintenanceStart(LocalTime.of(14, 0));
    draft.setMaintenanceEnd(LocalTime.of(15, 0));
    draft.setStatusUrl("https://status.operator.dev/maintenance");
    draft.setCreatedByUserId("platform-operator-1");
    draft.setCreatedAt(LocalDateTime.of(2026, 10, 1, 12, 0));
    return campaigns.saveAndFlush(draft);
  }

  private ServiceNoticeRecipient recipient(
      ServiceNoticeCampaign owner, String userId, MailStatus status) {
    var row = new ServiceNoticeRecipient();
    row.setCampaignId(owner.getId());
    row.setRecipientId(userId);
    row.setTenantId(7L);
    row.setMailStatus(status);
    row.setCorrelationId(UUID.randomUUID().toString());
    row.setNextAttemptAtUtc(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
    return recipients.save(row);
  }
}
