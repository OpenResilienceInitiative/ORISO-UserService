package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.MailServiceApiControllerFactory;
import de.caritas.cob.userservice.api.facade.EmailNotificationFacade;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import de.caritas.cob.userservice.api.service.consultingtype.*;
import de.caritas.cob.userservice.api.service.donotdisturb.DoNotDisturbService;
import de.caritas.cob.userservice.api.service.email.*;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.sender.*;
import de.caritas.cob.userservice.api.service.emailsupplier.*;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import de.caritas.cob.userservice.api.workflow.accountinactivity.*;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;

/** Explicit TLS receiver contract; run scripts/ci/run-notification-smtp-receipts.sh. */
@DataJpaTest(showSql = false)
@TestPropertySource(
    properties = {
      "spring.profiles.active=testing,notification-email-smtp-fixture",
      "multitenancy.enabled=true",
      "feature.multitenancy.with.single.domain.enabled=false",
      "app.base.url=https://app.synthetic.oriso.test",
      "email.branding.name=Synthetic ORISO",
      "email.branding.logo-url=",
      "email.branding.cache-ttl-seconds=0"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  NotificationEmailSmtpContract.AccountFixture.class,
  ReplyEmailDeliveryWriter.class,
  InternalChatEmailService.class,
  AdviceSeekerReplyEmailService.class,
  NotificationAccountEligibility.class,
  MatrixCaseReplyActorAuthorizer.class,
  MembersOnlyChatAccess.class,
  DoNotDisturbService.class,
  NewEnquiryEmailSupplier.class,
  NewDirectEnquiryEmailSupplier.class,
  TenantTemplateSupplier.class,
  NotificationRequestFacts.class,
  MailService.class,
  NotificationMailSender.class,
  NotificationMailComposer.class,
  TenantEmailBrandValues.class,
  SenderOrganisationResolver.class,
  EmailBrandingResolver.class,
  OrisoEmailBrand.class,
  OrisoEmailRenderer.class,
  TenantSystemEmailRouteService.class,
  TenantSystemEmailDelivery.class,
  PlatformSmtpSettingsProvider.class,
  OrisoEmailDispatcher.class
})
class NotificationEmailSmtpContract {
  @TestConfiguration(proxyBeanMethods = false)
  @Profile("notification-email-smtp-fixture")
  static class AccountFixture {
    @Bean
    AccountInactivityService accountLifecycle(
        javax.sql.DataSource ds, PlatformTransactionManager tx) {
      return new AccountInactivityService(
          new JdbcTemplate(ds), tx, Clock.systemUTC(), mock(AccountInactivityEffects.class));
    }
  }

  @Autowired ApplicationContext context;
  @Autowired javax.sql.DataSource dataSource;
  @Autowired ReplyEmailDeliveryRepository deliveries;
  @Autowired ReplyEmailDeliveryWriter writer;
  @Autowired InternalChatEmailService internal;
  @Autowired AdviceSeekerReplyEmailService replies;
  @Autowired AccountInactivityService lifecycle;
  @Autowired MailService mail;
  @Autowired OrisoEmailBrand brand;
  @Autowired TenantTemplateSupplier templates;
  @Autowired NotificationRequestFacts requestFacts;
  @MockitoBean SessionRepository sessions;
  @MockitoBean GroupChatParticipantRepository participants;
  @MockitoBean ConsultantRepository consultants;
  @MockitoBean ConsultantAgencyRepository agencies;
  @MockitoBean CaseHandoverRequestRepository handovers;
  @MockitoBean SessionSupervisorRepository supervisors;
  @MockitoBean ChatRepository chats;
  @MockitoBean UserDoNotDisturbRepository dnd;
  @MockitoBean IdentityAccountStatusLookup identities;
  @MockitoBean MatrixSynapseService matrix;
  @MockitoBean TenantService tenants;
  @MockitoBean ApplicationSettingsService settings;
  @MockitoBean TenantSystemEmailClient tenantMail;
  @MockitoBean AgencyService agencyService;
  @MockitoBean ReleaseToggleService toggles;
  @MockitoBean TopicService topics;
  @MockitoBean PlatformOperatorOrganisationClient platformOrganisation;
  @MockitoBean TraegerOrganisationClient tenantOrganisation;
  @MockitoBean SecurityHeaderSupplier securityHeaders;
  @MockitoBean MailServiceApiControllerFactory legacyMail;

