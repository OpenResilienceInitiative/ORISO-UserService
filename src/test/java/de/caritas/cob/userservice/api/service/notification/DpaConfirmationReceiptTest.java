package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.observability.DpaSignedNoticeMetrics;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.exception.SmtpSendException.Category;
import de.caritas.cob.userservice.api.exception.SmtpSendException.DeliveryDisposition;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.DpaSignedNotice;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.DpaSignedNoticeRepository;
import de.caritas.cob.userservice.api.port.out.IdentityLocaleLookup;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteFrameMailRendererFixture;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailTransport;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationFixture;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationResolver;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.DpaSignatureDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Confirmation receipts observed at the SMTP boundary: notification dispatch, frame rendering and
 * branding are production objects; persistence, provider reads and SMTP are external test seams.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DpaConfirmationReceiptTest {

  private static final Long TENANT_ID = 42L;
  private static final String APP_ORIGIN = "https://app.example.org";

  @Mock private TenantDpaSignatureReadClient signatureReadClient;
  @Mock private DpaSignedNoticeRepository noticeRepository;
  @Mock private AdminRepository adminRepository;
  @Mock private AccountInviteRepository accountInviteRepository;
  @Mock private IdentityLocaleLookup identityLocaleLookup;
  @Mock private InviteEmailTemplateRepository templateRepository;
  @Mock private TenantService tenantService;
  @Mock private PlatformTransactionManager transactionManager;
  @Mock private DpaSignedNoticeMetrics metrics;
  @Mock private TenantTemplateSupplier tenantTemplateSupplier;
  @Mock private InviteMailTransport inviteMailTransport;

  @BeforeEach
  void setUp() {
    when(tenantService.getPlatformTenantDataFresh())
        .thenReturn(
            new RestrictedTenantDTO()
                .id(0L)
                .theming(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Theming()
                        .primaryColor("#1c4f8f")));
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(noticeRepository.save(any(DpaSignedNotice.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(templateRepository.findByKindAndActiveTrueAndTenantIdIsNullOrderByCreateDateDesc(
            InviteEmailTemplateKind.DPA_SIGNED_NOTICE))
        .thenReturn(List.of());
    when(identityLocaleLookup.findLocaleById(anyString())).thenReturn(Optional.empty());
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().id(TENANT_ID).name("Träger Nord e.V."));
    when(tenantTemplateSupplier.getTenantBaseUrl(any(RestrictedTenantDTO.class))).thenReturn("");
    when(inviteMailTransport.send(any(), any(), any(), any(), any()))
        .thenReturn(new InviteMailSendReceipt("toni@example.org", Instant.now()));
    when(signatureReadClient.readSignatures(TENANT_ID))
        .thenReturn(
            List.of(
                new DpaSignatureDTO()
                    .tenantId(TENANT_ID)
                    .status("SIGNED")
                    .source("FORWARDED_EXTERNAL")
                    .dpaVersion("2026-07-01T12:00:00")
                    .signedAt("2026-08-14T09:15:00")
                    .signerName("Erika Mustermann")
                    .forwardedByUserId("kc-admin-1")));
    when(adminRepository.findById("kc-admin-1"))
        .thenReturn(
            Optional.of(
                Admin.builder()
                    .id("kc-admin-1")
                    .username("toni")
                    .firstName("Toni")
                    .lastName("Tenantadmin")
                    .email("toni@example.org")
                    .build()));
  }

  @Test
  void forwardedConfirmation_receiptReachesSignerInStandardFrameWithoutAdminAction() {
    signature().signerEmail("erika@example.org");

    SentMail mail = sendNoticeTo("erika@example.org");

    assertThat(mail.text())
        .contains("Ihre Bestätigung der Vertragsunterlagen wurde registriert.")
        .contains("Der Trägeradmin wird darüber benachrichtigt")
        .contains("Träger Nord e.V.")
        .contains("Erika Mustermann")
        .contains("14.08.2026 11:15 Uhr")
        .contains("Diese E-Mail wurde automatisch versendet.")
        .doesNotContain("https://admin.example.org")
        .doesNotContain("Einladung annehmen")
        .doesNotContain("{{");
    assertThat(mail.html()).contains("<!DOCTYPE html>").doesNotContain("Einladung annehmen");
  }

  @Test
  void englishConfirmation_receivesEnglishReceiptEvenWhenAdminUsesGerman() {
    signature().signerEmail("erika@example.org").language("en");

    SentMail mail = sendNoticeTo("erika@example.org");

    assertThat(mail.text())
        .contains("Your confirmation of the contract documents has been recorded.")
        .contains("The organisation administrator will be notified")
        .contains("This email was sent automatically.")
        .doesNotContain("Ihre Bestätigung")
        .doesNotContain("https://admin.example.org");
  }

  @ParameterizedTest
  @ValueSource(strings = {"en-GB", "EN_us", " en "})
  void regionalEnglishConfirmation_receivesEnglishReceipt(String language) {
    signature().signerEmail("erika@example.org").language(language);
    assertThat(sendNoticeTo("erika@example.org").text())
        .contains("Your confirmation of the contract documents has been recorded.");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  ", "fr", "de-DE"})
  void absentOrUnsupportedLanguage_receivesGermanReceipt(String language) {
    signature().signerEmail("erika@example.org").language(language);
    assertThat(sendNoticeTo("erika@example.org").text())
        .contains("Ihre Bestätigung der Vertragsunterlagen wurde registriert.");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  void missingSignerAddress_keepsExistingAdminNotice(String email) {
    signature().signerEmail(email);
    var mail = sendNoticeTo("toni@example.org");
    assertThat(mail.text()).contains("https://admin.example.org");
    verify(inviteMailTransport, times(1)).send(any(), any(), any(), any(), any());
  }

  @Test
  void missingAdmin_doesNotSuppressSignerReceipt() {
    signature().signerEmail("erika@example.org");
    when(adminRepository.findById("kc-admin-1")).thenReturn(Optional.empty());
    assertThat(sendNoticeTo("erika@example.org").text())
        .contains("Ihre Bestätigung der Vertragsunterlagen wurde registriert.");
    verify(inviteMailTransport, times(1)).send(any(), any(), any(), any(), any());
  }

  @Test
  void selfConfirmation_doesNotSendEitherForwardedNotice() {
    signature().signerEmail("erika@example.org").source("OWNER");
    service(SenderOrganisationFixture.nobody()).onSignatureHint(TENANT_ID);
    verify(inviteMailTransport, never()).send(any(), any(), any(), any(), any());
  }

  @Test
  void sameAddress_receivesAdminNoticeAndDistinctSignerReceiptOnlyOnce() {
    signature().signerEmail("toni@example.org");
    usePersistentLedger();
    var service = service(SenderOrganisationFixture.nobody());
    service.onSignatureHint(TENANT_ID);
    service.onSignatureHint(TENANT_ID);

    var text = ArgumentCaptor.forClass(String.class);
    verify(inviteMailTransport, times(2))
        .send(any(), eq("toni@example.org"), any(), any(), text.capture());
    assertThat(text.getAllValues().get(0)).contains("https://admin.example.org");
    assertThat(text.getAllValues().get(1))
        .contains("Ihre Bestätigung der Vertragsunterlagen wurde registriert.")
        .doesNotContain("https://admin.example.org");
  }

  @ParameterizedTest
  @ValueSource(strings = {"toni@example.org", "erika@example.org"})
  void failedRoleCanRetryWithoutDuplicatingSuccessfulRole(String failedRecipient) {
    signature().signerEmail("erika@example.org");
    usePersistentLedger();
    when(inviteMailTransport.send(any(), eq(failedRecipient), any(), any(), any()))
        .thenThrow(
            new SmtpSendException(
                Category.SMTP_TRANSPORT_FAILED,
                DeliveryDisposition.CONFIRMED_NOT_SENT,
                "Rejected in receipt regression"))
        .thenReturn(new InviteMailSendReceipt(failedRecipient, Instant.now()));
    var service = service(SenderOrganisationFixture.nobody());
    service.onSignatureHint(TENANT_ID);
    service.onSignatureHint(TENANT_ID);
    service.onSignatureHint(TENANT_ID);

    String successfulRecipient =
        failedRecipient.equals("toni@example.org") ? "erika@example.org" : "toni@example.org";
    verify(inviteMailTransport, times(2)).send(any(), eq(failedRecipient), any(), any(), any());
    verify(inviteMailTransport, times(1)).send(any(), eq(successfulRecipient), any(), any(), any());
  }

  /** Database seam models persisted role claims and the unique constraint from the migration. */
  private void usePersistentLedger() {
    Map<DpaSignedNotice.NoticeRole, DpaSignedNotice> ledger = new HashMap<>();
    when(noticeRepository.save(any(DpaSignedNotice.class)))
        .thenAnswer(
            invocation -> {
              DpaSignedNotice notice = invocation.getArgument(0);
              DpaSignedNotice existing = ledger.putIfAbsent(notice.getNoticeRole(), notice);
              if (existing != null && existing != notice) {
                throw new DataIntegrityViolationException("duplicate recipient role");
              }
              return notice;
            });
    doAnswer(
            invocation -> {
              DpaSignedNotice notice = invocation.getArgument(0);
              ledger.remove(notice.getNoticeRole(), notice);
              return null;
            })
        .when(noticeRepository)
        .delete(any(DpaSignedNotice.class));
  }

  private DpaSignatureDTO signature() {
    return signatureReadClient.readSignatures(TENANT_ID).get(0);
  }

  private SentMail sendNoticeTo(String recipient) {
    return sendNotice(SenderOrganisationFixture.nobody(), recipient);
  }

  private SentMail sendNotice(SenderOrganisationResolver senderOrganisations, String recipient) {
    var service = service(senderOrganisations);
    service.onSignatureHint(TENANT_ID);
    ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
    verify(inviteMailTransport).send(any(), eq(recipient), any(), html.capture(), text.capture());
    return new SentMail(html.getValue(), text.getValue());
  }

  private DpaSignedNoticeService service(SenderOrganisationResolver senderOrganisations) {
    EmailBrandingResolver brandingResolver =
        new EmailBrandingResolver(
            tenantService, tenantTemplateSupplier, "Online-Beratung", "", APP_ORIGIN);
    InviteMailDispatchService mailDispatch =
        new InviteMailDispatchService(
            de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsFixture.configured(
                "smtp-user", "smtp-pass"),
            inviteMailTransport,
            InviteFrameMailRendererFixture.inviteFrameMailRenderer(
                brandingResolver, senderOrganisations),
            de.caritas.cob.userservice.api.service.accountinvite.mail.TenantMailRoutingFixture
                .platformRoutes(),
            de.caritas.cob.userservice.api.service.accountinvite.mail.TenantMailRoutingFixture
                .unusedRelay());
    DpaSignedNoticeService service =
        new DpaSignedNoticeService(
            signatureReadClient,
            noticeRepository,
            adminRepository,
            accountInviteRepository,
            identityLocaleLookup,
            templateRepository,
            mailDispatch,
            tenantService,
            transactionManager,
            metrics,
            new AdminPanelUrl("https://admin.example.org"));
    service.useExecutor(Runnable::run);

    return service;
  }

  private record SentMail(String html, String text) {}
}
