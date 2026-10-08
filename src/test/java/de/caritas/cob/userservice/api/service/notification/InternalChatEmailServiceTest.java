package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.donotdisturb.DoNotDisturbService;
import de.caritas.cob.userservice.api.service.email.*;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.sender.*;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/** Event acknowledgement through real durable fan-out/rendering to the external mail boundary. */
class InternalChatEmailServiceTest {
  static final String ROOM = "!internal:matrix.example";
  static final String EVENT = "$internal-event";
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final GroupChatParticipantRepository participants =
      mock(GroupChatParticipantRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final IdentityAccountStatusLookup identities = mock(IdentityAccountStatusLookup.class);
  private final MatrixSynapseService matrix = mock(MatrixSynapseService.class);
  private final ReplyEmailDeliveryRepository repository = mock(ReplyEmailDeliveryRepository.class);
  private final UserDoNotDisturbRepository dnd = mock(UserDoNotDisturbRepository.class);
  private final TenantService tenants = mock(TenantService.class);
  private final ApplicationSettingsService settings = mock(ApplicationSettingsService.class);
  private final TenantSystemEmailClient mailClient = mock(TenantSystemEmailClient.class);
  private final AuthenticatedUser caller = mock(AuthenticatedUser.class);
  private final Map<Long, ReplyEmailDelivery> stored = new LinkedHashMap<>();
  private final List<Delivered> received = new ArrayList<>();
  private final ReplyEmailDeliveryWriter writer = new ReplyEmailDeliveryWriter(repository);
  private InternalChatEmailService service;
  private Consultant actor;
  private Consultant recipient;
  private Session session;
  private List<GroupChatParticipant> membership;
  private Map<String, Object> event;
  private Map<String, Object> tenantSettings;

  @BeforeEach
  void setup() {
    var ids = new AtomicLong();
    when(repository.saveAndFlush(any()))
        .thenAnswer(
            invocation -> {
              ReplyEmailDelivery row = invocation.getArgument(0);
              if (row.getId() == null) {
                if (stored.values().stream()
                    .anyMatch(
                        existing ->
                            existing.getRecipientKind() == row.getRecipientKind()
                                && existing.getRecipientUserId().equals(row.getRecipientUserId())
                                && existing.getEventKey().equals(row.getEventKey()))) {
                  throw new DataIntegrityViolationException("unique recipient/event");
                }
                row.setId(ids.incrementAndGet());
              }
              stored.put(row.getId(), row);
              return row;
            });
    when(repository.findById(anyLong()))
        .thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0))));
    when(repository.findByIdForUpdate(anyLong()))
        .thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0))));
    when(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            any(), any()))
        .thenAnswer(
            i ->
                stored.values().stream()
                    .filter(
                        row ->
                            row.getStatus() == i.getArgument(0)
                                && !row.getNextAttemptAt().isAfter(i.getArgument(1)))
                    .toList());
    when(identities.findEnabledById(anyString())).thenReturn(Optional.of(true));
    actor = consultant("actor", 7L);
    recipient = consultant("recipient", 7L);
    session =
        Session.builder()
            .id(42L)
            .tenantId(7L)
            .agencyId(13L)
            .matrixRoomId(ROOM)
            .conversationType(ConversationType.INTERNAL_GROUP)
            .status(Session.SessionStatus.IN_PROGRESS)
            .registrationType(Session.RegistrationType.REGISTERED)
            .postcode("PRIVATE_CASE_MARKER")
            .build();
    membership =
        new ArrayList<>(
            List.of(
                new GroupChatParticipant(42L, "actor"),
                new GroupChatParticipant(42L, "recipient")));
    when(sessions.findByMatrixRoomId(ROOM)).thenReturn(Optional.of(session));
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(participants.findByChatId(42L)).thenAnswer(i -> List.copyOf(membership));
    addConsultant(actor);
    addConsultant(recipient);
    when(caller.isConsultant()).thenReturn(true);
    when(caller.getUserId()).thenReturn("actor");
    when(caller.getTenantId()).thenReturn(7L);
    when(matrix.getRoomMembers(ROOM))
        .thenReturn(Optional.of(List.of(actor.getMatrixUserId(), recipient.getMatrixUserId())));
    when(matrix.loginAsUserAccessToken(actor.getMatrixUserId())).thenReturn("matrix-token");
    event =
        new HashMap<>(
            Map.of(
                "event_id",
                EVENT,
                "sender",
                actor.getMatrixUserId(),
                "type",
                "m.room.encrypted",
                "content",
                Map.of("ciphertext", "PRIVATE_ENCRYPTED_MESSAGE_MARKER")));
    when(matrix.getRoomEvent(ROOM, EVENT, "matrix-token")).thenAnswer(i -> Optional.of(event));
    tenantSettings =
        new HashMap<>(
            Map.of(
                "featureSystemNotificationEmailsEnabled",
                true,
                "smtpMode",
                "OWN",
                "smtp",
                Map.of(
                    "enabled",
                    true,
                    "host",
                    "mail.example",
                    "port",
                    587,
                    "secure",
                    false,
                    "username",
                    "fixture",
                    "from",
                    "mail@example.org",
                    "passwordSet",
                    true)));
    when(mailClient.readTenant(7L)).thenAnswer(i -> Map.of("settings", tenantSettings));
    doAnswer(
            i -> {
              received.add(new Delivered(i.getArgument(2), i.getArgument(3), i.getArgument(4)));
              return null;
            })
        .when(mailClient)
        .deliver(eq(7L), eq("NEW_MESSAGE"), anyString(), any(), any());
    var tenant = new RestrictedTenantDTO().id(7L).name("PRIVATE_TENANT_MARKER").subdomain("tenant");
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenants.getRestrictedTenantData(7L)).thenReturn(tenant);
    var templates = new TenantTemplateSupplier(tenants, settings);
    ReflectionTestUtils.setField(templates, "applicationBaseUrl", "https://app.example.net");
    ReflectionTestUtils.setField(templates, "multitenancyWithSingleDomain", true);
    var branding =
        new EmailBrandingResolver(tenants, templates, "ORISO", "", "https://app.example.net", 0L);
    var brand =
        new OrisoEmailBrand(
            new SenderOrganisationResolver(
                mock(PlatformOperatorOrganisationClient.class),
                mock(TraegerOrganisationClient.class)),
            branding);
    var route = new TenantSystemEmailRouteService(mailClient);
    var delivery =
        new TenantSystemEmailDelivery(
            mailClient, new PlatformSmtpSettingsProvider(settings), new OrisoEmailDispatcher());
    service =
        new InternalChatEmailService(
            sessions,
            participants,
            consultants,
            identities,
            matrix,
            writer,
            new DoNotDisturbService(dnd),
            tenants,
            templates,
            branding,
            brand,
            new OrisoEmailRenderer(true),
            route,
            delivery);
    ReflectionTestUtils.setField(service, "applicationBaseUrl", "https://app.example.net");
  }

  @Test
  void newInternalMessageDeliversOneNeutralMailToOtherCurrentCounsellor() {
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).hasSize(1);
    assertThat(received.getFirst().recipient()).isEqualTo("recipient@example.org");
    assertThat(received.getFirst().email().subject()).isEqualTo("Neue Nachricht auf ORISO");
    assertThat(received.getFirst().email().html())
        .contains("mail=interne-nachricht")
        .doesNotContain("PRIVATE_");
    assertThat(received.getFirst().email().text()).contains("/42").doesNotContain("PRIVATE_");
  }

  @Test
  void duplicateBrowserAcknowledgementAndMatrixEventDeliverOnlyOnce() {
    service.onMessageIntent(ROOM, EVENT, caller);
    service.onMessageIntent(ROOM, EVENT, caller);
    service.onMatrixMessage(ROOM, EVENT, actor.getMatrixUserId());
    drain();
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).hasSize(1);
  }

  @Test
  void removedParticipantBetweenFanOutAndDispatchReceivesNoMail() {
    service.onMessageIntent(ROOM, EVENT, caller);
    writer.pendingIds().forEach(service::resolveIntent);
    membership.removeIf(row -> row.getConsultantId().equals("recipient"));
    writer.pendingIds().forEach(service::deliverPending);
    assertThat(received).isEmpty();
  }

  @Test
  void departedMatrixMemberBetweenFanOutAndDispatchReceivesNoMail() {
    service.onMessageIntent(ROOM, EVENT, caller);
    writer.pendingIds().forEach(service::resolveIntent);
    when(matrix.getRoomMembers(ROOM)).thenReturn(Optional.of(List.of(actor.getMatrixUserId())));
    writer.pendingIds().forEach(service::deliverPending);
    assertThat(received).isEmpty();
  }

  @Test
  void currentGroupMembershipDoesNotEnrollAdviceSeekersOrOtherTenants() {
    var outsider = consultant("other-tenant", 8L);
    addConsultant(outsider);
    membership.add(new GroupChatParticipant(42L, outsider.getId()));
    membership.add(new GroupChatParticipant(42L, "advice-seeker"));
    when(matrix.getRoomMembers(ROOM))
        .thenReturn(
            Optional.of(
                List.of(
                    actor.getMatrixUserId(),
                    recipient.getMatrixUserId(),
                    outsider.getMatrixUserId(),
                    "@advice-seeker:matrix.example")));
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).extracting(Delivered::recipient).containsExactly("recipient@example.org");
  }

  @Test
  void internalPreferenceDisabledSuppressesMailIndependentlyOfFeedbackPreference() {
    recipient.setNotificationsSettings(
        "{\"internalChatNotificationEnabled\":false,\"feedbackNotificationEnabled\":true}");
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).isEmpty();
  }

  @Test
  void disabledFeedbackAndAdviceSeekerSettingsDoNotMuteInternalMessages() {
    recipient.setNotificationsSettings(
        "{\"newChatMessageNotificationEnabled\":false,\"feedbackNotificationEnabled\":false}");
    recipient.setNotifyNewChatMessageFromAdviceSeeker(false);
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).hasSize(1);
  }

  @Test
  void globalEmailOptOutSuppressesInternalMailEvenWhenSpecificPreferenceDefaultsOn() {
    recipient.setNotificationsEnabled(false);
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).isEmpty();
  }

  @Test
  void disabledTenantMailNeverRetriesOrSendsAfterThePolicyIsReenabled() {
    tenantSettings.put("featureSystemNotificationEmailsEnabled", false);
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    tenantSettings.put("featureSystemNotificationEmailsEnabled", true);
    makeRetryDue();
    drain();
    assertThat(received).isEmpty();
  }

  @Test
  void unavailableMailSetupRetriesAndRecoversWithoutResendingTheMessage() {
    tenantSettings.remove("smtpMode");
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).isEmpty();
    tenantSettings.put("smtpMode", "OWN");
    makeRetryDue();
    drain();
    assertThat(received).hasSize(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"sender", "event-id", "plaintext", "empty", "edit", "reaction"})
  void forgedOrNonMessageMatrixEventsCannotGenerateMail(String invalid) {
    switch (invalid) {
      case "sender" -> event.put("sender", recipient.getMatrixUserId());
      case "event-id" -> event.put("event_id", "$different-event");
      case "plaintext" -> event.put("type", "m.room.message");
      case "empty" -> event.put("content", Map.of("ciphertext", ""));
      case "edit" ->
          event.put(
              "content",
              Map.of("ciphertext", "opaque", "m.relates_to", Map.of("rel_type", "m.replace")));
      case "reaction" ->
          event.put(
              "content",
              Map.of("ciphertext", "opaque", "m.relates_to", Map.of("rel_type", "m.annotation")));
    }
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ConversationType.class,
      names = {"AGENCY_COUNSELLING", "LIVE_CHAT", "SELF_HELP"})
  void ordinaryCaseAndSelfHelpRoomsCannotGenerateInternalMail(ConversationType type) {
    session.setConversationType(type);
    service.onMessageIntent(ROOM, EVENT, caller);
    service.onMatrixMessage(ROOM, EVENT, actor.getMatrixUserId());
    drain();
    assertThat(received).isEmpty();
  }

  @Test
  void confidentialAsideRoomCannotBorrowItsCaseGroupLabel() {
    session.setMatrixRoomId("!primary:matrix.example");
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).isEmpty();
  }

  @Test
  void authenticatedCallerFromOtherTenantCannotReserveInternalMail() {
    when(caller.getTenantId()).thenReturn(8L);
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).isEmpty();
  }

  @Test
  void nonParticipantCannotGenerateMailEvenWithMatchingMatrixSender() {
    membership.removeIf(row -> row.getConsultantId().equals("actor"));
    service.onMessageIntent(ROOM, EVENT, caller);
    service.onMatrixMessage(ROOM, EVENT, actor.getMatrixUserId());
    drain();
    assertThat(received).isEmpty();
  }

  private void makeRetryDue() {
    // Advance the simulated database clock without inspecting delivery outcomes.
    stored.values().forEach(row -> row.setNextAttemptAt(LocalDateTime.now().minusSeconds(1)));
  }

  @Test
  void browserMessageAcknowledgementTriggersInternalMailWithoutAnExtraIntentFlag() {
    var controller =
        new de.caritas.cob.userservice.api.adapters.web.controller.EventNotificationController(
            mock(EventNotificationService.class),
            mock(TeamDiscussionNotificationService.class),
            mock(FeedbackMessageEmailService.class),
            service,
            caller,
            mock(MessageEventCurrentAccessService.class),
            Optional.empty());
    var request =
        new de.caritas.cob.userservice.api.adapters.web.controller.EventNotificationController
            .MessageEventRequestDTO();
    request.setRoomId(ROOM);
    request.setMatrixEventId(EVENT);
    assertThat(controller.createMessageEventNotification(request).getStatusCode().value())
        .isEqualTo(204);
    drain();
    assertThat(received).hasSize(1);
  }

  @Test
  void updatingAnotherEmailSettingPreservesAnExistingInternalChatOptOut() throws Exception {
    recipient.setNotificationsSettings("{\"internalChatNotificationEnabled\":false}");
    var patch =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(
                "{\"emailNotificationsEnabled\":true,\"settings\":{\"initialEnquiryNotificationEnabled\":false}}",
                de.caritas.cob.userservice.api.adapters.web.dto.EmailNotificationsDTO.class);
    new de.caritas.cob.userservice.api.UserServiceMapper(
            new de.caritas.cob.userservice.api.helper.UsernameTranscoder())
        .consultantOf(recipient, Map.of("emailNotifications", patch));
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(OrisoEmailRenderer.Tone.class)
  void smtpReceiptIsNeutralMultipartWithOneDedicatedOptOutSelector(OrisoEmailRenderer.Tone tone)
      throws Exception {
    recipient.setLanguageCode(
        LanguageCode.valueOf(
            tone == OrisoEmailRenderer.Tone.DE_FORMAL || tone == OrisoEmailRenderer.Tone.DE_INFORMAL
                ? "de"
                : tone.name().toLowerCase()));
    recipient.setLanguageFormal(tone != OrisoEmailRenderer.Tone.DE_INFORMAL);
    tenantSettings.put("smtpMode", "PLATFORM");
    when(settings.getGlobalSmtpSettingsSnapshot())
        .thenReturn(
            Optional.of(
                PlatformSmtpSettingsFixture.credentials("fixture-account", "fixture-secret")));
    List<jakarta.mail.internet.MimeMessage> mime = new ArrayList<>();
    try (var transport =
        org.mockito.Mockito.mockStatic(
            OrisoSmtpTransport.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
      transport
          .when(() -> OrisoSmtpTransport.send(any(jakarta.mail.Message.class)))
          .thenAnswer(
              call -> {
                mime.add(call.getArgument(0));
                return null;
              });
      service.onMessageIntent(ROOM, EVENT, caller);
      drain();
    }
    assertThat(mime).hasSize(1);
    var message = mime.getFirst();
    assertThat(message.getAllRecipients())
        .extracting(Object::toString)
        .containsExactly("recipient@example.org");
    assertThat(message.getSubject()).doesNotContain("PRIVATE_", "actor", "recipient");
    assertThat(UUID.fromString(message.getHeader("X-ORISO-Delivery-ID", null))).isNotNull();
    var multipart = (jakarta.mail.internet.MimeMultipart) message.getContent();
    assertThat(multipart.getContentType()).startsWith("multipart/alternative");
    for (int part = 0; part < 2; part++) {
      String content = (String) multipart.getBodyPart(part).getContent();
      assertThat(content).contains("mail=interne-nachricht").doesNotContain("PRIVATE_", "{{");
      assertThat(org.apache.commons.lang3.StringUtils.countMatches(content, "mail=")).isEqualTo(1);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ConsultantStatus.class,
      names = {"CREATED", "ERROR", "IN_DELETION"})
  void incompleteOrDeletingRecipientCannotReceiveInternalMail(ConsultantStatus status) {
    recipient.setStatus(status);
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ConsultantStatus.class,
      names = {"CREATED", "ERROR", "IN_DELETION"})
  void incompleteOrDeletingSenderCannotGenerateInternalMail(ConsultantStatus status) {
    actor.setStatus(status);
    service.onMessageIntent(ROOM, EVENT, caller);
    service.onMatrixMessage(ROOM, EVENT, actor.getMatrixUserId());
    drain();
    assertThat(received).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"actor", "recipient"})
  void suspendedIdentityCannotGenerateOrReceiveMail(String userId) {
    when(identities.findEnabledById(userId)).thenReturn(Optional.of(false));
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).isEmpty();
    doReturn(Optional.of(true)).when(identities).findEnabledById(userId);
    makeRetryDue();
    drain();
    assertThat(received).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"actor", "recipient"})
  void identityOutageRetriesWithoutLosingTheMessage(String userId) {
    when(identities.findEnabledById(userId))
        .thenThrow(new IllegalStateException("fixture-secret-not-for-logs"));
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).isEmpty();
    doReturn(Optional.of(true)).when(identities).findEnabledById(userId);
    makeRetryDue();
    drain();
    assertThat(received).hasSize(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"actor", "recipient"})
  void suspensionAfterFanOutPreventsPendingDelivery(String userId) {
    service.onMessageIntent(ROOM, EVENT, caller);
    writer.pendingIds().forEach(service::resolveIntent);
    when(identities.findEnabledById(userId)).thenReturn(Optional.of(false));
    drain();
    assertThat(received).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "policy,TENANT_POLICY,NOTIFICATION_POLICY_INVALID",
    "mode,TENANT_SMTP,SMTP_MODE_INVALID",
    "own,TENANT_SMTP,OWN_SMTP_INCOMPLETE",
    "membership,MATRIX_MEMBERSHIP,DEPENDENCY_UNAVAILABLE",
    "event,MATRIX_EVENT,DEPENDENCY_UNAVAILABLE",
    "identity,IDENTITY,DEPENDENCY_UNAVAILABLE",
    "context,TENANT_CONTEXT,TENANT_UNAVAILABLE"
  })
  void retryDiagnosticsIdentifySafeActionableStageAndReason(
      String failure, String stage, String reason) {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(InternalChatEmailService.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      switch (failure) {
        case "policy" -> tenantSettings.remove("featureSystemNotificationEmailsEnabled");
        case "mode" -> tenantSettings.remove("smtpMode");
        case "own" -> tenantSettings.put("smtp", Map.of("enabled", true));
        case "membership" -> when(matrix.getRoomMembers(ROOM)).thenReturn(Optional.empty());
        case "event" ->
            when(matrix.getRoomEvent(ROOM, EVENT, "matrix-token")).thenReturn(Optional.empty());
        case "identity" ->
            when(identities.findEnabledById("actor"))
                .thenThrow(new IllegalStateException("fixture-secret-not-for-logs"));
        case "context" -> when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(null);
      }
      service.onMessageIntent(ROOM, EVENT, caller);
      drain();
      assertThat(received).isEmpty();
      assertThat(appender.list)
          .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .anySatisfy(message -> assertThat(message).contains("stage=" + stage, "reason=" + reason))
          .allSatisfy(
              message ->
                  assertThat(message)
                      .doesNotContain(
                          "fixture-secret",
                          "matrix-token",
                          "PRIVATE_",
                          "actor@example",
                          "recipient@example"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  private void drain() {
    for (int pass = 0; pass < 2; pass++) {
      for (long id : writer.pendingIds()) {
        if (writer.kindOf(id).orElseThrow() == ReplyEmailDelivery.RecipientKind.INTERNAL_INTENT)
          service.resolveIntent(id);
        else service.deliverPending(id);
      }
    }
  }

  private void addConsultant(Consultant consultant) {
    when(consultants.findByIdAndDeleteDateIsNull(consultant.getId()))
        .thenReturn(Optional.of(consultant));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(consultant.getMatrixUserId()))
        .thenReturn(Optional.of(consultant));
  }

  private static Consultant consultant(String id, Long tenantId) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(tenantId);
    consultant.setMatrixUserId("@" + id + ":matrix.example");
    consultant.setEmail(id + "@example.org");
    consultant.setNotificationsEnabled(true);
    consultant.setLanguageCode(LanguageCode.de);
    consultant.setLanguageFormal(true);
    return consultant;
  }

  private record Delivered(
      String recipient, OrisoEmailRenderer.RenderedEmail email, UUID correlation) {}
}
