package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdReservationReleaseTaskRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.InviteSendResult;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseProcessor;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseType;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdReservation;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public direct-send seam: an SMTP rejection is a failed operation, not a silently persisted draft
 * that blocks the recipient from trying again.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AccountInviteService.class, IdReservationReleaseProcessor.class})
class AccountInviteDirectSendAtomicIT {

  private static final String RECIPIENT = "owner@example.org";

  @Autowired private AccountInviteService service;
  @MockitoSpyBean private AccountInviteRepository accountInviteRepository;
  @Autowired private InviteEmailTemplateRepository templateRepository;
  @Autowired private IdReservationReleaseTaskRepository reservationReleaseTaskRepository;
  @MockitoSpyBean private InviteEmailDeliveryRepository deliveryRepository;

  @MockitoBean private AuthenticatedUser authenticatedUser;
  @MockitoBean private IdentityEmailOwnerLookup identityEmailOwnerLookup;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private TenantIdAllocationClient tenantIdAllocationClient;
  @MockitoBean private AgencyIdAllocationClient agencyIdAllocationClient;
  @MockitoBean private InviteAcceptUrlBuilder inviteAcceptUrlBuilder;
  @MockitoBean private InviteMailDispatchService inviteMailDispatchService;
  @MockitoBean private InviteEmailDeliveryFailureRecorder deliveryFailureRecorder;

  private Long templateId;

  @BeforeEach
  void setUp() {
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(authenticatedUser.getUsername()).thenReturn("admin@example.org");
    when(identityEmailOwnerLookup.findByEmail(RECIPIENT)).thenReturn(Optional.empty());
    when(tenantIdAllocationClient.reserve(null))
        .thenReturn(new TenantIdReservation(17L, "reservation-17"));
    when(tenantIdAllocationClient.getAvailability(17L)).thenReturn(IdAllocationStatus.RESERVED);
    when(tenantIdAllocationClient.release(17L)).thenReturn(true);
    when(agencyIdAllocationClient.release(23L)).thenReturn(true);
    templateId =
        templateRepository
            .save(
                InviteEmailTemplate.builder()
                    .kind(InviteEmailTemplateKind.TENANT_INVITE)
                    .name("Tenant welcome")
                    .subject("Welcome")
                    .body("Open {{inviteLink}}")
                    .active(true)
                    .createDate(LocalDateTime.now())
                    .build())
            .getId();
  }

  @AfterEach
  void cleanUp() {
    reservationReleaseTaskRepository.deleteAll();
    deliveryRepository.deleteAll();
    accountInviteRepository.deleteAll();
    templateRepository.deleteAll();
  }

