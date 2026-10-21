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
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Event acknowledgement through real durable fan-out/rendering to the external mail boundary.
 * Ported from US#1376; FE#1725 story 40 adds the tenant switch tests.
 */
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
  private final java.util.concurrent.atomic.AtomicBoolean failNextRecipientInsert =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final List<Delivered> received = new ArrayList<>();
  private final ReplyEmailDeliveryWriter writer = new ReplyEmailDeliveryWriter(repository);
  private InternalChatEmailService service;
  private Consultant actor;
  private Consultant recipient;
  private Session session;
  private List<GroupChatParticipant> membership;
  private Map<String, Object> event;
  private Map<String, Object> tenantSettings;
  private RestrictedTenantDTO tenant;

  @BeforeEach
  void setup() {
    var ids = new AtomicLong();
    when(repository.saveAndFlush(any()))
        .thenAnswer(
            invocation -> {
              ReplyEmailDelivery row = invocation.getArgument(0);
              if (row.getId() == null
                  && row.getRecipientKind() == ReplyEmailDelivery.RecipientKind.INTERNAL
                  && failNextRecipientInsert.getAndSet(false)) {
                throw new DataIntegrityViolationException("other constraint");
              }
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
    when(repository.existsByRecipientKindAndRecipientUserIdAndEventKey(any(), any(), any()))
        .thenAnswer(
            i ->
                stored.values().stream()
                    .anyMatch(
                        row ->
                            row.getRecipientKind() == i.getArgument(0)
                                && row.getRecipientUserId().equals(i.getArgument(1))
                                && row.getEventKey().equals(i.getArgument(2))));
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
    tenant = new RestrictedTenantDTO().id(7L).name("PRIVATE_TENANT_MARKER").subdomain("tenant");
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
  @org.junit.jupiter.params.provider.ValueSource(strings = {"m.text", "m.emote", "m.file"})
  void unencryptedInternalRoomMessageDeliversMail(String msgtype) {
    event.put("type", "m.room.message");
    event.put(
        "content",
        msgtype.equals("m.file")
            ? Map.of("msgtype", msgtype, "body", "plan.pdf", "url", "mxc://matrix.example/f")
            : Map.of("msgtype", msgtype, "body", "PRIVATE_PLAINTEXT_MARKER"));
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).hasSize(1);
    assertThat(received.get(0).toString()).doesNotContain("PRIVATE_PLAINTEXT_MARKER");
  }

  @Test
  void nonDuplicateFanOutFailureKeepsTheIntentForRetry() {
    failNextRecipientInsert.set(true);
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).isEmpty();
    makeRetryDue();
    drain();
    assertThat(received).hasSize(1);
  }

  @Test
  void missingMatrixEventStopsRetryingAfterTheAttemptCap() {
    when(matrix.getRoomEvent(ROOM, EVENT, "matrix-token")).thenReturn(Optional.empty());
    service.onMessageIntent(ROOM, EVENT, caller);
    for (int round = 0; round < 40; round++) {
      drain();
      makeRetryDue();
    }
    assertThat(writer.pendingIds()).isEmpty();
    assertThat(stored.values())
        .extracting(ReplyEmailDelivery::getStatus)
        .containsOnly(ReplyEmailDelivery.Status.REJECTED);
    verify(matrix, atMost(InternalChatEmailService.MAX_PREFLIGHT_ATTEMPTS))
        .getRoomEvent(ROOM, EVENT, "matrix-token");
    assertThat(received).isEmpty();
  }

  @Test
  void briefMatrixOutageStillDeliversOnceTheEventIsReadable() {
    when(matrix.getRoomEvent(ROOM, EVENT, "matrix-token"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.empty())
        .thenAnswer(i -> Optional.of(event));
    service.onMessageIntent(ROOM, EVENT, caller);
    for (int round = 0; round < 4; round++) {
      drain();
      makeRetryDue();
    }
    assertThat(received).hasSize(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "sender",
        "event-id",
        "plaintext",
        "empty",
        "edit",
        "reaction",
        "notice",
        "blank-text"
      })
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
      case "notice" -> {
        event.put("type", "m.room.message");
        event.put("content", Map.of("msgtype", "m.notice", "body", "system notice"));
      }
      case "blank-text" -> {
        event.put("type", "m.room.message");
        event.put("content", Map.of("msgtype", "m.text", "body", " "));
      }
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

  @Test
  void tenantSwitchOffSuppressesInternalGroupMail() {
    tenant.setSettings(new Settings().featureConsultantEmailInternalGroupEnabled(false));
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).isEmpty();
  }

  @Test
  void tenantSwitchOnDeliversInternalGroupMail() {
    tenant.setSettings(new Settings().featureConsultantEmailInternalGroupEnabled(true));
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).hasSize(1);
  }

  @Test
  void tenantSwitchTurnedOffAfterFanOutStopsPendingMail() {
    service.onMessageIntent(ROOM, EVENT, caller);
    writer.pendingIds().forEach(service::resolveIntent);
    tenant.setSettings(new Settings().featureConsultantEmailInternalGroupEnabled(false));
    writer.pendingIds().forEach(service::deliverPending);
    assertThat(received).isEmpty();
  }

  @Test
  void browserSwitchOffDoesNotSuppressInternalGroupMail() {
    tenant.setSettings(new Settings().featureConsultantBrowserInternalGroupEnabled(false));
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    assertThat(received).hasSize(1);
  }

  /** FE#1725 review: a missing Träger fails closed in both checks, also once it is back. */
  @Test
  void missingTenantAtFanOutSendsNoMailEvenAfterItReturns() {
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(null);
    service.onMessageIntent(ROOM, EVENT, caller);
    drain();
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    makeRetryDue();
    drain();
    assertThat(received).isEmpty();
  }

  @Test
  void missingTenantAtDispatchSendsNoMailEvenAfterItReturns() {
    service.onMessageIntent(ROOM, EVENT, caller);
    writer.pendingIds().forEach(service::resolveIntent);
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(null);
    writer.pendingIds().forEach(service::deliverPending);
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    makeRetryDue();
    drain();
    assertThat(received).isEmpty();
  }

  /** Only a replayed intent is swallowed; any other integrity failure reaches the caller. */
  @Test
  void integrityFailureThatIsNoReplayIsNotSwallowed() {
    doThrow(new DataIntegrityViolationException("column cannot be null"))
        .when(repository)
        .saveAndFlush(any());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.onMessageIntent(ROOM, EVENT, caller))
        .isInstanceOf(DataIntegrityViolationException.class);
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
  void suspensionAfterFanOutPreventsPendingDelivery(String userId) {
    service.onMessageIntent(ROOM, EVENT, caller);
    writer.pendingIds().forEach(service::resolveIntent);
    when(identities.findEnabledById(userId)).thenReturn(Optional.of(false));
    drain();
    assertThat(received).isEmpty();
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
