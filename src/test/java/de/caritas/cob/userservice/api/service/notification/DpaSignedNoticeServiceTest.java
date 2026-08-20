package de.caritas.cob.userservice.api.service.notification;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.DpaSignedNotice;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.DpaSignedNoticeRepository;
import de.caritas.cob.userservice.api.port.out.IdentityLocaleLookup;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.DpaSignatureDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * DPA_SIGNED_NOTICE recipient resolution, exactly-once behaviour and the untrusted-hint contract
 * (ORISO-UserService#1005).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DpaSignedNoticeServiceTest {

  private static final Long TENANT_ID = 42L;
  private static final String DPA_VERSION = "2026-07-01T12:00:00";

  @Mock private TenantDpaSignatureReadClient signatureReadClient;
  @Mock private DpaSignedNoticeRepository noticeRepository;
  @Mock private AdminRepository adminRepository;
  @Mock private AccountInviteRepository accountInviteRepository;
  @Mock private IdentityLocaleLookup identityLocaleLookup;

  @Mock
  private org.springframework.beans.factory.ObjectProvider<IdentityLocaleLookup>
      localeLookupProvider;

  @Mock private InviteEmailTemplateRepository templateRepository;
  @Mock private InviteMailDispatchService inviteMailDispatchService;
  @Mock private TenantService tenantService;
  @Mock private PlatformTransactionManager transactionManager;

  private DpaSignedNoticeService service;

  @BeforeEach
  void setUp() {
    TransactionStatus transactionStatus = new SimpleTransactionStatus();
    when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
    when(noticeRepository.save(any(DpaSignedNotice.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(templateRepository.findByKindAndActiveTrueOrderByCreateDateDesc(
            InviteEmailTemplateKind.DPA_SIGNED_NOTICE))
        .thenReturn(List.of());
    when(identityLocaleLookup.findLocaleById(anyString())).thenReturn(Optional.empty());
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().name("Träger Nord e.V."));
    service =
        new DpaSignedNoticeService(
            signatureReadClient,
            noticeRepository,
            adminRepository,
            accountInviteRepository,
            identityLocaleLookup,
            templateRepository,
            inviteMailDispatchService,
            tenantService,
            transactionManager,
            new AdminPanelUrl("https://admin.example.org"));
    // the endpoint dispatches asynchronously; run it inline so these tests keep asserting the
    // work itself rather than the hand-off
    service.useExecutor(Runnable::run);
  }

  private static Admin forwardingAdmin() {
    return Admin.builder()
        .id("kc-admin-1")
        .username("toni")
        .firstName("Toni")
        .lastName("Tenantadmin")
        .email("toni@example.org")
        .build();
  }

  private static DpaSignatureDTO forwardedSignature(String forwardedByUserId) {
    return new DpaSignatureDTO()
        .tenantId(TENANT_ID)
        .status("SIGNED")
        .source("FORWARDED_EXTERNAL")
        .dpaVersion(DPA_VERSION)
        .signedAt("2026-08-14T09:15:00")
        .signerName("Erika Mustermann")
        .signerPosition("Geschäftsführerin")
        .forwardedByUserId(forwardedByUserId);
  }

  private void givenSignatures(DpaSignatureDTO... signatures) {
    when(signatureReadClient.readSignatures(TENANT_ID)).thenReturn(List.of(signatures));
  }

  @Test
  void onSignatureHint_sendsToTheForwardingAdminsAccountEmailAndLanguage() {
    // given a forward created by a logged-in admin
    givenSignatures(forwardedSignature("kc-admin-1"));
    when(adminRepository.findById("kc-admin-1")).thenReturn(Optional.of(forwardingAdmin()));
    when(identityLocaleLookup.findLocaleById("kc-admin-1")).thenReturn(Optional.of("en"));

    // when
    service.onSignatureHint(TENANT_ID);

    // then
    var subject = ArgumentCaptor.forClass(String.class);
    var body = ArgumentCaptor.forClass(String.class);
    verify(inviteMailDispatchService)
        .send(
            eq("toni@example.org"),
            subject.capture(),
            body.capture(),
            eq("https://admin.example.org/admin"),
            eq(TENANT_ID),
            eq("en"));
    // the account language wins
    assertTrue(subject.getValue().contains("Data processing agreement signed"));
    // tenant, version, timestamp and signer as recorded
    assertTrue(body.getValue().contains("Träger Nord e.V."));
    assertTrue(body.getValue().contains("2026-07-01 12:00"));
    assertTrue(body.getValue().contains("2026-08-14 09:15"));
    assertTrue(body.getValue().contains("Erika Mustermann"));
    assertTrue(body.getValue().contains("Geschäftsführerin"));
    // no raw sign token can leak into the mail — the signature carries none
    assertTrue(!body.getValue().contains("/dpa-sign/"));
  }

  @Test
  void onSignatureHint_fallsBackToTheOnboardingContact_When_theForwardHadNoAccount() {
    // given a pre-account wizard forward (no forwardedByUserId)
    givenSignatures(forwardedSignature(null));
    when(accountInviteRepository
            .findFirstByTenantIdAndTargetRoleAndDpaForwardedAtIsNotNullOrderByDpaForwardedAtDesc(
                TENANT_ID, AccountInviteTargetRole.TENANT_ADMIN))
        .thenReturn(
            Optional.of(
                AccountInvite.builder().recipientEmail("wizard.admin@example.org").build()));

    // when
    service.onSignatureHint(TENANT_ID);

    // then: the onboarding contact address, default language
    verify(inviteMailDispatchService)
        .send(eq("wizard.admin@example.org"), any(), any(), any(), eq(TENANT_ID), eq("de"));
    verify(adminRepository, never()).findById(anyString());
  }

  @Test
  void onSignatureHint_releasesTheClaim_When_renderingFailsBeforeDispatch() {
    // the claim is committed in its own transaction and its unique key is what makes the notice
    // exactly-once — a claim stranded by a PRE-dispatch failure would not just lose this attempt,
    // it would make every later hint lose the race and the notice would never be sent
    givenSignatures(forwardedSignature("kc-admin-1"));
    when(adminRepository.findById("kc-admin-1")).thenReturn(Optional.of(forwardingAdmin()));
    when(templateRepository.findByKindAndActiveTrueOrderByCreateDateDesc(
            InviteEmailTemplateKind.DPA_SIGNED_NOTICE))
        .thenThrow(new IllegalStateException("template store unavailable"));

    service.onSignatureHint(TENANT_ID);

    verify(noticeRepository).delete(any(DpaSignedNotice.class));
    verify(inviteMailDispatchService, never()).send(any(), any(), any(), any(), any(), any());
  }

  @Test
  void onSignatureHint_keepsSignaturesApart_When_theProviderOmitsTheDpaVersion() {
    // pre-dev's provider contract has no dpaVersion on the signature shape, so it is absent for
    // every signature today; keying on "unknown" would collapse them all onto one ledger row and
    // silently treat later signatures as duplicates
    var signature = forwardedSignature("kc-admin-1").dpaVersion(null);
    when(signatureReadClient.readSignatures(TENANT_ID)).thenReturn(List.of(signature));
    when(adminRepository.findById("kc-admin-1")).thenReturn(Optional.of(forwardingAdmin()));

    service.onSignatureHint(TENANT_ID);

    // one signature only proves the key is not the literal "unknown"; a fallback that derived a
    // single fixed key for EVERY signature would pass that. Two signatures with different
    // timestamps must claim two different ledger keys.
    var second = forwardedSignature("kc-admin-1").dpaVersion(null).signedAt("2026-09-02T11:00:00");
    when(signatureReadClient.readSignatures(TENANT_ID)).thenReturn(List.of(second));
    service.onSignatureHint(TENANT_ID);

    var captor = ArgumentCaptor.forClass(DpaSignedNotice.class);
    verify(noticeRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    var keys =
        captor.getAllValues().stream().map(DpaSignedNotice::getDpaVersion).distinct().toList();
    assertFalse(keys.contains("unknown"));
    assertEquals(2, keys.size(), "each signature must claim its own ledger key");
  }

  @Test
  void onSignatureHint_keepsTheClaim_When_theMailWasSentButSentAtCouldNotBeRecorded() {
    // the compensation window: releasing the claim AFTER a successful handoff would let a later
    // hint mail the same administrator a second notice about the same signature, and for a
    // message about a signed contract a duplicate is worse than a missing timestamp
    givenSignatures(forwardedSignature("kc-admin-1"));
    when(adminRepository.findById("kc-admin-1")).thenReturn(Optional.of(forwardingAdmin()));
    when(noticeRepository.save(any(DpaSignedNotice.class)))
        .thenAnswer(invocation -> invocation.getArgument(0)) // the claim
        .thenThrow(new IllegalStateException("connection lost")); // recording sent_at

    service.onSignatureHint(TENANT_ID);

    verify(inviteMailDispatchService).send(any(), any(), any(), any(), any(), any());
    verify(noticeRepository, never()).delete(any(DpaSignedNotice.class));
  }

  @Test
  void onSignatureHint_sendsNothing_When_theTenantSelfSigned() {
    // given only an in-app/onboarding signature — no forwarded row at all
    givenSignatures(new DpaSignatureDTO().tenantId(TENANT_ID).status("SIGNED").source("OWNER"));

    service.onSignatureHint(TENANT_ID);

    verify(inviteMailDispatchService, never()).send(any(), any(), any(), any(), any(), any());
    verify(noticeRepository, never()).save(any());
  }

  @Test
  void onSignatureHint_sendsNothing_When_theForwardedLinkIsStillPending() {
    givenSignatures(
        new DpaSignatureDTO().tenantId(TENANT_ID).status("PENDING").source("FORWARDED_EXTERNAL"));

    service.onSignatureHint(TENANT_ID);

    verify(inviteMailDispatchService, never()).send(any(), any(), any(), any(), any(), any());
  }

  @Test
  void onSignatureHint_sendsNothing_When_theUpstreamReadIsEmpty() {
    // a spoofed hint for a tenant with nothing signed must stay silent
    when(signatureReadClient.readSignatures(TENANT_ID)).thenReturn(List.of());

    service.onSignatureHint(TENANT_ID);

    verify(inviteMailDispatchService, never()).send(any(), any(), any(), any(), any(), any());
  }

  @Test
  void onSignatureHint_sendsOnlyOnce_When_theLedgerRowWasAlreadyClaimed() {
    // given a concurrent/duplicate hint losing the unique constraint
    givenSignatures(forwardedSignature("kc-admin-1"));
    when(adminRepository.findById("kc-admin-1")).thenReturn(Optional.of(forwardingAdmin()));
    when(noticeRepository.save(any(DpaSignedNotice.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate"));

    service.onSignatureHint(TENANT_ID);

    verify(inviteMailDispatchService, never()).send(any(), any(), any(), any(), any(), any());
  }

  @Test
  void onSignatureHint_releasesTheClaim_When_theMailCannotBeSent() {
    // given the SMTP handover fails after the claim was taken
    givenSignatures(forwardedSignature("kc-admin-1"));
    when(adminRepository.findById("kc-admin-1")).thenReturn(Optional.of(forwardingAdmin()));
    when(inviteMailDispatchService.send(any(), any(), any(), any(), any(), any()))
        .thenThrow(new SmtpSendException("smtp down"));

    service.onSignatureHint(TENANT_ID);

    // the claim is compensated so a later hint can retry the notice
    verify(noticeRepository).delete(any(DpaSignedNotice.class));
  }

  @Test
  void onSignatureHint_sendsNothing_When_noRecipientCanBeResolved() {
    givenSignatures(forwardedSignature(null));
    when(accountInviteRepository
            .findFirstByTenantIdAndTargetRoleAndDpaForwardedAtIsNotNullOrderByDpaForwardedAtDesc(
                TENANT_ID, AccountInviteTargetRole.TENANT_ADMIN))
        .thenReturn(Optional.empty());

    service.onSignatureHint(TENANT_ID);

    verify(inviteMailDispatchService, never()).send(any(), any(), any(), any(), any(), any());
    verify(noticeRepository, never()).save(any());
  }

  @Test
  void onSignatureHint_stillAnswers_When_theDispatchQueueIsFull() {
    // the 202 must hold by construction at the boundary: a saturated queue is shed, never raised
    service.useExecutor(
        task -> {
          throw new java.util.concurrent.RejectedExecutionException("queue full");
        });

    assertDoesNotThrow(() -> service.onSignatureHint(TENANT_ID));
    verify(signatureReadClient, never()).readSignatures(any());
  }

  @Test
  void onSignatureHint_stillAnswers_When_theExecutorIsShutDown() {
    // a shut-down executor throws IllegalStateException, NOT RejectedExecutionException — the
    // hole the same pattern had elsewhere in this codebase
    service.useExecutor(
        task -> {
          throw new IllegalStateException("executor shut down");
        });

    assertDoesNotThrow(() -> service.onSignatureHint(TENANT_ID));
  }

  @Test
  void onSignatureHint_stillAnswers_When_aCollaboratorThrows() {
    // e.g. NotAuthorizedException escaping the locale lookup: the boundary contains it, so the
    // endpoint keeps its documented 202 without every collaborator being individually defensive
    givenSignatures(forwardedSignature("kc-admin-1"));
    when(adminRepository.findById("kc-admin-1")).thenReturn(Optional.of(forwardingAdmin()));
    when(identityLocaleLookup.findLocaleById(anyString()))
        .thenThrow(new IllegalStateException("token expired"));

    assertDoesNotThrow(() -> service.onSignatureHint(TENANT_ID));
  }

  @Test
  void onSignatureHint_ignoresANullTenant() {
    service.onSignatureHint(null);

    verify(signatureReadClient, never()).readSignatures(any());
  }

  @Test
  void onSignatureHint_recordsTheClaimWithTenantVersionAndRecipient() {
    givenSignatures(forwardedSignature("kc-admin-1"));
    when(adminRepository.findById("kc-admin-1")).thenReturn(Optional.of(forwardingAdmin()));

    service.onSignatureHint(TENANT_ID);

    var captor = ArgumentCaptor.forClass(DpaSignedNotice.class);
    verify(noticeRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    var claim = captor.getAllValues().get(0);
    assertEquals(TENANT_ID, claim.getTenantId());
    assertEquals(DPA_VERSION, claim.getDpaVersion());
    assertEquals("toni@example.org", claim.getRecipientEmail());
  }
}