  private Consultant actor;
  private Consultant recipient;
  private User asker;
  private Session consultation;
  private Session group;
  private List<String> groupMembers;
  private String event;
  private JdbcTemplate jdbc;
  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void prepareCurrentDomainAndRealDeliveryStorage() {
    assertThat(System.getenv("ORISO_SMTP_PORT"))
        .as("Run the dedicated SMTP contract script")
        .isNotBlank();
    jdbc = new JdbcTemplate(dataSource);
    // Hibernate create-drop does not install Liquibase 0103's production dedup key.
    jdbc.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS uk_reply_email_kind_recipient_event ON reply_email_delivery(recipient_kind,recipient_user_id,event_key)");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS account_inactivity(identity_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,assigned_months INT NOT NULL,revision BIGINT NOT NULL,last_activity TIMESTAMP(6) NOT NULL,due_at TIMESTAMP(6) NOT NULL,status VARCHAR(20) NOT NULL,last_error VARCHAR(1000),attempts INT DEFAULT 0 NOT NULL)");
    String suffix = UUID.randomUUID().toString().substring(0, 12);
    actor = consultant("mail-a-" + suffix);
    recipient = consultant("mail-c-" + suffix);
    asker =
        User.builder()
            .userId("mail-u-" + suffix)
            .username("synthetic-asker")
            .matrixUserId("@mail-u-" + suffix + ":synthetic.oriso.test")
            .tenantId(7L)
            .email("mail-u-" + suffix + "@synthetic.oriso.test")
            .notificationsEnabled(true)
            .notificationsSettings("{\"newChatMessageNotificationEnabled\":true}")
            .languageCode(LanguageCode.de)
            .languageFormal(true)
            .build();
    consultation =
        Session.builder()
            .id(4201L)
            .tenantId(7L)
            .agencyId(70L)
            .user(asker)
            .consultant(actor)
            .matrixRoomId("!consultation-" + suffix + ":synthetic.oriso.test")
            .conversationType(ConversationType.AGENCY_COUNSELLING)
            .status(Session.SessionStatus.IN_PROGRESS)
            .registrationType(Session.RegistrationType.REGISTERED)
            .postcode("12345")
            .build();
    group =
        Session.builder()
            .id(4202L)
            .tenantId(7L)
            .agencyId(70L)
            .matrixRoomId("!internal-" + suffix + ":synthetic.oriso.test")
            .conversationType(ConversationType.INTERNAL_GROUP)
            .status(Session.SessionStatus.IN_PROGRESS)
            .registrationType(Session.RegistrationType.REGISTERED)
            .postcode("PRIVATE_CASE_MARKER")
            .build();
    event = "$smtp-" + suffix;
    groupMembers = new ArrayList<>(List.of(actor.getMatrixUserId(), recipient.getMatrixUserId()));
    when(sessions.findByMatrixRoomId(consultation.getMatrixRoomId()))
        .thenReturn(Optional.of(consultation));
    when(sessions.findById(4201L)).thenReturn(Optional.of(consultation));
    when(sessions.findByMatrixRoomId(group.getMatrixRoomId())).thenReturn(Optional.of(group));
    when(sessions.findById(4202L)).thenReturn(Optional.of(group));
    for (var consultant : List.of(actor, recipient)) {
      when(consultants.findByIdAndDeleteDateIsNull(consultant.getId()))
          .thenReturn(Optional.of(consultant));
      when(consultants.findByMatrixUserIdAndDeleteDateIsNull(consultant.getMatrixUserId()))
          .thenReturn(Optional.of(consultant));
    }
    when(identities.findEnabledById(anyString())).thenReturn(Optional.of(true));
    when(participants.findByChatId(4202L))
        .thenReturn(
            List.of(
                new GroupChatParticipant(4202L, actor.getId()),
                new GroupChatParticipant(4202L, recipient.getId())));
    when(matrix.getRoomMembers(group.getMatrixRoomId()))
        .thenAnswer(ignored -> Optional.of(List.copyOf(groupMembers)));
    when(matrix.getRoomMembers(consultation.getMatrixRoomId()))
        .thenReturn(Optional.of(List.of(actor.getMatrixUserId(), asker.getMatrixUserId())));
    when(matrix.loginAsUserAccessToken(actor.getMatrixUserId()))
        .thenReturn("synthetic-matrix-token");
    when(matrix.findRoomEvent(eq(group.getMatrixRoomId()), anyString(), anyString()))
        .thenAnswer(
            call ->
                Optional.of(
                    Map.of(
                        "event_id",
                        call.getArgument(1, String.class),
                        "sender",
                        actor.getMatrixUserId(),
                        "type",
                        "m.room.encrypted",
                        "content",
                        Map.of("ciphertext", "PRIVATE_MESSAGE_MARKER"))));
    var tenant =
        new RestrictedTenantDTO()
            .id(7L)
            .subdomain("fixture")
            .name("PRIVATE_CENTRE_MARKER")
            .settings(new Settings().featureConsultantEmailInternalGroupEnabled(true));
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenants.getRestrictedTenantData(7L)).thenReturn(tenant);
    when(tenants.getRestrictedTenantData("fixture")).thenReturn(tenant);
    when(tenantMail.readMailContext(7L))
        .thenReturn(new TenantSystemEmailClient.MailContext(true, "PLATFORM", null, false, false));
    configureSmtp();
    when(toggles.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS)).thenReturn(true);
    var relation =
        ConsultantAgency.builder()
            .consultant(recipient)
            .agencyId(70L)
            .tenantId(7L)
            .status(ConsultantAgencyStatus.CREATED)
            .build();
    when(agencies.findByAgencyIdAndDeleteDateIsNull(70L)).thenReturn(List.of(relation));
    when(agencies.findByConsultantIdAndAgencyIdAndDeleteDateIsNull(recipient.getId(), 70L))
        .thenReturn(List.of(relation));
    when(agencyService.getAgency(70L))
        .thenReturn(
            new de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO()
                .id(70L)
                .name("PRIVATE_CENTRE_MARKER"));
    when(topics.getTopicById(9L))
        .thenReturn(
            new de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO()
                .name("Synthetic topic"));
    TenantContext.setCurrentTenant(7L);
  }

  @AfterEach
  void removeOnlyOwnedRows() {
    if (actor != null) {
      deliveries.deleteAllByIdInBatch(ownedRows().stream().map(ReplyEmailDelivery::getId).toList());
      jdbc.update(
          "DELETE FROM account_inactivity WHERE identity_id IN (?,?,?)",
          actor.getId(),
          recipient.getId(),
          asker.getUserId());
    }
    TenantContext.clear();
  }

  @Test
  void encryptedInternalMessageArrivesOnceWithRealClaimAndCorrelation() throws Exception {
    var before = receiptIds();
    internal.onMatrixMessage(group.getMatrixRoomId(), event, actor.getMatrixUserId());
    drain();
    var claim =
        ownedRows().stream()
            .filter(row -> row.getRecipientKind() == ReplyEmailDelivery.RecipientKind.INTERNAL)
            .findFirst()
            .orElseThrow();
    assertReceipt(
        before,
        recipient.getEmail(),
        claim.getCorrelationId(),
        "/sessions/consultant/sessionView/");
    assertThat(deliveries.findById(claim.getId()).orElseThrow().getStatus())
        .isEqualTo(ReplyEmailDelivery.Status.SENT);
    var delivered = receiptIds();
    internal.onMatrixMessage(group.getMatrixRoomId(), event, actor.getMatrixUserId());
    drain();
    assertNoNewReceipt(delivered);
    assertThat(ownedRows()).hasSize(2);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void repliesInBothDirectionsArriveOnceAndReplayCreatesNoClaim(boolean toConsultant)
      throws Exception {
    var before = receiptIds();
    reserveReply(toConsultant);
    var claim = ownedRows().getFirst();
    drain();
    assertReceipt(
        before,
        toConsultant ? actor.getEmail() : asker.getEmail(),
        claim.getCorrelationId(),
        toConsultant ? "/sessions/consultant/sessionView/" : "/sessions/user/view/session/4201");
    var delivered = receiptIds();
    reserveReply(toConsultant);
    drain();
    assertNoNewReceipt(delivered);
    assertThat(ownedRows())
        .hasSize(1)
        .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReplyEmailDelivery.Status.SENT));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void normalAndDirectEnquiryUseTheRealProducerFactsAndSmtp(boolean direct) throws Exception {
    var before = receiptIds();
    sendEnquiry(direct);
    var message =
        assertReceipt(before, recipient.getEmail(), null, "/sessions/consultant/sessionPreview");
    var text = ((MimeMultipart) message.getContent()).getBodyPart(0).getContent().toString();
    assertThat(text).contains("Synthetic topic", "21.10.2026 09:00 UTC");
    assertThat(ownedRows()).isEmpty(); // Initial enquiries have no durable reply claim contract.
    verifyNoInteractions(legacyMail);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void enquiryOptOutAndDisabledIdentitySuppressActualReceipt(boolean direct) throws Exception {
    var before = receiptIds();
    sendEnquiry(direct);
    assertReceipt(before, recipient.getEmail(), null, "/sessions/consultant/sessionPreview");
    var delivered = receiptIds();
    recipient.setNotificationsEnabled(false);
    sendEnquiry(direct);
    assertNoNewReceipt(delivered);
    recipient.setNotificationsEnabled(true);
    when(identities.findEnabledById(recipient.getId())).thenReturn(Optional.of(false));
    sendEnquiry(direct);
    assertNoNewReceipt(delivered);
    verifyNoInteractions(legacyMail);
  }

  @Test
  void missingSmtpSetupRetainsTheSameReplyUntilRealReceiptAfterRepair() throws Exception {
    var before = receiptIds();
    reserveReply(false);
    var claim = ownedRows().getFirst();
    when(settings.getGlobalSmtpSettingsSnapshot()).thenReturn(Optional.empty());
    drain();
    assertNoNewReceipt(before);
    assertThat(deliveries.findById(claim.getId()).orElseThrow().getStatus())
        .isEqualTo(ReplyEmailDelivery.Status.PENDING);
    configureSmtp();
    jdbc.update(
        "UPDATE reply_email_delivery SET next_attempt_at=? WHERE id=?",
        LocalDateTime.now().minusSeconds(1),
        claim.getId());
    drain();
    assertReceipt(
        before, asker.getEmail(), claim.getCorrelationId(), "/sessions/user/view/session/4201");
    assertThat(deliveries.findById(claim.getId()).orElseThrow().getStatus())
        .isEqualTo(ReplyEmailDelivery.Status.SENT);
    assertThat(ownedRows()).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void pendingReplyIsSuppressedIfEitherAccountStartsSuspending(boolean sender) throws Exception {
    var before = receiptIds();
    reserveReply(false);
    String identity = sender ? actor.getId() : asker.getUserId();
    lifecycle.assignAtCreation(identity, 7L, 12, 0L, Instant.now());
    jdbc.update("UPDATE account_inactivity SET status='SUSPENDING' WHERE identity_id=?", identity);
    drain();
    assertNoNewReceipt(before);
    assertThat(ownedRows())
        .singleElement()
        .satisfies(
            row -> assertThat(row.getStatus()).isEqualTo(ReplyEmailDelivery.Status.REJECTED));
  }

  @Test
  void removingAnInternalRecipientBeforeDispatchPreventsReceipt() throws Exception {
    var before = receiptIds();
    internal.onMatrixMessage(group.getMatrixRoomId(), event, actor.getMatrixUserId());
    ownedRows().stream()
        .filter(row -> row.getRecipientKind() == ReplyEmailDelivery.RecipientKind.INTERNAL_INTENT)
        .forEach(row -> internal.resolveIntent(row.getId()));
    groupMembers.remove(recipient.getMatrixUserId());
    drain();
    assertNoNewReceipt(before);
    assertThat(ownedRows())
        .filteredOn(row -> row.getRecipientKind() == ReplyEmailDelivery.RecipientKind.INTERNAL)
        .singleElement()
        .satisfies(
            row -> assertThat(row.getStatus()).isEqualTo(ReplyEmailDelivery.Status.REJECTED));
  }

  private void configureSmtp() {
    when(settings.getGlobalSmtpSettingsSnapshot())
        .thenReturn(
            Optional.of(
                PlatformSmtpSettingsFixture.credentials("synthetic-fixture", "synthetic-fixture")
                    .globalSmtpHost("localhost")
                    .globalSmtpPort(System.getenv("ORISO_SMTP_PORT"))
                    .globalSmtpFrom("notification@synthetic.oriso.test")));
  }

  private Consultant consultant(String id) {
    return Consultant.builder()
        .id(id)
        .username(id)
        .firstName("PRIVATE_COUNSELLOR_MARKER")
        .lastName("Fixture")
        .matrixUserId("@" + id + ":synthetic.oriso.test")
        .tenantId(7L)
        .email(id + "@synthetic.oriso.test")
        .status(ConsultantStatus.CREATED)
        .notificationsEnabled(true)
        .notificationsSettings(
            "{\"initialEnquiryNotificationEnabled\":true,\"newChatMessageNotificationEnabled\":true,\"internalChatNotificationEnabled\":true}")
        .notifyNewChatMessageFromAdviceSeeker(true)
        .languageCode(LanguageCode.de)
        .languageFormal(true)
        .build();
  }

  private void reserveReply(boolean toConsultant) {
    if (toConsultant)
      replies.onAdviceSeekerMessage(consultation.getMatrixRoomId(), event, asker.getMatrixUserId());
    else replies.onConsultantReply(consultation.getMatrixRoomId(), event, actor.getMatrixUserId());
  }

  private void sendEnquiry(boolean direct) {
    var request =
        Session.builder()
            .id(4301L)
            .tenantId(7L)
            .agencyId(70L)
            .user(asker)
            .consultant(direct ? recipient : null)
            .status(Session.SessionStatus.NEW)
            .registrationType(Session.RegistrationType.REGISTERED)
            .mainTopicId(9L)
            .postcode("12345")
            .enquiryMessageDate(LocalDateTime.of(2026, 10, 21, 9, 0))
            .build();
    var facade =
        new EmailNotificationFacade(
            mail,
            brand,
            mock(SessionService.class),
            mock(ConsultantService.class),
            mock(IdentityClientConfig.class),
            context.getBeanProvider(NewEnquiryEmailSupplier.class),
            context.getBeanProvider(NewDirectEnquiryEmailSupplier.class),
            context.getBeanProvider(AssignEnquiryEmailSupplier.class),
            templates,
            requestFacts,
            toggles,
            tenants);
    if (direct)
      facade.sendNewDirectEnquiryEmailNotification(request, new TenantData(7L, "fixture"));
    else facade.sendNewEnquiryEmailNotification(request, new TenantData(7L, "fixture"));
  }

  private List<ReplyEmailDelivery> ownedRows() {
    return deliveries.findAll().stream()
        .filter(
            row ->
                Set.of(actor.getId(), recipient.getId(), asker.getUserId())
                    .contains(row.getRecipientUserId()))
        .toList();
  }

  private void drain() {
    for (int pass = 0; pass < 4; pass++) {
      var owned =
          ownedRows().stream()
              .map(ReplyEmailDelivery::getId)
              .collect(java.util.stream.Collectors.toSet());
      var due = writer.pendingIds().stream().filter(owned::contains).toList();
      if (due.isEmpty()) return;
      for (long id : due) {
        switch (writer.kindOf(id).orElseThrow()) {
          case INTERNAL_INTENT -> internal.resolveIntent(id);
          case INTERNAL -> internal.deliverPending(id);
          case ASKER, CONSULTANT -> replies.deliverPending(id);
          default -> throw new AssertionError("Unexpected owned claim kind");
        }
      }
    }
    throw new AssertionError("Owned claim drain did not settle");
  }

  private Set<String> receiptIds() throws Exception {
    var body =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(System.getenv("ORISO_MAILPIT_API") + "/api/v1/messages"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(body.statusCode()).isEqualTo(200);
    var ids = new HashSet<String>();
    json.readTree(body.body())
        .path("messages")
        .forEach(message -> ids.add(message.path("ID").asText()));
    return ids;
  }

  private MimeMessage assertReceipt(
      Set<String> baseline, String address, String correlation, String path) throws Exception {
    var captured = new java.util.concurrent.atomic.AtomicReference<MimeMessage>();
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var fresh = receiptIds();
              fresh.removeAll(baseline);
              assertThat(fresh).hasSize(1);
              var raw =
                  http.send(
                      HttpRequest.newBuilder(
                              URI.create(
                                  System.getenv("ORISO_MAILPIT_API")
                                      + "/api/v1/message/"
                                      + fresh.iterator().next()
                                      + "/raw"))
                          .GET()
                          .build(),
                      HttpResponse.BodyHandlers.ofByteArray());
              assertThat(raw.statusCode()).isEqualTo(200);
              var message =
                  new MimeMessage(
                      jakarta.mail.Session.getInstance(new Properties()),
                      new ByteArrayInputStream(raw.body()));
              assertThat(message.getAllRecipients())
                  .singleElement()
                  .satisfies(
                      to -> assertThat(((InternetAddress) to).getAddress()).isEqualTo(address));
              assertThat(message.isMimeType("multipart/alternative")).isTrue();
              var multipart = (MimeMultipart) message.getContent();
              assertThat(multipart.getCount()).isEqualTo(2);
              assertThat(multipart.getBodyPart(0).isMimeType("text/plain")).isTrue();
              assertThat(multipart.getBodyPart(1).isMimeType("text/html")).isTrue();
              for (int i = 0; i < 2; i++)
                assertThat(multipart.getBodyPart(i).getContent().toString())
                    .contains("https://fixture.app.synthetic.oriso.test" + path)
                    .doesNotContain("PRIVATE_MESSAGE_MARKER", "{{");
              if (correlation != null)
                assertThat(message.getHeader("X-ORISO-Delivery-ID", null)).isEqualTo(correlation);
              captured.set(message);
            });
    return captured.get();
  }

  private void assertNoNewReceipt(Set<String> baseline) {
    await()
        .during(Duration.ofMillis(200))
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(receiptIds()).isEqualTo(baseline));
  }
}
