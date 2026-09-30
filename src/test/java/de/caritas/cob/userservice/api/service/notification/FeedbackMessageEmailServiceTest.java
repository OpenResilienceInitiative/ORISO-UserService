package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.RecipientKind;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.Status;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackMessageEmailServiceTest {
  private static final String ROOM = "!feedback:matrix.example";
  private static final String PRIMARY = "!primary:matrix.example";
  private static final String EVENT = "$feedback-event";

  @Mock private SessionRepository sessions;
  @Mock private SessionSupervisorRepository supervision;
  @Mock private ConsultantRepository consultants;
  @Mock private ConsultantAgencyRepository agencies;
  @Mock private MatrixSynapseService matrix;
  @Mock private ReplyEmailDeliveryWriter writer;
  @Mock private DoNotDisturbService doNotDisturb;
  @Mock private TenantService tenants;
  @Mock private TenantTemplateSupplier tenantTemplates;
  @Mock private EmailBrandingResolver branding;
  @Mock private OrisoEmailBrand emailBrand;
  @Mock private OrisoEmailRenderer renderer;
  @Mock private TenantSystemEmailRouteService routes;
  @Mock private TenantSystemEmailDelivery delivery;

  private FeedbackMessageEmailService service;
  private Session session;
  private Consultant owner;
  private Consultant supervisor;

  @BeforeEach
  void setUp() {
    service =
        new FeedbackMessageEmailService(
            sessions,
            supervision,
            consultants,
            agencies,
            matrix,
            writer,
            doNotDisturb,
            tenants,
            tenantTemplates,
            branding,
            emailBrand,
            renderer,
            routes,
            delivery);
    owner = consultant("owner", "@owner:matrix.example", false);
    supervisor = consultant("supervisor", "@supervisor:matrix.example", true);
    session =
        Session.builder()
            .id(42L)
            .tenantId(7L)
            .agencyId(13L)
            .consultant(owner)
            .matrixRoomId(PRIMARY)
            .registrationType(Session.RegistrationType.REGISTERED)
            .status(Session.SessionStatus.IN_PROGRESS)
            .supervisionOptedOut(false)
            .postcode("PRIVATE_CASE_MARKER")
            .build();
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(supervision.findByMatrixRoomIdAndIsActiveTrue(ROOM))
        .thenReturn(List.of(supervision(supervisor)));
    when(agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(anyString(), eq(13L)))
        .thenReturn(true);
    when(consultants.findByIdAndDeleteDateIsNull("owner")).thenReturn(Optional.of(owner));
    when(consultants.findByIdAndDeleteDateIsNull("supervisor")).thenReturn(Optional.of(supervisor));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(owner.getMatrixUserId()))
        .thenReturn(Optional.of(owner));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(supervisor.getMatrixUserId()))
        .thenReturn(Optional.of(supervisor));
    when(matrix.loginAsUserAccessToken(anyString())).thenReturn("matrix-token");
    when(matrix.getRoomEvent(eq(ROOM), eq(EVENT), eq("matrix-token")))
        .thenReturn(Optional.of(event(owner.getMatrixUserId())));
    when(matrix.getRoomMembers(ROOM))
        .thenReturn(Optional.of(List.of(owner.getMatrixUserId(), supervisor.getMatrixUserId())));
  }

  @Test
  void ownerFeedbackReservesOnlyActiveSupervisorAfterMatrixEventVerification() {
    var intent = claim(RecipientKind.FEEDBACK_INTENT, owner, "owner");
    when(writer.claim(1L)).thenReturn(Optional.of(intent));

    service.resolveIntent(1L);

    verify(matrix).getRoomEvent(ROOM, EVENT, "matrix-token");
    verify(writer).reserveFeedbackRecipient("supervisor", intent);
    verify(writer).finish(1L, Status.RESOLVED);
    verify(writer, never()).reserveFeedbackRecipient("owner", intent);
  }

  @Test
  void activeSupervisorFeedbackReservesCurrentOwnerRatherThanTheAddingConsultant() {
    var intent = claim(RecipientKind.FEEDBACK_INTENT, supervisor, "supervisor");
    when(writer.claim(1L)).thenReturn(Optional.of(intent));
    when(matrix.getRoomEvent(ROOM, EVENT, "matrix-token"))
        .thenReturn(Optional.of(event(supervisor.getMatrixUserId())));

    service.resolveIntent(1L);

    verify(writer).reserveFeedbackRecipient("owner", intent);
    verify(writer, never()).reserveFeedbackRecipient("supervisor", intent);
    verify(writer).finish(1L, Status.RESOLVED);
  }

  @Test
  void aMisconfiguredSelfSupervisionRowNeverProducesSelfMail() {
    var intent = claim(RecipientKind.FEEDBACK_INTENT, owner, "owner");
    when(writer.claim(1L)).thenReturn(Optional.of(intent));
    owner.setSupervisor(true);
    when(supervision.findByMatrixRoomIdAndIsActiveTrue(ROOM))
        .thenReturn(List.of(supervision(owner)));

    service.resolveIntent(1L);

    verify(writer, never()).reserveFeedbackRecipient(anyString(), any());
    verify(writer).finish(1L, Status.RESOLVED);
  }

  @Test
  void forgedSenderOrEditedMatrixEventCannotProduceARecipientClaim() {
    var intent = claim(RecipientKind.FEEDBACK_INTENT, owner, "owner");
    when(writer.claim(1L)).thenReturn(Optional.of(intent));
    when(matrix.getRoomEvent(ROOM, EVENT, "matrix-token"))
        .thenReturn(Optional.of(event(supervisor.getMatrixUserId())));

    service.resolveIntent(1L);

    verify(writer).finish(1L, Status.REJECTED);
    verify(writer, never()).reserveFeedbackRecipient(anyString(), any());
  }

  @Test
  void matrixEditRelationCannotProduceAFeedbackMail() {
    var intent = claim(RecipientKind.FEEDBACK_INTENT, owner, "owner");
    when(writer.claim(1L)).thenReturn(Optional.of(intent));
    when(matrix.getRoomEvent(ROOM, EVENT, "matrix-token"))
        .thenReturn(
            Optional.of(
                Map.of(
                    "event_id",
                    EVENT,
                    "sender",
                    owner.getMatrixUserId(),
                    "type",
                    "m.room.encrypted",
                    "content",
                    Map.of(
                        "ciphertext",
                        "opaque-encrypted-payload",
                        "m.relates_to",
                        Map.of("rel_type", "m.replace")))));

    service.resolveIntent(1L);

    verify(writer).finish(1L, Status.REJECTED);
    verify(writer, never()).reserveFeedbackRecipient(anyString(), any());
  }

  @Test
  void encryptedReplyRelationWithoutRelTypeStillResolvesFeedback() {
    var intent = claim(RecipientKind.FEEDBACK_INTENT, owner, "owner");
    when(writer.claim(1L)).thenReturn(Optional.of(intent));
    when(matrix.getRoomEvent(ROOM, EVENT, "matrix-token"))
        .thenReturn(
            Optional.of(
                Map.of(
                    "event_id",
                    EVENT,
                    "sender",
                    owner.getMatrixUserId(),
                    "type",
                    "m.room.encrypted",
                    "content",
                    Map.of(
                        "ciphertext",
                        "opaque-encrypted-payload",
                        "m.relates_to",
                        Map.of("m.in_reply_to", Map.of("event_id", "$parent"))))));

    service.resolveIntent(1L);

    verify(writer).reserveFeedbackRecipient("supervisor", intent);
    verify(writer).finish(1L, Status.RESOLVED);
  }

  @Test
  void unknownMatrixEventRetriesDurableIntentAndCanDeliverAfterItBecomesVisible() {
    var intent = claim(RecipientKind.FEEDBACK_INTENT, owner, "owner");
    when(writer.claim(1L)).thenReturn(Optional.of(intent));
    when(matrix.getRoomEvent(ROOM, EVENT, "matrix-token"))
        .thenReturn(Optional.empty(), Optional.of(event(owner.getMatrixUserId())));

    service.resolveIntent(1L);
    verify(writer).retryLater(1L);
    verify(writer, never()).reserveFeedbackRecipient(anyString(), any());

    service.resolveIntent(1L);
    verify(writer).reserveFeedbackRecipient("supervisor", intent);
    verify(writer).finish(1L, Status.RESOLVED);
  }

  @Test
  void revokedSupervisorOrPrimaryRoomCannotCreateFeedbackIntent() {
    var caller = caller("supervisor");
    session.setSupervisionOptedOut(true);
    service.onFeedbackIntent(ROOM, EVENT, caller);
    session.setSupervisionOptedOut(false);
    service.onFeedbackIntent(PRIMARY, EVENT, caller);

    verify(writer, never())
        .reserveFeedbackIntent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong());
  }

  @Test
  void aSideRoomIdSharedByDifferentCasesFailsClosed() {
    var otherCase =
        Session.builder()
            .id(99L)
            .tenantId(7L)
            .agencyId(13L)
            .consultant(owner)
            .matrixRoomId("!other-primary:matrix.example")
            .registrationType(Session.RegistrationType.REGISTERED)
            .postcode("PRIVATE_OTHER_CASE")
            .status(Session.SessionStatus.IN_PROGRESS)
            .build();
    var conflicting = supervision(supervisor);
    conflicting.setSession(otherCase);
    when(supervision.findByMatrixRoomIdAndIsActiveTrue(ROOM))
        .thenReturn(List.of(supervision(supervisor), conflicting));

    service.onFeedbackIntent(ROOM, EVENT, caller("owner"));

    verify(writer, never())
        .reserveFeedbackIntent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong());
  }

  @Test
  void postResponseReplayIsIdempotentAndDoesNotNeedTheMatrixEventToBeVisibleYet() {
    var caller = caller("owner");
    org.mockito.Mockito.doThrow(new DataIntegrityViolationException("already reserved"))
        .when(writer)
        .reserveFeedbackIntent(
            eq("owner"),
            eq(owner.getMatrixUserId()),
            eq(ROOM),
            eq(EVENT),
            anyString(),
            eq(7L),
            eq(42L));

    service.onFeedbackIntent(ROOM, EVENT, caller);

    verify(writer)
        .reserveFeedbackIntent(
            eq("owner"),
            eq(owner.getMatrixUserId()),
            eq(ROOM),
            eq(EVENT),
            anyString(),
            eq(7L),
            eq(42L));
    verify(matrix, never()).getRoomEvent(anyString(), anyString(), anyString());
  }

  @Test
  void unknownMembershipDefersTheMailAndARecoveryCanSendItOnce() {
    var pending = claim(RecipientKind.FEEDBACK, owner, "supervisor");
    pending.setCorrelationId("ab2e5141-2f26-456a-9e46-0ff642918115");
    when(writer.claim(2L)).thenReturn(Optional.of(pending));
    when(matrix.getRoomMembers(ROOM))
        .thenReturn(
            Optional.empty(),
            Optional.of(List.of(owner.getMatrixUserId(), supervisor.getMatrixUserId())));
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    var tenant = new RestrictedTenantDTO().id(7L).subdomain("tenant");
    when(routes.resolve(7L)).thenReturn(Optional.of(route));
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenantTemplates.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.net");
    when(branding.resolveNotification(7L, "https://tenant.example.net"))
        .thenReturn(EmailBranding.neutral());
    when(emailBrand.values("https://tenant.example.net", null))
        .thenReturn(new HashMap<>(Map.of("platformName", "Community Hub")));
    when(renderer.render(eq("rueckmeldung"), any(), any()))
        .thenReturn(new OrisoEmailRenderer.RenderedEmail("New message", "<html/>", "Text"));
    ReflectionTestUtils.setField(service, "multitenancyEnabled", true);

    service.deliverPending(2L);
    verify(writer).retryLater(2L);
    verifyNoSmtp();

    service.deliverPending(2L);
    verify(routes).resolve(7L);
    verify(delivery, times(1))
        .sendReply(eq(7L), eq(route), eq("supervisor@example.net"), any(), any());
    verify(writer).finish(2L, Status.SENT);
  }

  @Test
  void everyFeedbackLocaleProducesNeutralMultipartMailWithTheProtectedCaseAction()
      throws Exception {
    var pending = claim(RecipientKind.FEEDBACK, owner, "supervisor");
    pending.setCorrelationId("ab2e5141-2f26-456a-9e46-0ff642918115");
    owner.setUsername("PRIVATE_AUTHOR_MARKER");
    supervisor.setFirstName("PRIVATE_RECIPIENT_MARKER");
    when(writer.claim(2L)).thenReturn(Optional.of(pending));
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    var tenant = new RestrictedTenantDTO().id(7L).subdomain("tenant");
    when(routes.resolve(7L)).thenReturn(Optional.of(route));
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenantTemplates.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.net");
    when(branding.resolveNotification(7L, "https://tenant.example.net"))
        .thenReturn(
            new EmailBranding(
                "PRIVATE_CENTRE_MARKER",
                "https://tenant.example.net/tenant-logo.png",
                "#112233",
                null,
                null));
    when(emailBrand.readablePrimary("#112233")).thenReturn("#112233");
    when(emailBrand.values("https://tenant.example.net", null))
        .thenAnswer(ignored -> neutralBrand());
    var tested =
        new FeedbackMessageEmailService(
            sessions,
            supervision,
            consultants,
            agencies,
            matrix,
            writer,
            doNotDisturb,
            tenants,
            tenantTemplates,
            branding,
            emailBrand,
            new OrisoEmailRenderer(true),
            routes,
            delivery);
    ReflectionTestUtils.setField(tested, "multitenancyEnabled", true);
    var smtp =
        new PlatformSmtpSettingsProvider.Settings(
            "smtp.example.net", 587, false, "account", "secret", "sender@example.net", "#112233");

    for (var tone : OrisoEmailRenderer.Tone.values()) {
      supervisor.setLanguageCode(
          LanguageCode.valueOf(
              tone == OrisoEmailRenderer.Tone.DE_FORMAL
                      || tone == OrisoEmailRenderer.Tone.DE_INFORMAL
                  ? "de"
                  : tone.name().toLowerCase()));
      supervisor.setLanguageFormal(tone != OrisoEmailRenderer.Tone.DE_INFORMAL);
      tested.deliverPending(2L);
    }

    var rendered = ArgumentCaptor.forClass(OrisoEmailRenderer.RenderedEmail.class);
    verify(delivery, times(7))
        .sendReply(eq(7L), eq(route), eq("supervisor@example.net"), rendered.capture(), any());
    try (MockedStatic<OrisoSmtpTransport> transport =
        Mockito.mockStatic(OrisoSmtpTransport.class, Mockito.CALLS_REAL_METHODS)) {
      transport.when(() -> OrisoSmtpTransport.send(any(Message.class))).thenAnswer(call -> null);
      for (var mail : rendered.getAllValues()) {
        new OrisoEmailDispatcher()
            .sendOrThrow(smtp, "supervisor@example.net", mail, UUID.randomUUID());
      }
      var messages = ArgumentCaptor.forClass(Message.class);
      transport.verify(() -> OrisoSmtpTransport.send(messages.capture()), times(7));
      for (int index = 0; index < messages.getAllValues().size(); index++) {
        var tone = OrisoEmailRenderer.Tone.values()[index];
        var mime = (MimeMessage) messages.getAllValues().get(index);
        var parts = (MimeMultipart) mime.getContent();
        assertThat(parts.getCount()).isEqualTo(2);
        String plain = parts.getBodyPart(0).getContent().toString();
        String html = parts.getBodyPart(1).getContent().toString();
        String all = mime.getSubject() + plain + html;
        assertThat(mime.getSubject())
            .isEqualTo(
                new OrisoEmailRenderer(true)
                    .subjectOf("rueckmeldung", tone)
                    .replace("{{platformName}}", "Community Hub"))
            .contains("Community Hub");
        assertThat(html).contains(new OrisoEmailRenderer(true).preheaderOf("rueckmeldung", tone));
        assertThat(all)
            .contains("https://tenant.example.net/tenant-logo.png")
            .contains("#112233")
            .contains(
                "https://tenant.example.net/sessions/consultant/sessionView/session/42?channel=supervision&amp;at=%24feedback-event")
            .contains("mail=rueckmeldung")
            .doesNotContain(
                "PRIVATE_AUTHOR_MARKER",
                "PRIVATE_RECIPIENT_MARKER",
                "PRIVATE_PERSON_MARKER",
                "PRIVATE_CASE_MARKER",
                "PRIVATE_CENTRE_MARKER",
                "{{caseReference}}",
                "{{requestReceivedAt}}",
                "{{");
      }
    }
  }

  @Test
  void missingSmtpRouteKeepsFeedbackPendingWithoutAProviderFallback() {
    var pending = claim(RecipientKind.FEEDBACK, owner, "supervisor");
    when(writer.claim(2L)).thenReturn(Optional.of(pending));
    when(routes.resolve(7L)).thenReturn(Optional.empty());

    service.deliverPending(2L);

    verify(writer).retryLater(2L);
    verifyNoSmtp();
  }

  @Test
  void revokedSupervisionRejectsPreviouslyReservedMail() {
    var reservedForOldSupervisor = claim(RecipientKind.FEEDBACK, owner, "supervisor");
    when(writer.claim(2L)).thenReturn(Optional.of(reservedForOldSupervisor));
    when(supervision.findByMatrixRoomIdAndIsActiveTrue(ROOM)).thenReturn(List.of());

    service.deliverPending(2L);

    verify(writer).finish(2L, Status.REJECTED);
    verifyNoSmtp();
  }

  @Test
  void reassignmentRejectsAQueuedMailFromThePreviousOwner() {
    var reserved = claim(RecipientKind.FEEDBACK, owner, "supervisor");
    when(writer.claim(2L)).thenReturn(Optional.of(reserved));
    session.setConsultant(consultant("new-owner", "@new-owner:matrix.example", false));

    service.deliverPending(2L);

    verify(writer).finish(2L, Status.REJECTED);
    verifyNoSmtp();
  }

  @Test
  void knownMissingRoomMemberRejectsButUnknownMembershipRetries() {
    var intent = claim(RecipientKind.FEEDBACK_INTENT, owner, "owner");
    when(writer.claim(1L)).thenReturn(Optional.of(intent));
    when(matrix.getRoomMembers(ROOM)).thenReturn(Optional.of(List.of(owner.getMatrixUserId())));

    service.resolveIntent(1L);

    verify(writer, never()).reserveFeedbackRecipient(anyString(), any());
    verify(writer).finish(1L, Status.RESOLVED);
  }

  @Test
  void absentOrOptedOutSupervisorReceivesNoFeedbackClaim() {
    var intent = claim(RecipientKind.FEEDBACK_INTENT, owner, "owner");
    when(writer.claim(1L)).thenReturn(Optional.of(intent));
    supervisor.setAbsent(true);

    service.resolveIntent(1L);

    verify(writer, never()).reserveFeedbackRecipient(anyString(), any());
    verify(writer).finish(1L, Status.RESOLVED);
  }

  @Test
  void feedbackPreferenceOffPreventsARecipientClaim() {
    var intent = claim(RecipientKind.FEEDBACK_INTENT, owner, "owner");
    when(writer.claim(1L)).thenReturn(Optional.of(intent));
    supervisor.setNotificationsSettings("{\"feedbackNotificationEnabled\":false}");

    service.resolveIntent(1L);

    verify(writer, never()).reserveFeedbackRecipient(anyString(), any());
    verify(writer).finish(1L, Status.RESOLVED);
  }

  @Test
  void removedAgencyAssociationPreventsRecipientFanOut() {
    var intent = claim(RecipientKind.FEEDBACK_INTENT, owner, "owner");
    when(writer.claim(1L)).thenReturn(Optional.of(intent));
    when(agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull("supervisor", 13L))
        .thenReturn(false);

    service.resolveIntent(1L);

    verify(writer, never()).reserveFeedbackRecipient(anyString(), any());
    verify(writer).finish(1L, Status.RESOLVED);
  }

  @Test
  void differentTenantOrRemovedAgencyPreventsAQueuedMailFromLeaving() {
    var claim = claim(RecipientKind.FEEDBACK, owner, "supervisor");
    claim.setTenantId(8L);
    when(writer.claim(2L)).thenReturn(Optional.of(claim));

    service.deliverPending(2L);

    verify(writer).finish(2L, Status.REJECTED);
    verifyNoSmtp();
  }

  private void verifyNoSmtp() {
    verify(delivery, never()).sendReply(anyLong(), any(), anyString(), any(), any());
  }

  private static Map<String, String> neutralBrand() {
    return new LinkedHashMap<>(
        Map.ofEntries(
            Map.entry("platformName", "Community Hub"),
            Map.entry("offeringName", "Community Hub"),
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

  private SessionSupervisor supervision(Consultant who) {
    return SessionSupervisor.builder()
        .session(session)
        .supervisorConsultant(who)
        .addedByConsultant(owner)
        .matrixRoomId(ROOM)
        .isActive(true)
        .build();
  }

  private static Consultant consultant(String id, String matrixId, boolean isSupervisor) {
    return Consultant.builder()
        .id(id)
        .username(id)
        .firstName("PRIVATE_PERSON_MARKER")
        .lastName("PRIVATE_PERSON_MARKER")
        .matrixUserId(matrixId)
        .email(id + "@example.net")
        .tenantId(7L)
        .supervisor(isSupervisor)
        .notificationsEnabled(true)
        .languageCode(LanguageCode.de)
        .languageFormal(true)
        .build();
  }

  private static AuthenticatedUser caller(String id) {
    return new AuthenticatedUser(id, id, Set.of("consultant"), "secret-not-logged", 7L, Set.of());
  }

  private static ReplyEmailDelivery claim(
      RecipientKind kind, Consultant source, String recipientUserId) {
    var claim = new ReplyEmailDelivery();
    claim.setId(1L);
    claim.setRecipientKind(kind);
    claim.setRecipientUserId(recipientUserId);
    claim.setSourceMatrixUserId(source.getMatrixUserId());
    claim.setSourceRoomId(ROOM);
    claim.setSourceEventId(EVENT);
    claim.setEventKey("opaque-event-hash");
    claim.setSessionId(42L);
    claim.setTenantId(7L);
    return claim;
  }

  private static Map<String, Object> event(String sender) {
    return Map.of(
        "event_id",
        EVENT,
        "sender",
        sender,
        "type",
        "m.room.encrypted",
        "content",
        Map.of("ciphertext", "opaque-encrypted-payload"));
  }
}
