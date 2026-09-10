package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.InviteSendResult;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
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
@Import(AccountInviteService.class)
class AccountInviteDirectSendAtomicIT {

  private static final String RECIPIENT = "owner@example.org";

  @Autowired private AccountInviteService service;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private InviteEmailTemplateRepository templateRepository;
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
    accountInviteRepository.deleteAll();
    templateRepository.deleteAll();
  }

  @Test
  void directSend_ShouldRollbackInviteWhenSmtpRejects_AndPermitExactlyOneRetry() {
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), any()))
        .thenReturn("https://example.org/invite/token");
    doThrow(new SmtpSendException("SMTP refused the message"))
        .doReturn(
            new de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt(
                RECIPIENT, java.time.Instant.parse("2026-09-10T10:00:00Z")))
        .when(inviteMailDispatchService)
        .send(any(), any(), any(), any(), any(), any());

    assertThatThrownBy(() -> service.createAndSendInvite(tenantAdminInvite(), templateId))
        .isInstanceOf(SmtpSendException.class);

    assertThat(accountInviteRepository.count()).isZero();
    verify(tenantIdAllocationClient).release(17L);

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
    doThrow(new SmtpSendException("SMTP refused the message"))
        .when(inviteMailDispatchService)
        .send(any(), any(), any(), any(), any(), any());

    assertThatThrownBy(() -> service.createAndSendInvite(tenantAdminAgencyInvite(), templateId))
        .isInstanceOf(SmtpSendException.class);

    assertThat(accountInviteRepository.count()).isZero();
    verify(tenantIdAllocationClient).release(17L);
    verify(agencyIdAllocationClient).release(23L);
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
}
