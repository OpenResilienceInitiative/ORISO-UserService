package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.config.observability.DpaSignedNoticeMetrics;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.exception.SmtpSendException.Category;
import de.caritas.cob.userservice.api.exception.SmtpSendException.DeliveryDisposition;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.DpaSignedNotice;
import de.caritas.cob.userservice.api.model.DpaSignedNotice.NoticeRole;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.DpaSignedNoticeRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLocaleLookup;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.notification.DpaAdminNoticeOutboxConsumer.Event;
import de.caritas.cob.userservice.api.service.notification.DpaAdminNoticeOutboxConsumer.VerifiedSignature;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.DpaSignatureDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DpaSignedNoticeRecoveryPersistenceIT {

  private static final long TENANT_ID = 91704L;
  private static final String VERSION = "2026-11-01T07:00:00";
  private static final String SIGNED_AT = "2026-11-19T10:10:00.123";
  private static final String RECIPIENT = "forwarder@example.test";
  private static final Event EVENT =
      new Event(1704L, TENANT_ID, Instant.parse("2026-11-19T09:10:00.123Z").toEpochMilli());

  @Autowired private DpaSignedNoticeRepository notices;
  @Autowired private PlatformTransactionManager transactionManager;

  @AfterEach
  void removeCommittedFixtures() {
    notices.deleteAllInBatch(
        notices.findAll().stream()
            .filter(notice -> notice.getTenantId().equals(TENANT_ID))
            .toList());
  }

  @Test
  void aNewWorkerRecoversAPersistedAdminClaimAndIgnoresOldAndSignerRows() {
    var currentSender = mock(InviteMailDispatchService.class);
    var interrupted = service(currentSender, true);
    interrupted.onSignatureHint(TENANT_ID);

    verifyNoInteractions(currentSender);
    var pending =
        notices.findAll().stream()
            .filter(
                notice ->
                    notice.getTenantId().equals(TENANT_ID)
                        && VERSION.equals(notice.getDpaVersion()))
            .findFirst()
            .orElseThrow();
    assertThat(pending.getAdminRecoverySignatureAt()).isEqualTo(SIGNED_AT);
    assertThat(pending.getSentAt()).isNull();
    assertThat(pending.getAdminNextAttemptAt()).isNotNull();

    var due = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
    pending.setAdminNextAttemptAt(due);
    notices.saveAndFlush(pending);
    var historic = saveClaim("legacy", NoticeRole.ADMIN, null, null);
    var signer = saveClaim("signer", NoticeRole.SIGNER, SIGNED_AT, due);

    var recoveredSender = mock(InviteMailDispatchService.class);
    var restarted = service(recoveredSender, false);
    restarted.retryPendingAdminNotices();
    restarted.retryPendingAdminNotices();
    restarted.onSignatureHint(TENANT_ID);

    verify(recoveredSender, times(1))
        .send(eq(RECIPIENT), any(), any(), isNull(), eq(TENANT_ID), eq("de"), any());
    assertThat(notices.findById(pending.getId()).orElseThrow().getSentAt()).isNotNull();
    assertThat(notices.findById(historic.getId()).orElseThrow().getSentAt()).isNull();
    assertThat(notices.findById(signer.getId()).orElseThrow().getSentAt()).isNull();
  }

  @Test
  void aDelayedWorkerCannotUseTheReservationOfAWorkerThatRejectedAChangedAddress() {
    var start = Instant.parse("2026-11-19T11:00:00.123456789Z");
    var clock = mock(Clock.class);
    when(clock.instant()).thenReturn(start);
    var notice = saveClaim(VERSION, NoticeRole.ADMIN, SIGNED_AT, utc(start.minusSeconds(1)));
    var delayedMail = mock(InviteMailDispatchService.class);
    var changedAddressMail = mock(InviteMailDispatchService.class);
    var changedAddressRecovery =
        service(changedAddressMail, clock, "changed@example.test", () -> {});
    var delayedRecovery =
        service(
            delayedMail,
            clock,
            RECIPIENT,
            () -> {
              when(clock.instant()).thenReturn(start.plusSeconds(61));
              changedAddressRecovery.retryPendingAdminNotices();
            });

    delayedRecovery.retryPendingAdminNotices();

    verifyNoInteractions(delayedMail, changedAddressMail);
    var retained = notices.findById(notice.getId()).orElseThrow();
    assertThat(retained.getAdminNextAttemptAt()).isEqualTo(utc(start.plusSeconds(61 + 900)));
    assertThat(retained.getRecipientEmail()).isEqualTo(RECIPIENT);
    assertThat(retained.getAdminRecoverySignatureAt()).isEqualTo(SIGNED_AT);
    assertThat(retained.getSentAt()).isNull();

    when(clock.instant()).thenReturn(start.plusSeconds(61 + 901));
    var verifiedMail = mock(InviteMailDispatchService.class);
    var verifiedRecovery = service(verifiedMail, clock, RECIPIENT, () -> {});
    verifiedRecovery.retryPendingAdminNotices();
    verifiedRecovery.retryPendingAdminNotices();

    verify(verifiedMail)
        .send(eq(RECIPIENT), any(), any(), isNull(), eq(TENANT_ID), eq("de"), any());
    assertThat(notices.findById(notice.getId()).orElseThrow().getSentAt()).isNotNull();
  }

  @Test
  void anExpiredReservationIsRejectedEvenWhenNoOtherWorkerHasClaimedTheRow() {
    var start = Instant.parse("2026-11-19T11:00:00.123456789Z");
    var clock = mock(Clock.class);
    when(clock.instant()).thenReturn(start);
    var notice = saveClaim(VERSION, NoticeRole.ADMIN, SIGNED_AT, utc(start.minusSeconds(1)));
    var mail = mock(InviteMailDispatchService.class);
    var recovery =
        service(
            mail, clock, RECIPIENT, () -> when(clock.instant()).thenReturn(start.plusSeconds(60)));

    recovery.retryPendingAdminNotices();

    verifyNoInteractions(mail);
    var retained = notices.findById(notice.getId()).orElseThrow();
    assertThat(retained.getAdminNextAttemptAt()).isEqualTo(utc(start.plusSeconds(60)));
    assertThat(retained.getSentAt()).isNull();
  }

  private static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  @Test
  void typedAcknowledgementRunsAfterCommitAndCanBeRetriedAfterALostResponse() {
    var mail = mock(InviteMailDispatchService.class);
    var http = new RestTemplate();
    var server = MockRestServiceServer.bindTo(http).build();
    var identity = mock(IdentityClientConfig.class);
    when(identity.isFiveActorMode()).thenReturn(true);
    when(identity.taskAccessToken(TaskPurpose.NOTIFICATION_DISPATCH))
        .thenReturn("synthetic-task-token");
    var headers = new SecurityHeaderSupplier(new AuthenticatedUser());
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "csrfHeader");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "csrfCookie");
    var client =
        new TenantDpaSignatureReadClient(
            headers,
            mock(IdentityAuthentication.class),
            identity,
            mock(TenantAdminServiceApiControllerFactory.class),
            http);
    ReflectionTestUtils.setField(client, "tenantServiceApiUrl", "https://tenant.example.test");
    ReflectionTestUtils.setField(client, "outboxReadEnabled", true);
    String exactUrl = "https://tenant.example.test/internal/dpa-admin-notice-outbox/" + EVENT.id();
    String proof =
        """
        {"id":%d,"tenantId":%d,"signedAtEpochMillis":%d,"dpaVersion":"%s",
         "signedAt":"%s","forwardedByUserId":"forwarder","signerName":"Signer"}
        """
            .formatted(
                EVENT.id(), EVENT.tenantId(), EVENT.signedAtEpochMillis(), VERSION, SIGNED_AT);
    String body = "{\"signedAtEpochMillis\":" + EVENT.signedAtEpochMillis() + "}";
    server
        .expect(requestTo(exactUrl))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(proof, MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(exactUrl + "/acknowledgements"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(body))
        .andRespond(
            request -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(
                      notices
                          .findByAdminSourceEventId(EVENT.id())
                          .orElseThrow()
                          .getRecipientEmail())
                  .isEqualTo(RECIPIENT);
              throw new IOException("synthetic lost response");
            });
    server
        .expect(requestTo(exactUrl))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(proof, MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(exactUrl + "/acknowledgements"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(body))
        .andRespond(withNoContent());
    server
        .expect(requestTo(exactUrl))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(proof, MediaType.APPLICATION_JSON));

    var failedAck = new DpaAdminNoticeOutboxConsumer(service(mail, false), client);
    assertThatThrownBy(() -> failedAck.accept(EVENT)).isInstanceOf(IllegalStateException.class);
    var newWorker = new DpaAdminNoticeOutboxConsumer(service(mail, false), client);
    assertThat(newWorker.accept(EVENT)).isTrue();
    var claim = notices.findByAdminSourceEventId(EVENT.id()).orElseThrow();
    claim.setAdminNextAttemptAt(null);
    notices.saveAndFlush(claim);
    assertThat(newWorker.accept(EVENT)).isFalse();
    assertThat(notices.findByAdminSourceEventId(EVENT.id())).isPresent();
    server.verify();
    verifyNoInteractions(mail);
  }

  @Test
  void twoWorkersAcceptOneEventWithoutDuplicatingTheDurableClaim() throws Exception {
    var mail = mock(InviteMailDispatchService.class);
    var ready = new CyclicBarrier(2);
    var acknowledgements = new ConcurrentLinkedQueue<Event>();
    var first =
        new DpaAdminNoticeOutboxConsumer(
            service(mail, false), this::readExact, acknowledgements::add);
    var second =
        new DpaAdminNoticeOutboxConsumer(
            service(mail, false), this::readExact, acknowledgements::add);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var acceptedFirst =
          workers.submit(
              () -> {
                ready.await(10, TimeUnit.SECONDS);
                return first.accept(EVENT);
              });
      var acceptedSecond =
          workers.submit(
              () -> {
                ready.await(10, TimeUnit.SECONDS);
                return second.accept(EVENT);
              });
      assertThat(acceptedFirst.get(15, TimeUnit.SECONDS)).isTrue();
      assertThat(acceptedSecond.get(15, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(acknowledgements).containsExactly(EVENT, EVENT);
    assertThat(notices.findAll()).filteredOn(row -> row.getTenantId().equals(TENANT_ID)).hasSize(1);
    verifyNoInteractions(mail);
  }

  @Test
  void exactClaimSurvivesExplicitNonDeliveryAndCanBeRecoveredByAnotherWorker() {
    var start = Instant.parse("2026-11-19T11:00:00Z");
    var clock = mock(Clock.class);
    when(clock.instant()).thenReturn(start);
    var rejectedMail = mock(InviteMailDispatchService.class);
    doThrow(
            new SmtpSendException(
                Category.SMTP_TRANSPORT_FAILED,
                DeliveryDisposition.CONFIRMED_NOT_SENT,
                "synthetic refusal"))
        .when(rejectedMail)
        .send(eq(RECIPIENT), any(), any(), isNull(), eq(TENANT_ID), eq("de"), any());
    var firstWorker =
        new DpaAdminNoticeOutboxConsumer(
            service(rejectedMail, clock, RECIPIENT, () -> {}), this::readExact, event -> {});
    assertThat(firstWorker.accept(EVENT)).isTrue();
    var accepted = notices.findByAdminSourceEventId(EVENT.id()).orElseThrow();

    when(clock.instant()).thenReturn(start.plusSeconds(61));
    firstWorker.recover();
    var retained = notices.findByAdminSourceEventId(EVENT.id()).orElseThrow();
    assertThat(retained.getId()).isEqualTo(accepted.getId());
    assertThat(retained.getAdminSourceSignedAtEpochMillis()).isEqualTo(EVENT.signedAtEpochMillis());
    assertThat(retained.getAdminNextAttemptAt()).isAfter(utc(start.plusSeconds(61)));
    assertThat(retained.getSentAt()).isNull();

    when(clock.instant())
        .thenReturn(retained.getAdminNextAttemptAt().toInstant(ZoneOffset.UTC).plusSeconds(1));
    var verifiedMail = mock(InviteMailDispatchService.class);
    var newWorker =
        new DpaAdminNoticeOutboxConsumer(
            service(verifiedMail, clock, RECIPIENT, () -> {}), this::readExact, event -> {});
    newWorker.recover();
    newWorker.recover();
    verify(verifiedMail)
        .send(eq(RECIPIENT), any(), any(), isNull(), eq(TENANT_ID), eq("de"), any());
    assertThat(notices.findByAdminSourceEventId(EVENT.id()).orElseThrow().getSentAt()).isNotNull();
  }

  @Test
  void exactAcceptanceRejectsUnverifiedEventsLegacyCollisionsAndChangedRecipients() {
    var mail = mock(InviteMailDispatchService.class);
    var acknowledged = new java.util.ArrayList<Event>();
    var consumer =
        new DpaAdminNoticeOutboxConsumer(service(mail, false), this::readExact, acknowledged::add);
    var wrongId = new Event(EVENT.id() + 1, EVENT.tenantId(), EVENT.signedAtEpochMillis());
    var wrongTime = new Event(EVENT.id(), EVENT.tenantId(), EVENT.signedAtEpochMillis() + 1);
    assertThat(consumer.accept(wrongId)).isFalse();
    assertThat(consumer.accept(wrongTime)).isFalse();
    var oldClaim = saveClaim(VERSION, NoticeRole.ADMIN, null, null);
    assertThat(consumer.accept(EVENT)).isFalse();
    assertThat(notices.findByAdminSourceEventId(EVENT.id())).isEmpty();
    assertThat(acknowledged).isEmpty();
    notices.deleteById(oldClaim.getId());

    assertThat(consumer.accept(EVENT)).isTrue();
    var changedConsumer =
        new DpaAdminNoticeOutboxConsumer(
            service(mail, Clock.systemUTC(), "changed@example.test", () -> {}),
            this::readExact,
            acknowledged::add);
    assertThat(changedConsumer.accept(EVENT)).isFalse();
    assertThat(acknowledged).containsExactly(EVENT);
    verifyNoInteractions(mail);
  }

  @Test
  void ambiguousExactDeliveryRetainsItsOriginalClaimAndDoesNotIssueANewAcknowledgement() {
    var start = Instant.parse("2026-11-19T11:00:00Z");
    var clock = mock(Clock.class);
    when(clock.instant()).thenReturn(start);
    var mail = mock(InviteMailDispatchService.class);
    doThrow(
            new SmtpSendException(
                Category.SMTP_TRANSPORT_FAILED,
                DeliveryDisposition.DELIVERY_UNCERTAIN,
                "synthetic unconfirmed handoff"))
        .when(mail)
        .send(eq(RECIPIENT), any(), any(), isNull(), eq(TENANT_ID), eq("de"), any());

    var acknowledged = new java.util.ArrayList<Event>();
    var worker =
        new DpaAdminNoticeOutboxConsumer(
            service(mail, clock, RECIPIENT, () -> {}), this::readExact, acknowledged::add);
    assertThat(worker.accept(EVENT)).isTrue();
    var accepted = notices.findByAdminSourceEventId(EVENT.id()).orElseThrow();

    when(clock.instant()).thenReturn(start.plusSeconds(61));
    worker.recover();
    when(clock.instant()).thenReturn(start.plusSeconds(3600));
    worker.recover();
    assertThat(worker.accept(EVENT)).isFalse();

    var retained = notices.findByAdminSourceEventId(EVENT.id()).orElseThrow();
    assertThat(retained.getId()).isEqualTo(accepted.getId());
    assertThat(retained.getRecipientEmail()).isEqualTo(RECIPIENT);
    assertThat(retained.getAdminNextAttemptAt()).isNull();
    assertThat(retained.getSentAt()).isNull();
    assertThat(acknowledged).containsExactly(EVENT);
    verify(mail).send(eq(RECIPIENT), any(), any(), isNull(), eq(TENANT_ID), eq("de"), any());
  }

  private Optional<VerifiedSignature> readExact(Event ignored) {
    return Optional.of(new VerifiedSignature(EVENT, signature()));
  }

  private DpaSignedNotice saveClaim(
      String version, NoticeRole role, String signingTime, LocalDateTime retryAt) {
    return notices.saveAndFlush(
        DpaSignedNotice.builder()
            .tenantId(TENANT_ID)
            .dpaVersion(version)
            .noticeRole(role)
            .recipientEmail(RECIPIENT)
            .adminRecoverySignatureAt(signingTime)
            .adminNextAttemptAt(retryAt)
            .createDate(LocalDateTime.now(ZoneOffset.UTC))
            .build());
  }

  private DpaSignedNoticeService service(
      InviteMailDispatchService mail, boolean failBeforeHandoff) {
    return service(
        mail,
        Clock.systemUTC(),
        RECIPIENT,
        () -> {
          if (failBeforeHandoff) {
            throw new IllegalStateException("simulated interruption before handoff");
          }
        });
  }

  private DpaSignedNoticeService service(
      InviteMailDispatchService mail, Clock clock, String address, Runnable duringPreparation) {
    var signatures = mock(TenantDpaSignatureReadClient.class);
    var signature = signature();
    when(signatures.readSignatures(TENANT_ID)).thenReturn(List.of(signature));
    var admins = mock(AdminRepository.class);
    when(admins.findById("forwarder"))
        .thenReturn(
            Optional.of(
                Admin.builder()
                    .id("forwarder")
                    .tenantId(TENANT_ID)
                    .username("forwarder")
                    .firstName("Synthetic")
                    .lastName("Forwarder")
                    .email(address)
                    .type(Admin.AdminType.TENANT)
                    .build()));
    var locales = mock(IdentityLocaleLookup.class);
    when(locales.findLocaleById("forwarder")).thenReturn(Optional.of("de"));
    var templates = mock(InviteEmailTemplateRepository.class);
    var activeTemplates =
        when(
            templates.findByKindAndActiveTrueAndTenantIdIsNullOrderByCreateDateDesc(
                InviteEmailTemplateKind.DPA_SIGNED_NOTICE));
    activeTemplates.thenAnswer(
        invocation -> {
          duringPreparation.run();
          return List.of();
        });
    var tenants = mock(TenantService.class);
    when(tenants.getRestrictedTenantData(TENANT_ID))
        .thenReturn(new RestrictedTenantDTO().name("Synthetic tenant"));
    var service =
        new DpaSignedNoticeService(
            signatures,
            notices,
            admins,
            mock(AccountInviteRepository.class),
            locales,
            templates,
            mail,
            tenants,
            transactionManager,
            mock(DpaSignedNoticeMetrics.class),
            new AdminPanelUrl("https://admin.example.test"),
            clock);
    service.useExecutor(Runnable::run);
    return service;
  }

  private DpaSignatureDTO signature() {
    return new DpaSignatureDTO()
        .tenantId(TENANT_ID)
        .dpaVersion(VERSION)
        .status("SIGNED")
        .source("FORWARDED_EXTERNAL")
        .signedAt(SIGNED_AT)
        .forwardedByUserId("forwarder");
  }
}
