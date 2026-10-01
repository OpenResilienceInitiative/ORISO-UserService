package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.RecipientKind;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.Status;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.donotdisturb.DoNotDisturbService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailDispatcher;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.OrisoSmtpTransport;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdviceSeekerReplyEmailServiceTest {
  @Mock private SessionRepository sessions;
  @Mock private TenantService tenants;
  @Mock private TenantTemplateSupplier tenantTemplates;
  @Mock private EmailBrandingResolver branding;
  @Mock private OrisoEmailBrand emailBrand;
  @Mock private TenantSystemEmailRouteService routes;
  @Mock private TenantSystemEmailDelivery delivery;
  @Mock private ReplyEmailDeliveryWriter writer;
  @Mock private DoNotDisturbService doNotDisturb;
  @Mock private MatrixCaseReplyActorAuthorizer replyActors;

  private AdviceSeekerReplyEmailService service;

  @BeforeEach
  void setUp() {
    service =
        new AdviceSeekerReplyEmailService(
            sessions,
            tenants,
            tenantTemplates,
            branding,
            emailBrand,
            new OrisoEmailRenderer(),
            routes,
            delivery,
            writer,
            doNotDisturb,
            replyActors);
    ReflectionTestUtils.setField(service, "multitenancyEnabled", true);
    org.mockito.Mockito.lenient()
        .when(replyActors.isCurrentWriter(any(), anyString(), anyBoolean()))
        .thenReturn(true);
    org.mockito.Mockito.lenient()
        .when(replyActors.hasCurrentRoomMembers(any(), anyString(), anyString()))
        .thenReturn(true);
    org.mockito.Mockito.lenient()
        .when(branding.resolveNotification(anyLong(), anyString()))
        .thenReturn(new EmailBranding("Tenant", null, "#a5000a", null, null));
  }

  @Test
  void twoRepliesSendTwoNeutralMailsButAReplayedEventDoesNotSendAgain() {
    User asker = asker(true, "asker@example.net");
    Session session = session(asker);
    when(sessions.findByMatrixRoomId("!room")).thenReturn(Optional.of(session));
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    var tenant = new RestrictedTenantDTO().id(7L).subdomain("tenant");
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenantTemplates.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.net");
    when(routes.resolve(7L))
        .thenReturn(
            Optional.of(
                new TenantSystemEmailRouteService.Route(
                    TenantSystemEmailRouteService.Mode.PLATFORM, null)));
    when(emailBrand.valuesForResolvedBrand(
            eq("https://tenant.example.net"), any(EmailBranding.class)))
        .thenReturn(neutralBrand());
    when(writer.reserve(
            eq(RecipientKind.ASKER),
            eq("asker"),
            eq("@consultant:matrix.example"),
            eq("!room"),
            anyString(),
            eq(7L),
            eq(42L)))
        .thenReturn(1L, 2L)
        .thenThrow(new DataIntegrityViolationException("duplicate"));
    when(writer.claim(1L)).thenReturn(Optional.of(claim(1L)));
    when(writer.claim(2L)).thenReturn(Optional.of(claim(2L)));

    service.onConsultantReply("!room", "$first", "@consultant:matrix.example");
    service.onConsultantReply("!room", "$second", "@consultant:matrix.example");
    service.onConsultantReply("!room", "$first", "@consultant:matrix.example");
    service.deliverPending(1L);
    service.deliverPending(2L);

    var rendered = ArgumentCaptor.forClass(OrisoEmailRenderer.RenderedEmail.class);
    verify(delivery, org.mockito.Mockito.times(2))
        .sendReply(eq(7L), any(), eq("asker@example.net"), rendered.capture(), any());
    assertThat(rendered.getAllValues()).hasSize(2);
    for (var mail : rendered.getAllValues()) {
      assertThat(mail.subject()).isEqualTo("Sie haben eine neue Nachricht");
      assertThat(mail.html()).contains("https://tenant.example.net/sessions/user/view/session/42");
      assertThat(mail.html()).contains("mail=neue-nachricht");
      assertThat(mail.html()).doesNotContain("Counselling Centre", "Counsellor Real Name", "{{");
      assertThat(mail.text()).doesNotContain("Counselling Centre", "Counsellor Real Name", "{{");
    }
    verify(writer).finish(1L, Status.SENT);
    verify(writer).finish(2L, Status.SENT);
  }

  @Test
  void everyAskerLocaleKeepsPrivateCaseFactsOutOfTheActualMultipartMail() throws Exception {
    var asker = asker(true, "asker@example.net");
    asker.setUsername("PRIVATE_SEEKER_MARKER");
    var session = session(asker);
    session.setPostcode("PRIVATE_CASE_MARKER");
    session.getConsultant().setFirstName("PRIVATE_COUNSELLOR_MARKER");
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(writer.claim(1L)).thenReturn(Optional.of(claim(1L)));
    var tenant = new RestrictedTenantDTO().id(7L).subdomain("tenant");
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenantTemplates.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.net");
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(routes.resolve(7L)).thenReturn(Optional.of(route));
    when(branding.resolveNotification(7L, "https://tenant.example.net"))
        .thenReturn(
            new EmailBranding(
                "PRIVATE_CENTRE_MARKER",
                "https://tenant.example.net/tenant-logo.png",
                "#112233",
                null,
                null));
    when(emailBrand.readablePrimary("#112233")).thenReturn("#112233");
    when(emailBrand.valuesForResolvedBrand(
            eq("https://tenant.example.net"), any(EmailBranding.class)))
        .thenAnswer(
            ignored -> {
              var values = neutralBrand();
              values.put("platformName", "Community Hub");
              values.put("offeringName", "Community Hub");
              return values;
            });
    var tested =
        new AdviceSeekerReplyEmailService(
            sessions,
            tenants,
            tenantTemplates,
            branding,
            emailBrand,
            new OrisoEmailRenderer(true),
            routes,
            delivery,
            writer,
            doNotDisturb,
            replyActors);
    ReflectionTestUtils.setField(tested, "multitenancyEnabled", true);
    var smtp =
        new PlatformSmtpSettingsProvider.Settings(
            "smtp.example.net", 587, false, "account", "secret", "sender@example.net", "#112233");

    for (var tone : OrisoEmailRenderer.Tone.values()) {
      asker.setLanguageCode(
          LanguageCode.valueOf(
              tone == OrisoEmailRenderer.Tone.DE_INFORMAL
                      || tone == OrisoEmailRenderer.Tone.DE_FORMAL
                  ? "de"
                  : tone.name().toLowerCase()));
      asker.setLanguageFormal(tone != OrisoEmailRenderer.Tone.DE_INFORMAL);
      tested.deliverPending(1L);
    }

    var rendered = ArgumentCaptor.forClass(OrisoEmailRenderer.RenderedEmail.class);
    verify(delivery, Mockito.times(7))
        .sendReply(eq(7L), eq(route), eq("asker@example.net"), rendered.capture(), any());
    try (MockedStatic<OrisoSmtpTransport> transport =
        Mockito.mockStatic(OrisoSmtpTransport.class, Mockito.CALLS_REAL_METHODS)) {
      transport.when(() -> OrisoSmtpTransport.send(any(Message.class))).thenAnswer(call -> null);
      for (var mail : rendered.getAllValues()) {
        new OrisoEmailDispatcher().sendOrThrow(smtp, "asker@example.net", mail, UUID.randomUUID());
      }
      var messages = ArgumentCaptor.forClass(Message.class);
      transport.verify(() -> OrisoSmtpTransport.send(messages.capture()), Mockito.times(7));
      for (int index = 0; index < messages.getAllValues().size(); index++) {
        var sent = messages.getAllValues().get(index);
        var tone = OrisoEmailRenderer.Tone.values()[index];
        var mime = (MimeMessage) sent;
        var parts = (MimeMultipart) mime.getContent();
        assertThat(parts.getCount()).isEqualTo(2);
        String plain = parts.getBodyPart(0).getContent().toString();
        String html = parts.getBodyPart(1).getContent().toString();
        String all = mime.getSubject() + plain + html;
        assertThat(mime.getSubject())
            .isEqualTo(new OrisoEmailRenderer(true).subjectOf("neue-nachricht", tone));
        assertThat(html).contains(new OrisoEmailRenderer(true).preheaderOf("neue-nachricht", tone));
        assertThat(all).contains("Community Hub");
        assertThat(all).contains("https://tenant.example.net/tenant-logo.png");
        assertThat(all).contains("#112233");
        assertThat(all).contains("https://tenant.example.net/sessions/user/view/session/42");
        assertThat(all)
            .doesNotContain(
                "PRIVATE_SEEKER_MARKER",
                "PRIVATE_COUNSELLOR_MARKER",
                "PRIVATE_CASE_MARKER",
                "PRIVATE_CENTRE_MARKER",
                "{{");
        assertThat(mime.getSubject()).doesNotContain("PRIVATE_");
      }
    }
  }

  @Test
  void seekerEventClaimsPrimaryConsultantOnceAndRejectsAReplay() {
    var session = consultation();
    when(sessions.findByMatrixRoomId("!room:matrix.example")).thenReturn(Optional.of(session));
    when(writer.reserve(
            eq(RecipientKind.CONSULTANT),
            eq("consultant"),
            eq("@asker:matrix.example"),
            eq("!room:matrix.example"),
            anyString(),
            eq(7L),
            eq(42L)))
        .thenReturn(1L)
        .thenThrow(new DataIntegrityViolationException("duplicate event"));

    service.onAdviceSeekerMessage("!room:matrix.example", "$first", "@asker:matrix.example");
    service.onAdviceSeekerMessage("!room:matrix.example", "$first", "@asker:matrix.example");

    verify(writer, org.mockito.Mockito.times(2))
        .reserve(
            eq(RecipientKind.CONSULTANT),
            eq("consultant"),
            eq("@asker:matrix.example"),
            eq("!room:matrix.example"),
            anyString(),
            eq(7L),
            eq(42L));
    verifyNoInteractions(delivery);
  }

  @Test
  void liveChatSeekerMessageUsesTheExistingPrivateRoomEligibility() {
    var session = consultation();
    session.setConversationType(ConversationType.LIVE_CHAT);
    when(sessions.findByMatrixRoomId("!room:matrix.example")).thenReturn(Optional.of(session));

    service.onAdviceSeekerMessage(
        "!room:matrix.example", "$live-chat-message", "@asker:matrix.example");

    verify(writer)
        .reserve(
            eq(RecipientKind.CONSULTANT),
            eq("consultant"),
            eq("@asker:matrix.example"),
            eq("!room:matrix.example"),
            anyString(),
            eq(7L),
            eq(42L));
  }

  @Test
  void otherActorAndSelfHelpOrDisabledRecipientNeverClaimConsultantMail() {
    var session = consultation();
    when(sessions.findByMatrixRoomId("!room:matrix.example")).thenReturn(Optional.of(session));
    service.onAdviceSeekerMessage("!room:matrix.example", "$event", "@other:matrix.example");
    session.setConversationType(ConversationType.SELF_HELP);
    service.onAdviceSeekerMessage("!room:matrix.example", "$event", "@asker:matrix.example");
    session.setConversationType(ConversationType.AGENCY_COUNSELLING);
    session.getConsultant().setNotifyNewChatMessageFromAdviceSeeker(false);
    service.onAdviceSeekerMessage("!room:matrix.example", "$event", "@asker:matrix.example");
    session.getConsultant().setNotifyNewChatMessageFromAdviceSeeker(true);
    session
        .getConsultant()
        .setNotificationsSettings("{\"newChatMessageNotificationEnabled\":false}");
    service.onAdviceSeekerMessage("!room:matrix.example", "$event", "@asker:matrix.example");

    verifyNoInteractions(writer, delivery);
  }

  @Test
  void doNotDisturbSuppressesConsultantMailAtClaimTime() {
    var session = consultation();
    when(sessions.findByMatrixRoomId("!room:matrix.example")).thenReturn(Optional.of(session));
    when(doNotDisturb.isInDoNotDisturb("consultant")).thenReturn(true);

    service.onAdviceSeekerMessage("!room:matrix.example", "$event", "@asker:matrix.example");

    verifyNoInteractions(writer, delivery);
  }

  @Test
  void changedPrimaryConsultantRejectsQueuedMailBeforeSmtp() {
    var session = consultation();
    session.getConsultant().setId("replacement");
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(writer.claim(1L)).thenReturn(Optional.of(consultantClaim()));

    service.deliverPending(1L);

    verify(writer).finish(1L, Status.REJECTED);
    verifyNoInteractions(delivery);
  }

  @Test
  void reassignedConsultantClaimRejectsBeforeUnavailableMatrixMembershipLookup() {
    var session = consultation();
    session.getConsultant().setId("replacement");
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(writer.claim(1L)).thenReturn(Optional.of(consultantClaim()));

    service.deliverPending(1L);

    verify(writer).finish(1L, Status.REJECTED);
    verify(replyActors, never()).hasCurrentRoomMembers(any(), anyString(), anyString());
    verifyNoInteractions(delivery);
  }

  @Test
  void unsupportedRecipientLanguageDefersClaimBeforeSmtp() {
    var session = session(asker(true, "asker@example.net"));
    session.getUser().setLanguageCode(LanguageCode.es);
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(writer.claim(1L)).thenReturn(Optional.of(claim(1L)));

    service.deliverPending(1L);

    verify(writer).retryLater(1L);
    verify(writer, never()).finish(1L, Status.UNCERTAIN);
    verifyNoInteractions(delivery);
  }

  @Test
  void removedSeekerOrConsultantRoomMemberRejectsQueuedConsultantMail() {
    var session = consultation();
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(writer.claim(1L)).thenReturn(Optional.of(consultantClaim()));
    when(replyActors.hasCurrentRoomMembers(
            session, "@asker:matrix.example", "@consultant:matrix.example"))
        .thenReturn(false);

    service.deliverPending(1L);

    verify(writer).finish(1L, Status.REJECTED);
    verifyNoInteractions(delivery);
  }

  @Test
  void changedPrimaryRoomRejectsBothKindsOfQueuedMail() {
    var session = consultation();
    session.setMatrixRoomId("!replacement:matrix.example");
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(writer.claim(1L)).thenReturn(Optional.of(consultantClaim()));
    var askerClaim = claim(2L);
    askerClaim.setSourceRoomId("!room:matrix.example");
    when(writer.claim(2L)).thenReturn(Optional.of(askerClaim));

    service.deliverPending(1L);
    service.deliverPending(2L);

    verify(writer).finish(1L, Status.REJECTED);
    verify(writer).finish(2L, Status.REJECTED);
    verifyNoInteractions(delivery);
  }

  @Test
  void revokedReplyActorRejectsQueuedAskerMailBeforeSmtp() {
    var session = session(asker(true, "asker@example.net"));
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(writer.claim(1L)).thenReturn(Optional.of(claim(1L)));
    when(replyActors.isCurrentWriter(session, "@consultant:matrix.example", true))
        .thenReturn(false);

    service.deliverPending(1L);

    verify(writer).finish(1L, Status.REJECTED);
    verifyNoInteractions(delivery);
  }

  @Test
  void unavailableRoomMembershipRetriesWithoutContactingSmtp() {
    var session = prepareReadyClaim();
    when(replyActors.isCurrentWriter(session, "@consultant:matrix.example", true))
        .thenThrow(new IllegalStateException("Matrix room membership is unavailable"))
        .thenReturn(true);

    service.deliverPending(1L);
    org.mockito.Mockito.verify(delivery, never())
        .sendReply(anyLong(), any(), anyString(), any(), any());
    service.deliverPending(1L);

    verify(writer).retryLater(1L);
    verify(delivery).sendReply(eq(7L), any(), eq("asker@example.net"), any(), any());
    verify(writer).finish(1L, Status.SENT);
  }

  @Test
  void oldClaimWithoutTrustedSenderCannotGuessCurrentConsultant() {
    var session = session(asker(true, "asker@example.net"));
    var oldClaim = claim(1L);
    oldClaim.setSourceMatrixUserId(null);
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(writer.claim(1L)).thenReturn(Optional.of(oldClaim));

    service.deliverPending(1L);

    verify(writer).finish(1L, Status.REJECTED);
    verifyNoInteractions(delivery, replyActors);
  }

  @Test
  void wrongTenantOrRoomNeverReservesConsultantMail() {
    var session = consultation();
    when(sessions.findByMatrixRoomId("!room:matrix.example")).thenReturn(Optional.of(session));
    session.getConsultant().setTenantId(8L);
    service.onAdviceSeekerMessage("!room:matrix.example", "$event", "@asker:matrix.example");
    session.getConsultant().setTenantId(7L);
    session.setMatrixRoomId("!protected:matrix.example");
    service.onAdviceSeekerMessage("!room:matrix.example", "$event", "@asker:matrix.example");

    verifyNoInteractions(writer, delivery);
  }

  @Test
  void consultantMailUsesItsOwnTemplateAndCurrentProtectedRoomUrl() {
    var session = consultation();
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(writer.claim(1L)).thenReturn(Optional.of(consultantClaim()));
    var tenant = new RestrictedTenantDTO().id(7L).subdomain("tenant");
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenantTemplates.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.net");
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.OWN, null);
    when(routes.resolve(7L)).thenReturn(Optional.of(route));
    when(emailBrand.valuesForResolvedBrand(
            eq("https://tenant.example.net"), any(EmailBranding.class)))
        .thenReturn(neutralBrand());
    when(branding.resolveNotification(7L, "https://tenant.example.net"))
        .thenReturn(
            new EmailBranding(
                "Private Counselling Centre",
                "https://tenant.example.net/logo.png",
                "#112233",
                null,
                null));
    when(emailBrand.readablePrimary("#112233")).thenReturn("#112233");
    var renderer = org.mockito.Mockito.mock(OrisoEmailRenderer.class);
    var rendered = new OrisoEmailRenderer.RenderedEmail("New message", "<p>Body</p>", "Body");
    var tested =
        new AdviceSeekerReplyEmailService(
            sessions,
            tenants,
            tenantTemplates,
            branding,
            emailBrand,
            renderer,
            routes,
            delivery,
            writer,
            doNotDisturb,
            replyActors);
    ReflectionTestUtils.setField(tested, "multitenancyEnabled", true);
    when(renderer.render(
            eq("neue-nachricht-beratung"), eq(OrisoEmailRenderer.Tone.DE_FORMAL), any()))
        .thenReturn(rendered);

    tested.deliverPending(1L);

    @SuppressWarnings("unchecked")
    var values = ArgumentCaptor.forClass(Map.class);
    verify(renderer)
        .render(
            eq("neue-nachricht-beratung"), eq(OrisoEmailRenderer.Tone.DE_FORMAL), values.capture());
    assertThat(values.getValue().get("messageUrl"))
        .isEqualTo(
            "https://tenant.example.net/sessions/consultant/sessionView/%21room%3Amatrix.example/42");
    assertThat(values.getValue().get("platformName")).isEqualTo("ORISO");
    assertThat(values.getValue().get("logoUrl")).isEqualTo("https://tenant.example.net/logo.png");
    assertThat(values.getValue().get("primaryColor")).isEqualTo("#112233");
    verify(delivery)
        .sendReply(eq(7L), eq(route), eq("consultant@example.net"), eq(rendered), any());
    verify(writer).finish(1L, Status.SENT);
  }

  @Test
  void disabledEmailChoiceDoesNotReserveOrSend() {
    var session = session(asker(false, "asker@example.net"));
    when(sessions.findByMatrixRoomId("!room")).thenReturn(Optional.of(session));

    service.onConsultantReply("!room", "$first", "@consultant:matrix.example");

    verifyNoInteractions(writer, delivery);
  }

  @Test
  void unauthorizedConsultantSenderNeverCreatesAnAskerClaim() {
    var session = session(asker(true, "asker@example.net"));
    when(sessions.findByMatrixRoomId("!room")).thenReturn(Optional.of(session));
    when(replyActors.isCurrentWriter(session, "@outsider:matrix.example", false)).thenReturn(false);

    service.onConsultantReply("!room", "$event", "@outsider:matrix.example");

    verifyNoInteractions(writer, delivery);
  }

  @Test
  void absentAddressDoesNotReserveOrSend() {
    var session = session(asker(true, ""));
    when(sessions.findByMatrixRoomId("!room")).thenReturn(Optional.of(session));

    service.onConsultantReply("!room", "$first", "@consultant:matrix.example");

    verifyNoInteractions(writer, delivery);
  }

  @Test
  void pendingDeletionNeitherReservesNorDeliversReplyMail() {
    var asker = asker(true, "asker@example.net");
    asker.setDeleteDate(java.time.LocalDateTime.now());
    var session = session(asker);
    when(sessions.findByMatrixRoomId("!room")).thenReturn(Optional.of(session));
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(writer.claim(1L)).thenReturn(Optional.of(claim(1L)));

    service.onConsultantReply("!room", "$first", "@consultant:matrix.example");
    service.deliverPending(1L);

    verify(writer, never())
        .reserve(any(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong());
    verify(writer).finish(1L, Status.REJECTED);
    verifyNoInteractions(delivery);
  }

  @Test
  void eventWithoutIdNeverClaimsAnUnrepeatableDelivery() {
    service.onConsultantReply("!room", null, "@consultant:matrix.example");
    verifyNoInteractions(sessions, writer, delivery);
  }

  @Test
  void invalidTenantUrlDefersTheQueuedEmailWithoutSMTPFallback() {
    var session = session(asker(true, "asker@example.net"));
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(writer.claim(1L)).thenReturn(Optional.of(claim(1L)));
    var tenant = new RestrictedTenantDTO().id(7L).subdomain("tenant");
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenantTemplates.getTenantBaseUrl(tenant)).thenReturn("not-a-public-url");
    when(routes.resolve(7L))
        .thenReturn(
            Optional.of(
                new TenantSystemEmailRouteService.Route(
                    TenantSystemEmailRouteService.Mode.PLATFORM, null)));

    service.deliverPending(1L);

    verify(writer).retryLater(1L);
    verify(delivery, never()).sendReply(anyLong(), any(), anyString(), any(), any());
  }

  @Test
  void uncertainSmtpResultStopsAutomaticReplay() {
    prepareReadyClaim();
    org.mockito.Mockito.doThrow(new IllegalStateException("SMTP acknowledgement lost"))
        .when(delivery)
        .sendReply(anyLong(), any(), anyString(), any(), any());

    assertThatThrownBy(() -> service.deliverPending(1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SMTP acknowledgement lost");

    verify(writer).finish(1L, Status.UNCERTAIN);
    verify(writer, never()).retryLater(1L);
  }

  @Test
  void tenantConfigurationRejectionRetriesAfterRepair() {
    prepareReadyClaim();
    org.mockito.Mockito.doThrow(
            new TenantSystemEmailRouteService.ConfigurationException("OWN route invalid"))
        .when(delivery)
        .sendReply(anyLong(), any(), anyString(), any(), any());

    service.deliverPending(1L);

    verify(writer).retryLater(1L);
    verify(writer, never()).finish(1L, Status.UNCERTAIN);
  }

  @Test
  void missingPlatformSmtpConfigurationDefersBeforeAttemptingSend() {
    when(sessions.findById(42L)).thenReturn(Optional.of(session(asker(true, "asker@example.net"))));
    when(writer.claim(1L)).thenReturn(Optional.of(claim(1L)));
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(routes.resolve(7L)).thenReturn(Optional.of(route));
    org.mockito.Mockito.doThrow(new IllegalStateException("SMTP is not configured"))
        .when(delivery)
        .requireConfigured(route);

    service.deliverPending(1L);

    verify(writer).retryLater(1L);
    verify(delivery, never()).sendReply(anyLong(), any(), anyString(), any(), any());
  }

  @Test
  void roomCannotRouteMailThroughAnotherRecipientsTenant() {
    var session = session(asker(true, "asker@example.net"));
    session.setTenantId(8L);
    when(sessions.findByMatrixRoomId("!room")).thenReturn(Optional.of(session));

    assertThatThrownBy(
            () -> service.onConsultantReply("!room", "$first", "@consultant:matrix.example"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Reply email recipient tenant is missing or inconsistent");
    verifyNoInteractions(routes, writer, delivery);
  }

  private static Session session(User asker) {
    return Session.builder()
        .id(42L)
        .tenantId(7L)
        .user(asker)
        .consultant(consultant())
        .matrixRoomId("!room")
        .registrationType(Session.RegistrationType.REGISTERED)
        .postcode("10000")
        .status(Session.SessionStatus.IN_PROGRESS)
        .build();
  }

  private static ReplyEmailDelivery claim(long id) {
    var claim = new ReplyEmailDelivery();
    claim.setId(id);
    claim.setSessionId(42L);
    claim.setTenantId(7L);
    claim.setRecipientUserId("asker");
    claim.setSourceMatrixUserId("@consultant:matrix.example");
    claim.setSourceRoomId("!room");
    claim.setCorrelationId("ab2e5141-2f26-456a-9e46-0ff642918115");
    return claim;
  }

  private static ReplyEmailDelivery consultantClaim() {
    var claim = claim(1L);
    claim.setRecipientKind(RecipientKind.CONSULTANT);
    claim.setRecipientUserId("consultant");
    claim.setSourceMatrixUserId("@asker:matrix.example");
    claim.setSourceRoomId("!room:matrix.example");
    return claim;
  }

  private static Session consultation() {
    var session = session(asker(true, "asker@example.net"));
    session.setMatrixRoomId("!room:matrix.example");
    session.setConversationType(ConversationType.AGENCY_COUNSELLING);
    session.setConsultant(consultant());
    return session;
  }

  private static Consultant consultant() {
    return Consultant.builder()
        .id("consultant")
        .matrixUserId("@consultant:matrix.example")
        .username("consultant")
        .firstName("Test")
        .lastName("Consultant")
        .email("consultant@example.net")
        .tenantId(7L)
        .languageCode(LanguageCode.de)
        .languageFormal(true)
        .notificationsEnabled(true)
        .notifyNewChatMessageFromAdviceSeeker(true)
        .build();
  }

  private Session prepareReadyClaim() {
    var session = session(asker(true, "asker@example.net"));
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(writer.claim(1L)).thenReturn(Optional.of(claim(1L)));
    var tenant = new RestrictedTenantDTO().id(7L).subdomain("tenant");
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenantTemplates.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.net");
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(routes.resolve(7L)).thenReturn(Optional.of(route));
    when(emailBrand.valuesForResolvedBrand(
            eq("https://tenant.example.net"), any(EmailBranding.class)))
        .thenReturn(neutralBrand());
    return session;
  }

  private static User asker(boolean enabled, String address) {
    var user =
        User.builder()
            .userId("asker")
            .matrixUserId("@asker:matrix.example")
            .username("anonymous")
            .email(address)
            .tenantId(7L)
            .languageCode(LanguageCode.de)
            .languageFormal(true)
            .notificationsEnabled(true)
            .notificationsSettings("{\"newChatMessageNotificationEnabled\":" + enabled + "}")
            .build();
    return user;
  }

  private static Map<String, String> neutralBrand() {
    return new LinkedHashMap<>(
        Map.ofEntries(
            Map.entry("platformName", "ORISO"),
            Map.entry("offeringName", "ORISO"),
            Map.entry("operatorName", ""),
            Map.entry("orgName", ""),
            Map.entry("orgAddress", ""),
            Map.entry("contactLine", ""),
            Map.entry("logoUrl", ""),
            Map.entry("primaryColor", "#a5000a"),
            Map.entry("accentColor", "#cc1e1c"),
            Map.entry("appUrl", "https://tenant.example.net"),
            Map.entry("settingsUrl", "https://tenant.example.net/profile/einstellungen"),
            Map.entry("unsubscribeUrl", "https://tenant.example.net/profile/einstellungen/email"),
            Map.entry("privacyUrl", "https://tenant.example.net/datenschutz"),
            Map.entry("imprintUrl", "https://tenant.example.net/impressum")));
  }
}
