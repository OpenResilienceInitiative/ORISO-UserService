package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
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
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisation;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationFixture;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationResolver;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.DpaSignatureDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The footer of the "Vertragsunterlagen bestätigt" notice, observed on the wire: the notice
 * service, the invite frame, the brand values and the dispatcher are the production objects; only
 * the lookups, the SMTP settings read and the transport are stubbed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DpaSignedNoticeFooterTest {

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
  @Mock private TenantTemplateSupplier tenantTemplateSupplier;
  @Mock private InviteMailTransport inviteMailTransport;

  @BeforeEach
  void setUp() {
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(noticeRepository.save(any(DpaSignedNotice.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(templateRepository.findByKindAndActiveTrueOrderByCreateDateDesc(
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
  void footer_namesTheTraeger_withThePlatformOwnersAddress_When_theTraegerHasNone() {
    String text =
        sendNotice(
                SenderOrganisationFixture.resolving(
                    SenderOrganisationFixture.PLATFORM_OWNER,
                    Map.of(TENANT_ID, new SenderOrganisation("Träger Nord e.V.", null, null))))
            .text();

    assertThat(text)
        .contains("\nTräger Nord e.V.\nBetreiberweg 1, 10115 Berlin\ninfo@betreiber.example\n");
  }

  @Test
  void footer_usesTheTraegersOwnAddress_When_itHasOne() {
    SentMail mail =
        sendNotice(
            SenderOrganisationFixture.resolving(
                SenderOrganisationFixture.PLATFORM_OWNER,
                Map.of(
                    TENANT_ID,
                    new SenderOrganisation("Träger Nord e.V.", "Nordstraße 5, 24103 Kiel", null))));

    assertThat(mail.html())
        .contains(">Nordstraße 5, 24103 Kiel</div>")
        .doesNotContain("Betreiberweg");
    assertThat(mail.text()).contains("\nTräger Nord e.V.\nNordstraße 5, 24103 Kiel\n");
    // The offered-by line names the platform operator, never the Träger (Frank, 2026-09-23).
    for (String part : List.of(mail.html(), mail.text())) {
      assertThat(part)
          .contains("Online-Beratung ist ein Angebot von ORISO.")
          .doesNotContain("ist ein Angebot von Träger Nord");
    }
  }

  @Test
  void footer_hasNoSenderLines_When_nobodyEnteredAny() {
    SentMail mail = sendNotice(SenderOrganisationFixture.nobody());

    for (String part : List.of(mail.html(), mail.text())) {
      assertThat(part)
          .contains("Vertragsunterlagen")
          .doesNotContain("Musterstraße")
          .doesNotContain("ist ein Angebot von")
          .doesNotContain("{{");
    }
  }

  private SentMail sendNotice(SenderOrganisationResolver senderOrganisations) {
    EmailBrandingResolver brandingResolver =
        new EmailBrandingResolver(
            tenantService, tenantTemplateSupplier, "Online-Beratung", "", APP_ORIGIN);
    InviteMailDispatchService mailDispatch =
        new InviteMailDispatchService(
            new PlatformSmtpSettingsProvider(
                "smtp.example.org",
                "587",
                "false",
                "smtp-user",
                "smtp-pass",
                "noreply@example.org",
                false),
            inviteMailTransport,
            InviteFrameMailRendererFixture.inviteFrameMailRenderer(
                brandingResolver, senderOrganisations));
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
            new AdminPanelUrl("https://admin.example.org"));
    service.useExecutor(Runnable::run);

    service.onSignatureHint(TENANT_ID);

    ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
    verify(inviteMailTransport)
        .send(any(), eq("toni@example.org"), any(), html.capture(), text.capture());
    return new SentMail(html.getValue(), text.getValue());
  }

  private record SentMail(String html, String text) {}
}