  @Test
  void directSend_ShouldRollbackInviteWhenSmtpRejects_AndPermitExactlyOneRetry() {
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), any()))
        .thenReturn("https://example.org/invite/token");
    doThrow(confirmedRejection())
        .doReturn(
            new de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt(
                RECIPIENT, java.time.Instant.parse("2026-09-10T10:00:00Z")))
        .when(inviteMailDispatchService)
        .send(any(), any(), any(), any(), any(), any());

    assertThatThrownBy(() -> service.createAndSendInvite(tenantAdminInvite(), templateId))
        .isInstanceOf(SmtpSendException.class);

    assertThat(accountInviteRepository.count()).isZero();
    verify(tenantIdAllocationClient).release(17L);
    verifyNoInteractions(deliveryFailureRecorder);

    service.createAndSendInvite(tenantAdminInvite(), templateId);

    assertThat(accountInviteRepository.findAll())
        .singleElement()
        .satisfies(
            invite -> {
              assertThat(invite.getRecipientEmail()).isEqualTo(RECIPIENT);
              assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
            });
  }

  @Test
  void directSend_ShouldReleaseTenantAndAgencyReservationsWhenSmtpRejects() {
    when(agencyIdAllocationClient.reserve(null, 17L)).thenReturn(23L);
    when(agencyIdAllocationClient.getAvailability(23L)).thenReturn(IdAllocationStatus.RESERVED);
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), any()))
        .thenReturn("https://example.org/invite/token");
    SmtpSendException failure = confirmedRejection();
    doThrow(failure).when(inviteMailDispatchService).send(any(), any(), any(), any(), any(), any());

    assertThatThrownBy(() -> service.createAndSendInvite(tenantAdminAgencyInvite(), templateId))
        .isSameAs(failure);

    assertThat(accountInviteRepository.count()).isZero();
    verify(tenantIdAllocationClient).release(17L);
    verify(agencyIdAllocationClient).release(23L);
  }

  @Test
  void directSend_ShouldKeepReservationsWhenCommittedClaimCannotBeDeleted() {
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), any()))
        .thenReturn("https://example.org/invite/token");
    SmtpSendException failure = confirmedRejection();
    doThrow(failure).when(inviteMailDispatchService).send(any(), any(), any(), any(), any(), any());
    doThrow(new DataAccessResourceFailureException("database unavailable"))
        .when(accountInviteRepository)
        .deleteById(any());

    assertThatThrownBy(() -> service.createAndSendInvite(tenantAdminInvite(), templateId))
        .isSameAs(failure)
        .satisfies(exception -> assertThat(exception.getSuppressed()).hasSize(1));

    assertThat(accountInviteRepository.findAll()).hasSize(1);
    verify(tenantIdAllocationClient, never()).release(17L);
  }

  @Test
  void directSend_ShouldPersistTenantReleaseRetryAndStillReleaseAgency() {
    when(agencyIdAllocationClient.reserve(null, 17L)).thenReturn(23L);
    when(agencyIdAllocationClient.getAvailability(23L)).thenReturn(IdAllocationStatus.RESERVED);
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), any()))
        .thenReturn("https://example.org/invite/token");
    SmtpSendException failure = confirmedRejection();
    doThrow(failure).when(inviteMailDispatchService).send(any(), any(), any(), any(), any(), any());
    when(tenantIdAllocationClient.release(17L)).thenReturn(false);

    assertThatThrownBy(() -> service.createAndSendInvite(tenantAdminAgencyInvite(), templateId))
        .isSameAs(failure);

    verify(agencyIdAllocationClient).release(23L);
    assertThat(reservationReleaseTaskRepository.findAll())
        .singleElement()
        .satisfies(
            task -> {
              assertThat(task.getAllocationType()).isEqualTo(IdReservationReleaseType.TENANT);
              assertThat(task.getReservedId()).isEqualTo(17L);
              assertThat(task.getAttemptCount()).isEqualTo(1);
              assertThat(task.getLastAttemptAt()).isNotNull();
            });
  }

  @Test
  void directSend_ShouldKeepClaimWhenSmtpDeliveryIsUncertain_AndBlockRetry() {
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), any()))
        .thenReturn("https://example.org/invite/token");
    doThrow(
            new SmtpSendException(
                SmtpSendException.Category.SMTP_TRANSPORT_FAILED,
                SmtpSendException.DeliveryDisposition.DELIVERY_UNCERTAIN,
                "SMTP connection closed after DATA"))
        .when(inviteMailDispatchService)
        .send(any(), any(), any(), any(), any(), any());

    assertThatThrownBy(() -> service.createAndSendInvite(tenantAdminInvite(), templateId))
        .isInstanceOf(SmtpSendException.class);

    AccountInvite retained = accountInviteRepository.findAll().getFirst();
    assertThat(retained)
        .satisfies(
            invite -> {
              assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
              assertThat(invite.getTokenHash()).isNotBlank();
              assertThat(invite.getActiveRecipientKey()).isEqualTo(RECIPIENT);
            });
    verify(deliveryFailureRecorder)
        .recordFailure(
            org.mockito.ArgumentMatchers.eq(retained.getId()),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verify(tenantIdAllocationClient, never()).release(17L);

    assertThatThrownBy(() -> service.createAndSendInvite(tenantAdminInvite(), templateId))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses
                .CustomValidationHttpStatusException.class);
    verify(inviteMailDispatchService).send(any(), any(), any(), any(), any(), any());
  }

  @Test
  void directSend_ShouldKeepUsableClaimWhenDeliveryAuditFailsAfterSmtp() {
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), any()))
        .thenReturn("https://example.org/invite/token");
    when(inviteMailDispatchService.send(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt(
                RECIPIENT, java.time.Instant.parse("2026-09-10T10:00:00Z")));
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("audit unavailable"))
        .when(deliveryRepository)
        .saveAndFlush(any());

    InviteSendResult result = service.createAndSendInvite(tenantAdminInvite(), templateId);

    assertThat(result.rawToken()).isNotBlank();
    assertThat(accountInviteRepository.findById(result.invite().getId()))
        .get()
        .satisfies(
            invite -> {
              assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
              assertThat(invite.getTokenHash()).isNotBlank();
              assertThat(invite.getActiveRecipientKey()).isEqualTo(RECIPIENT);
            });
  }

  @Test
  void directSend_ShouldCommitRecipientClaimBeforeSmtp_AndRejectRapidSecondClick()
      throws Exception {
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), any()))
        .thenReturn("https://example.org/invite/token");
    CountDownLatch firstMailStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstMail = new CountDownLatch(1);
    AtomicInteger mailCalls = new AtomicInteger();
    when(inviteMailDispatchService.send(any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              if (mailCalls.incrementAndGet() == 1) {
                firstMailStarted.countDown();
                if (!releaseFirstMail.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("test timed out waiting to release first mail");
                }
              }
              return new de.caritas.cob.userservice.api.service.accountinvite.mail
                  .InviteMailSendReceipt(
                  RECIPIENT, java.time.Instant.parse("2026-09-10T10:00:00Z"));
            });

    CompletableFuture<Void> firstRequest =
        CompletableFuture.runAsync(
            () -> service.createAndSendInvite(tenantAdminInvite(), templateId));
    assertThat(firstMailStarted.await(5, TimeUnit.SECONDS)).isTrue();

    try {
      assertThatThrownBy(() -> service.createAndSendInvite(tenantAdminInvite(), templateId))
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses
                  .CustomValidationHttpStatusException.class);
    } finally {
      releaseFirstMail.countDown();
      firstRequest.get(5, TimeUnit.SECONDS);
    }

    assertThat(accountInviteRepository.findAll()).hasSize(1);
    assertThat(mailCalls).hasValue(1);
  }

  @Test
  void createInvite_ShouldExpireElapsedClaimAndPermitReinvite() {
    AccountInvite expired = persistedInvite(AccountInviteStatus.EMAIL_SENT, RECIPIENT);
    expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    expired.setActiveRecipientKey(RECIPIENT);
    expired = accountInviteRepository.saveAndFlush(expired);

    AccountInvite replacement = service.createInvite(counsellorInvite("Ada"));

    assertThat(accountInviteRepository.findById(expired.getId()))
        .get()
        .satisfies(
            invite -> {
              assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.EXPIRED);
              assertThat(invite.getActiveRecipientKey()).isNull();
            });
    assertThat(replacement.getActiveRecipientKey()).isEqualTo(RECIPIENT);
  }

  @Test
  void createInvite_ShouldExpireClaimedDraftThatWasNeverOpenedAndPermitReinvite() {
    AccountInvite expiredDraft = persistedInvite(AccountInviteStatus.DRAFT, RECIPIENT);
    expiredDraft.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    expiredDraft.setActiveRecipientKey(RECIPIENT);
    expiredDraft = accountInviteRepository.saveAndFlush(expiredDraft);

    AccountInvite replacement = service.createInvite(counsellorInvite("Ada"));

    assertThat(accountInviteRepository.findById(expiredDraft.getId()))
        .get()
        .satisfies(
            invite -> {
              assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.EXPIRED);
              assertThat(invite.getActiveRecipientKey()).isNull();
            });
    assertThat(replacement.getActiveRecipientKey()).isEqualTo(RECIPIENT);
  }

  @Test
  void acceptInvite_ShouldCommitExpiredStateAndReleaseRecipientClaim() {
    String rawToken = "expired-invite-token";
    AccountInvite expired = persistedInvite(AccountInviteStatus.EMAIL_SENT, RECIPIENT);
    expired.setTokenHash(AccountInviteService.hash(rawToken));
    expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    expired.setActiveRecipientKey(RECIPIENT);
    expired = accountInviteRepository.saveAndFlush(expired);

    assertThatThrownBy(() -> service.acceptInvite(rawToken, "user-1"))
        .isInstanceOf(AccountInviteLinkException.class);

    assertThat(accountInviteRepository.findById(expired.getId()))
        .get()
        .satisfies(
            invite -> {
              assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.EXPIRED);
              assertThat(invite.getActiveRecipientKey()).isNull();
            });
  }

  @Test
  void resendInvite_ShouldRejectExistingRecipientClaimBeforeMailDispatch() {
    AccountInvite oldInvite = persistedInvite(AccountInviteStatus.EXPIRED, RECIPIENT);
    oldInvite.setActiveRecipientKey(null);
    oldInvite = accountInviteRepository.saveAndFlush(oldInvite);
    AccountInvite legacyConflict = persistedInvite(AccountInviteStatus.EMAIL_SENT, RECIPIENT);
    legacyConflict.setActiveRecipientKey(null);
    accountInviteRepository.saveAndFlush(legacyConflict);

    Long oldInviteId = oldInvite.getId();
    assertThatThrownBy(
            () ->
                service.resendInvite(
                    new AccountInviteService.SendInviteCommand(oldInviteId, templateId)))
        .isInstanceOf(CustomValidationHttpStatusException.class);

    verify(inviteMailDispatchService, never()).send(any(), any(), any(), any(), any(), any());
  }

  @Test
  void resendInvite_ShouldCommitClaimHandoverBeforeMailDispatch() throws Exception {
    AccountInvite oldInvite =
        accountInviteRepository.saveAndFlush(
            persistedInvite(AccountInviteStatus.EMAIL_SENT, RECIPIENT));
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), any()))
        .thenReturn("https://example.org/invite/replacement");
    CountDownLatch mailStarted = new CountDownLatch(1);
    CountDownLatch releaseMail = new CountDownLatch(1);
    when(inviteMailDispatchService.send(any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              mailStarted.countDown();
              if (!releaseMail.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timed out waiting to release mail");
              }
              return new de.caritas.cob.userservice.api.service.accountinvite.mail
                  .InviteMailSendReceipt(
                  RECIPIENT, java.time.Instant.parse("2026-09-10T10:00:00Z"));
            });

    CompletableFuture<Void> resend =
        CompletableFuture.runAsync(
            () ->
                service.resendInvite(
                    new AccountInviteService.SendInviteCommand(oldInvite.getId(), templateId)));
    assertThat(mailStarted.await(5, TimeUnit.SECONDS)).isTrue();

    try {
      assertThat(accountInviteRepository.findById(oldInvite.getId()))
          .get()
          .satisfies(
              committedOld -> {
                assertThat(committedOld.getStatus()).isEqualTo(AccountInviteStatus.SUPERSEDED);
                assertThat(committedOld.getActiveRecipientKey()).isNull();
              });
      assertThat(accountInviteRepository.findAll())
          .filteredOn(invite -> !invite.getId().equals(oldInvite.getId()))
          .singleElement()
          .satisfies(
              replacement -> {
                assertThat(replacement.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
                assertThat(replacement.getActiveRecipientKey()).isEqualTo(RECIPIENT);
                assertThat(replacement.getTokenHash()).isNotBlank();
              });
    } finally {
      releaseMail.countDown();
      resend.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void resendInvite_ShouldRestoreOldInviteWhenSmtpConfirmedNotSent() {
    AccountInvite oldInvite =
        accountInviteRepository.saveAndFlush(
            persistedInvite(AccountInviteStatus.EMAIL_SENT, RECIPIENT));
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), any()))
        .thenReturn("https://example.org/invite/replacement");
    SmtpSendException failure = confirmedRejection();
    doThrow(failure).when(inviteMailDispatchService).send(any(), any(), any(), any(), any(), any());

    assertThatThrownBy(
            () ->
                service.resendInvite(
                    new AccountInviteService.SendInviteCommand(oldInvite.getId(), templateId)))
        .isSameAs(failure);

    assertThat(accountInviteRepository.findAll())
        .singleElement()
        .satisfies(
            restored -> {
              assertThat(restored.getId()).isEqualTo(oldInvite.getId());
              assertThat(restored.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
              assertThat(restored.getActiveRecipientKey()).isEqualTo(RECIPIENT);
              assertThat(restored.getSupersededByInviteId()).isNull();
            });
  }

  @Test
  void resendInvite_ShouldKeepCommittedReplacementWhenDeliveryAuditFails() {
    AccountInvite oldInvite =
        accountInviteRepository.saveAndFlush(
            persistedInvite(AccountInviteStatus.EMAIL_SENT, RECIPIENT));
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), any()))
        .thenReturn("https://example.org/invite/replacement");
    when(inviteMailDispatchService.send(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt(
                RECIPIENT, java.time.Instant.parse("2026-09-10T10:00:00Z")));
    doThrow(new DataAccessResourceFailureException("audit unavailable"))
        .when(deliveryRepository)
        .saveAndFlush(any());

    InviteSendResult result =
        service.resendInvite(
            new AccountInviteService.SendInviteCommand(oldInvite.getId(), templateId));

    assertThat(result.rawToken()).isNotBlank();
    assertThat(accountInviteRepository.findById(oldInvite.getId()))
        .get()
        .extracting(AccountInvite::getStatus)
        .isEqualTo(AccountInviteStatus.SUPERSEDED);
    assertThat(accountInviteRepository.findById(result.invite().getId()))
        .get()
        .satisfies(
            replacement -> {
              assertThat(replacement.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
              assertThat(replacement.getActiveRecipientKey()).isEqualTo(RECIPIENT);
            });
  }

  @Test
  void createInvite_ShouldNotMisreportUnrelatedIntegrityFailureAsEmailConflict() {
    CreateAccountInviteCommand command = counsellorInvite("x".repeat(300));

    assertThatThrownBy(() -> service.createInvite(command))
        .isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(CustomValidationHttpStatusException.class);
  }

  private CreateAccountInviteCommand tenantAdminInvite() {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.TENANT_ADMIN,
        null,
        RECIPIENT,
        "Ada",
        "Lovelace",
        null,
        null,
        30L,
        IdAllocationMode.AUTO,
        null);
  }

  private static SmtpSendException confirmedRejection() {
    return new SmtpSendException(
        SmtpSendException.Category.SMTP_TRANSPORT_FAILED,
        SmtpSendException.DeliveryDisposition.CONFIRMED_NOT_SENT,
        "SMTP refused the message");
  }

  private CreateAccountInviteCommand tenantAdminAgencyInvite() {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.TENANT_ADMIN,
        null,
        RECIPIENT,
        "Ada",
        "Lovelace",
        null,
        null,
        30L,
        IdAllocationMode.AUTO,
        IdAllocationMode.AUTO);
  }

  private static CreateAccountInviteCommand counsellorInvite(String firstName) {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.COUNSELLOR,
        null,
        RECIPIENT,
        firstName,
        "Lovelace",
        null,
        null,
        30L,
        null,
        null);
  }

  private static AccountInvite persistedInvite(AccountInviteStatus status, String recipient) {
    LocalDateTime now = LocalDateTime.now();
    return AccountInvite.builder()
        .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
        .tenantId(17L)
        .tenantIdReservationToken("reservation-17")
        .recipientEmail(recipient)
        .activeRecipientKey(recipient)
        .expiresAt(now.plusDays(30))
        .status(status)
        .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
        .emailVerificationStatus(EmailVerificationStatus.PENDING)
        .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
        .createdByUserId("admin-1")
        .createdByUsername("admin@example.org")
        .createDate(now)
        .updateDate(now)
        .build();
  }
}
