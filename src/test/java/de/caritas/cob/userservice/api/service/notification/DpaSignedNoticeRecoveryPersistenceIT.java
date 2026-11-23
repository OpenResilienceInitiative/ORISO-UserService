package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.observability.DpaSignedNoticeMetrics;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.DpaSignedNotice;
import de.caritas.cob.userservice.api.model.DpaSignedNotice.NoticeRole;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.DpaSignedNoticeRepository;
import de.caritas.cob.userservice.api.port.out.IdentityLocaleLookup;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.DpaSignatureDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DpaSignedNoticeRecoveryPersistenceIT {

  private static final long TENANT_ID = 91704L;
  private static final String VERSION = "2026-11-01T07:00:00";
  private static final String SIGNED_AT = "2026-11-19T10:10:00.123";
  private static final String RECIPIENT = "forwarder@example.test";

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
    var signature =
        new DpaSignatureDTO()
            .tenantId(TENANT_ID)
            .dpaVersion(VERSION)
            .status("SIGNED")
            .source("FORWARDED_EXTERNAL")
            .signedAt(SIGNED_AT)
            .forwardedByUserId("forwarder");
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
}
