package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.model.Session.RegistrationType.REGISTERED;
import static de.caritas.cob.userservice.api.testHelper.AsyncVerification.verifyAsync;
import static de.caritas.cob.userservice.api.testHelper.FieldConstants.FIELD_VALUE_EMAIL_DUMMY_SUFFIX;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.AGENCY_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.APPLICATION_BASE_URL;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.APPLICATION_BASE_URL_FIELD_NAME;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT_ID_2;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_SUCHT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.IS_NO_TEAM_SESSION;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.IS_TEAM_SESSION;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.MATRIX_ROOM_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.NAME;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USERNAME_CONSULTANT_ENCODED;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USERNAME_ENCODED;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_ID;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.google.api.client.util.Lists;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.web.dto.NotificationsSettingsDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ReassignmentNotificationDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.EmailNotificationException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.json.JsonSerializationUtils;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggle;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.api.service.email.NotificationRequestFacts;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationFixture;
import de.caritas.cob.userservice.api.service.emailsupplier.AssignEnquiryEmailSupplier;
import de.caritas.cob.userservice.api.service.emailsupplier.NewDirectEnquiryEmailSupplier;
import de.caritas.cob.userservice.api.service.emailsupplier.NewEnquiryEmailSupplier;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.GroupChatDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.NewMessageDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.NotificationsDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.TeamSessionsDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.WelcomeMessageDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailsDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailNotificationFacadeTest {

  private final Consultant CONSULTANT =
      Consultant.builder()
          .id(CONSULTANT_ID)
          .matrixUserId("XXX")
          .username(USERNAME_CONSULTANT_ENCODED)
          .firstName("consultant")
          .lastName("consultant")
          .email("consultant@domain.de")
          .absent(false)
          .teamConsultant(false)
          .languageFormal(false)
          .tenantId(1L)
          .encourage2fa(true)
          .magicLinkLoginEnabled(true)
          .notifyEnquiriesRepeating(true)
          .notifyNewChatMessageFromAdviceSeeker(true)
          .status(ConsultantStatus.CREATED)
          .walkThroughEnabled(false)
          .languageCode(LanguageCode.de)
          .notificationsEnabled(false)
          .build();
  private final Consultant CONSULTANT_WITHOUT_MAIL =
      Consultant.builder()
          .id(CONSULTANT_ID)
          .matrixUserId("XXX")
          .username("consultant")
          .firstName("consultant")
          .lastName("consultant")
          .email("")
          .absent(false)
          .teamConsultant(false)
          .languageFormal(false)
          .tenantId(1L)
          .encourage2fa(true)
          .magicLinkLoginEnabled(true)
          .notifyEnquiriesRepeating(true)
          .notifyNewChatMessageFromAdviceSeeker(true)
          .status(ConsultantStatus.CREATED)
          .walkThroughEnabled(false)
          .languageCode(LanguageCode.de)
          .notificationsEnabled(false)
          .build();
  private final Consultant CONSULTANT2 =
      Consultant.builder()
          .id(CONSULTANT_ID_2)
          .matrixUserId("XXX")
          .username("consultant2")
          .firstName("consultant2")
          .lastName("consultant2")
          .email("consultant2@domain.de")
          .absent(false)
          .teamConsultant(false)
          .languageFormal(false)
          .tenantId(1L)
          .encourage2fa(true)
          .magicLinkLoginEnabled(true)
          .notifyEnquiriesRepeating(true)
          .notifyNewChatMessageFromAdviceSeeker(true)
          .status(ConsultantStatus.CREATED)
          .walkThroughEnabled(false)
          .languageCode(LanguageCode.de)
          .notificationsEnabled(false)
          .build();
  private final Consultant CONSULTANT_NO_EMAIL =
      Consultant.builder()
          .id(CONSULTANT_ID)
          .matrixUserId("XXX")
          .username("consultant")
          .firstName("consultant")
          .lastName("consultant")
          .email("")
          .absent(false)
          .teamConsultant(false)
          .languageFormal(false)
          .tenantId(1L)
          .encourage2fa(true)
          .magicLinkLoginEnabled(true)
          .notifyEnquiriesRepeating(true)
          .notifyNewChatMessageFromAdviceSeeker(true)
          .status(ConsultantStatus.CREATED)
          .walkThroughEnabled(false)
          .languageCode(LanguageCode.de)
          .notificationsEnabled(false)
          .build();
  // ADR-002 §2 / #1201: the counsellor's real name must never reach the advice seeker's mailbox.
  // These fixtures carry a real name that is distinctive enough for an absence assertion to mean
  // something, and differ only in which public name is available.
  private static final String REAL_FIRST_NAME = "Angela";
  private static final String REAL_LAST_NAME = "Musterfrau";

  private final Consultant CONSULTANT_WITH_PSEUDONYM =
      Consultant.builder()
          .id(CONSULTANT_ID)
          .username("beraterin1")
          .firstName(REAL_FIRST_NAME)
          .lastName(REAL_LAST_NAME)
          .displayName("Frau M.")
          .email("consultant@domain.de")
          .languageCode(LanguageCode.de)
          .build();

  private final Consultant CONSULTANT_WITHOUT_PSEUDONYM =
      Consultant.builder()
          .id(CONSULTANT_ID)
          .username("beraterin1")
          .firstName(REAL_FIRST_NAME)
          .lastName(REAL_LAST_NAME)
          .displayName(null)
          .email("consultant@domain.de")
          .languageCode(LanguageCode.de)
          .build();

  private final Consultant CONSULTANT_WITHOUT_ANY_PUBLIC_NAME =
      Consultant.builder()
          .id(CONSULTANT_ID)
          .username("")
          .firstName(REAL_FIRST_NAME)
          .lastName(REAL_LAST_NAME)
          .displayName(null)
          .email("consultant@domain.de")
          .languageCode(LanguageCode.de)
          .build();

  private final User USER = new User(USER_ID, null, USERNAME_ENCODED, "email@email.de", false);
  private final User USER_NO_EMAIL = new User(USER_ID, null, "username", "", false);
  private final ConsultantAgency CONSULTANT_AGENCY =
      new ConsultantAgency(
          1L, CONSULTANT, AGENCY_ID, nowInUtc(), nowInUtc(), nowInUtc(), null, null);
  private final ConsultantAgency CONSULTANT_AGENCY_2 =
      new ConsultantAgency(
          1L, CONSULTANT2, AGENCY_ID, nowInUtc(), nowInUtc(), nowInUtc(), null, null);
  private final Session SESSION =
      Session.builder()
          .id(1L)
          .user(USER)
          .consultant(CONSULTANT)
          .consultingTypeId(CONSULTING_TYPE_ID_SUCHT)
          .registrationType(REGISTERED)
          .postcode("88045")
          .agencyId(AGENCY_ID)
          .status(SessionStatus.INITIAL)
          .enquiryMessageDate(nowInUtc())
          .matrixRoomId(MATRIX_ROOM_ID)
          .teamSession(IS_NO_TEAM_SESSION)
          .createDate(nowInUtc())
          .build();

  private final Session SESSION_IN_PROGRESS =
      Session.builder()
          .id(1L)
          .user(USER)
          .consultant(CONSULTANT)
          .consultingTypeId(CONSULTING_TYPE_ID_SUCHT)
          .registrationType(REGISTERED)
          .postcode("88045")
          .agencyId(AGENCY_ID)
          .status(SessionStatus.IN_PROGRESS)
          .enquiryMessageDate(nowInUtc())
          .matrixRoomId(MATRIX_ROOM_ID)
          .teamSession(IS_NO_TEAM_SESSION)
          .createDate(nowInUtc())
          .build();

  private final Session SESSION_IN_PROGRESS_NO_EMAIL =
      Session.builder()
          .id(1L)
          .user(USER_NO_EMAIL)
          .consultant(CONSULTANT_NO_EMAIL)
          .consultingTypeId(CONSULTING_TYPE_ID_SUCHT)
          .registrationType(REGISTERED)
          .postcode("88045")
          .agencyId(AGENCY_ID)
          .status(SessionStatus.IN_PROGRESS)
          .enquiryMessageDate(nowInUtc())
          .matrixRoomId(MATRIX_ROOM_ID)
          .teamSession(IS_NO_TEAM_SESSION)
          .createDate(nowInUtc())
          .build();

  private final Session TEAM_SESSION =
      Session.builder()
          .id(1L)
          .user(USER)
          .consultant(CONSULTANT)
          .consultingTypeId(CONSULTING_TYPE_ID_SUCHT)
          .registrationType(REGISTERED)
          .postcode("88045")
          .agencyId(AGENCY_ID)
          .status(SessionStatus.IN_PROGRESS)
          .enquiryMessageDate(nowInUtc())
          .matrixRoomId(MATRIX_ROOM_ID)
          .teamSession(IS_TEAM_SESSION)
          .createDate(nowInUtc())
          .build();

  private final String USER_ROLE = UserRole.USER.getValue();
  private final Set<String> USER_ROLES = new HashSet<>(Collections.singletonList(USER_ROLE));
  private final String CONSULTANT_ROLE = UserRole.CONSULTANT.getValue();
  private final Set<String> CONSULTANT_ROLES =
      new HashSet<>(Collections.singletonList(CONSULTANT_ROLE));
  private final String ERROR_MSG = "error";
  private final List<ConsultantAgency> CONSULTANT_LIST =
      Arrays.asList(CONSULTANT_AGENCY, CONSULTANT_AGENCY_2);
  private final NotificationsDTO NOTIFICATIONS_DTO_TO_ALL_TEAM_CONSULTANTS =
      new NotificationsDTO()
          .teamSessions(
              new TeamSessionsDTO().newMessage(new NewMessageDTO().allTeamConsultants(true)));
  private final NotificationsDTO NOTIFICATIONS_DTO_TO_ASSIGNED_CONSULTANT_ONLY =
      new NotificationsDTO()
          .teamSessions(
              new TeamSessionsDTO().newMessage(new NewMessageDTO().allTeamConsultants(false)));
  private final ExtendedConsultingTypeResponseDTO
      CONSULTING_TYPE_SETTINGS_NOTIFICATION_TO_ALL_TEAM_CONSULTANTS =
          new ExtendedConsultingTypeResponseDTO()
              .id(0)
              .slug("suchtberatung")
              .groupChat(new GroupChatDTO().isGroupChat(false))
              .consultantBoundedToConsultingType(false)
              .welcomeMessage(
                  new WelcomeMessageDTO().sendWelcomeMessage(false).welcomeMessageText(null))
              .sendFurtherStepsMessage(false)
              .sessionDataInitializing(null)
              .notifications(NOTIFICATIONS_DTO_TO_ALL_TEAM_CONSULTANTS)
              .languageFormal(false)
              .roles(null)
              .registration(null);
  private final ExtendedConsultingTypeResponseDTO
      CONSULTING_TYPE_SETTINGS_NOTIFICATION_TO_ASSIGNED_CONSULTANT_ONLY =
          new ExtendedConsultingTypeResponseDTO()
              .id(0)
              .slug("suchtberatung")
              .groupChat(new GroupChatDTO().isGroupChat(false))
              .consultantBoundedToConsultingType(false)
              .welcomeMessage(
                  new WelcomeMessageDTO().sendWelcomeMessage(false).welcomeMessageText(null))
              .sendFurtherStepsMessage(false)
              .sessionDataInitializing(null)
              .notifications(NOTIFICATIONS_DTO_TO_ASSIGNED_CONSULTANT_ONLY)
              .languageFormal(false)
              .roles(null)
              .registration(null);

  private EmailNotificationFacade emailNotificationFacade;

  @Mock private NewEnquiryEmailSupplier newEnquiryEmailSupplier;
  @Mock private ObjectProvider<NewEnquiryEmailSupplier> newEnquiryEmailSupplierProvider;

  @SuppressWarnings("unused")
  @Mock
  private NewDirectEnquiryEmailSupplier newDirectEnquiryEmailSupplier;

  @Mock private ObjectProvider<NewDirectEnquiryEmailSupplier> newDirectEnquiryEmailSupplierProvider;

  @Spy private AssignEnquiryEmailSupplier assignEnquiryEmailSupplier;
  @Mock private ObjectProvider<AssignEnquiryEmailSupplier> assignEnquiryEmailSupplierProvider;

  @Mock private MailService mailService;
  @Mock private NotificationRequestFacts notificationRequestFacts;

  @Spy
  private OrisoEmailBrand emailBrand =
      new OrisoEmailBrand(SenderOrganisationFixture.platformOwner());

  @Mock SessionService sessionService;
  @Mock ConsultantService consultantService;
  @Mock IdentityClientConfig identityClientConfig;
  @Mock ReleaseToggleService releaseToggleService;
  @Mock TenantTemplateSupplier tenantTemplateSupplier;

  @Mock
  @SuppressWarnings("unused")
  KeycloakService keycloakService;

  private LogbackCaptor facadeLogCaptor;
  private LogbackCaptor assignEnquiryLogCaptor;

  @BeforeEach
  void setup() throws SecurityException {
    USER.setTenantId(1L);
    emailNotificationFacade =
        new EmailNotificationFacade(
            mailService,
            emailBrand,
            sessionService,
            consultantService,
            identityClientConfig,
            newEnquiryEmailSupplierProvider,
            newDirectEnquiryEmailSupplierProvider,
            assignEnquiryEmailSupplierProvider,
            tenantTemplateSupplier,
            notificationRequestFacts,
            releaseToggleService);
    when(newEnquiryEmailSupplierProvider.getObject()).thenReturn(newEnquiryEmailSupplier);
    when(newDirectEnquiryEmailSupplierProvider.getObject())
        .thenReturn(newDirectEnquiryEmailSupplier);
    when(assignEnquiryEmailSupplierProvider.getObject()).thenReturn(assignEnquiryEmailSupplier);
    when(identityClientConfig.getEmailDummySuffix()).thenReturn(FIELD_VALUE_EMAIL_DUMMY_SUFFIX);
    when(notificationRequestFacts.forSession(any())).thenReturn(List.of());
    ReflectionTestUtils.setField(
        emailNotificationFacade, APPLICATION_BASE_URL_FIELD_NAME, APPLICATION_BASE_URL);
    ReflectionTestUtils.setField(emailBrand, "platformName", "Beispielplattform");
    ReflectionTestUtils.setField(
        assignEnquiryEmailSupplier, "consultantService", consultantService);
    facadeLogCaptor = LogbackCaptor.forClass(EmailNotificationFacade.class);
    assignEnquiryLogCaptor = LogbackCaptor.forClass(AssignEnquiryEmailSupplier.class);
    when(releaseToggleService.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS))
        .thenReturn(false);
  }

  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    facadeLogCaptor.detach();
    assignEnquiryLogCaptor.detach();
  }

  @Test
  void
      sendNewEnquiryEmailNotification_Should_SendEmailNotificationViaMailServiceHelperToConsultants() {
    givenNewEnquiryMailSupplierReturnNonEmptyMails();
    var session = givenEnquirySession();

    emailNotificationFacade.sendNewEnquiryEmailNotification(session, null);

    verify(mailService).sendEmailNotification(Mockito.any(MailsDTO.class));
  }

  @Test
  void sendNewEnquiryEmailNotification_ShouldNot_SendEmailNotificationViaMailServiceHelperToUser() {
    givenNewEnquiryMailSupplierReturnNonEmptyMails();
    var session = givenEnquirySession();

    emailNotificationFacade.sendNewEnquiryEmailNotification(session, null);

    verify(mailService).sendEmailNotification(Mockito.any(MailsDTO.class));
  }

  @Test
  void sendNewEnquiryEmailNotification_Should_SetCurrentTenantContextFromSession() {
    assertThat(TenantContext.getCurrentTenant()).isNull();
    givenNewEnquiryMailSupplierReturnNonEmptyMails();
    var session = givenEnquirySession();

    session.setTenantId(1L);
    emailNotificationFacade.sendNewEnquiryEmailNotification(session, null);

    verify(mailService).sendEmailNotification(Mockito.any(MailsDTO.class));
  }

  private Session givenEnquirySession() {
    var session = new EasyRandom().nextObject(Session.class);
    session.setConsultant(null);
    return session;
  }

  private void givenNewEnquiryMailSupplierReturnNonEmptyMails() {
    List<MailDTO> mails = getMailDTOS();
    when(newEnquiryEmailSupplier.generateEmails()).thenReturn(mails);
  }

  @Test
  void concurrentNewEnquiriesKeepSupplierStateFactsAndTenantSeparate() throws Exception {
    var first = givenEnquirySession();
    first.setId(101L);
    var second = givenEnquirySession();
    second.setId(202L);
    var firstSupplier = Mockito.mock(NewEnquiryEmailSupplier.class);
    var secondSupplier = Mockito.mock(NewEnquiryEmailSupplier.class);
    var firstSession = new AtomicReference<Session>();
    var secondSession = new AtomicReference<Session>();
    var firstInsideGeneration = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    when(newEnquiryEmailSupplierProvider.getObject()).thenReturn(firstSupplier, secondSupplier);
    Mockito.doAnswer(
            invocation -> {
              firstSession.set(invocation.getArgument(0));
              return null;
            })
        .when(firstSupplier)
        .setCurrentSession(any());
    Mockito.doAnswer(
            invocation -> {
              secondSession.set(invocation.getArgument(0));
              return null;
            })
        .when(secondSupplier)
        .setCurrentSession(any());
    when(firstSupplier.generateEmails())
        .thenAnswer(
            invocation -> {
              firstInsideGeneration.countDown();
              if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("First dispatch was never released");
              }
              return List.of(
                  new MailDTO()
                      .email("first@example.test")
                      .templateData(
                          List.of(
                              new TemplateDataDTO()
                                  .key("sourceSession")
                                  .value(firstSession.get().getId().toString()))));
            });
    when(secondSupplier.generateEmails())
        .thenAnswer(
            invocation ->
                List.of(
                    new MailDTO()
                        .email("second@example.test")
                        .templateData(
                            List.of(
                                new TemplateDataDTO()
                                    .key("sourceSession")
                                    .value(secondSession.get().getId().toString())))));
    when(notificationRequestFacts.forSession(any()))
        .thenAnswer(
            invocation ->
                List.of(
                    new TemplateDataDTO()
                        .key("factSession")
                        .value(((Session) invocation.getArgument(0)).getId().toString())));
    var deliveries = new java.util.concurrent.ConcurrentHashMap<String, String>();
    Mockito.doAnswer(
            invocation -> {
              var mail = ((MailsDTO) invocation.getArgument(0)).getMails().getFirst();
              var details =
                  mail.getTemplateData().stream()
                      .filter(
                          value ->
                              "sourceSession".equals(value.getKey())
                                  || "factSession".equals(value.getKey()))
                      .collect(
                          java.util.stream.Collectors.toMap(
                              TemplateDataDTO::getKey, TemplateDataDTO::getValue));
              deliveries.put(
                  mail.getEmail(),
                  TenantContext.getCurrentTenant()
                      + ":"
                      + details.get("sourceSession")
                      + ":"
                      + details.get("factSession"));
              return null;
            })
        .when(mailService)
        .sendEmailNotification(any());

    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstDispatch =
          executor.submit(
              () ->
                  emailNotificationFacade.sendNewEnquiryEmailNotification(
                      first, new TenantData(11L, "first")));
      try {
        assertThat(firstInsideGeneration.await(5, TimeUnit.SECONDS)).isTrue();
        var secondDispatch =
            executor.submit(
                () ->
                    emailNotificationFacade.sendNewEnquiryEmailNotification(
                        second, new TenantData(22L, "second")));
        secondDispatch.get(5, TimeUnit.SECONDS);
      } finally {
        releaseFirst.countDown();
      }
      firstDispatch.get(5, TimeUnit.SECONDS);
    }

    assertThat(deliveries)
        .containsEntry("first@example.test", "11:101:101")
        .containsEntry("second@example.test", "22:202:202")
        .hasSize(2);
    verify(newEnquiryEmailSupplierProvider, times(2)).getObject();
  }

  private List<MailDTO> getMailDTOS() {
    List<MailDTO> mails = Lists.newArrayList();
    mails.add(new MailDTO());
    return mails;
  }

  @Test
  void sendNewEnquiryEmailNotification_ShouldNot_SendEmailWhenGeneratedEmailListIsEmpty() {
    emailNotificationFacade.sendNewEnquiryEmailNotification(SESSION, null);

    verify(mailService, times(0)).sendEmailNotification(Mockito.any(MailsDTO.class));
  }

  @Test
  void sendNewEnquiryEmailNotification_Should_LogError_WhenSendEmailFails() {
    var session = givenEnquirySession();
    EmailNotificationException emailNotificationException =
        new EmailNotificationException(new Exception());
    when(newEnquiryEmailSupplier.generateEmails()).thenThrow(emailNotificationException);

    emailNotificationFacade.sendNewEnquiryEmailNotification(session, null);

    org.assertj.core.api.Assertions.assertThat(
            facadeLogCaptor.contains(
                Level.ERROR, "Failed to send new enquiry notification for session"))
        .isTrue();
  }

  @Test
  void sendAssignEnquiryEmailNotification_Should_LogError_When_MailServiceHelperThrowsException() {
    doThrow(new RuntimeException("unexpected")).when(mailService).sendEmailNotification(any());
    when(consultantService.getConsultant(any())).thenReturn(Optional.of(CONSULTANT));
    emailNotificationFacade.sendAssignEnquiryEmailNotification(
        givenEnquirySession(), CONSULTANT, USER_ID, NAME, null);
    org.assertj.core.api.Assertions.assertThat(
            facadeLogCaptor.contains(Level.ERROR, "EmailNotificationFacade error:"))
        .isTrue();
  }

  @Test
  void
      sendNewEnquiryEmailNotification_Should_notSendAnyMail_When_sessionHasAlreadyAConsultantAssigned() {
    emailNotificationFacade.sendNewEnquiryEmailNotification(
        new EasyRandom().nextObject(Session.class), null);

    verifyNoInteractions(newEnquiryEmailSupplier);
  }

  @Test
  void sendReassignRequestNotification_Should_SendEmail_When_askerHasValidMailAddress() {
    var session = new EasyRandom().nextObject(Session.class);
    when(sessionService.getSessionByMatrixRoomId(any())).thenReturn(session);
    session.getUser().setEmail("mail@valid.de");
    session
        .getUser()
        .setNotificationsSettings(
            JsonSerializationUtils.serializeToJsonString(new NotificationsSettingsDTO()));

    emailNotificationFacade.sendReassignRequestNotification("id", null);

    verify(mailService).sendEmailNotification(Mockito.any());
  }

  @Test
  void sendReassignRequestNotification_ShouldNot_SendEmail_When_askerHasDummyMailAddress() {
    var session = new EasyRandom().nextObject(Session.class);
    when(sessionService.getSessionByMatrixRoomId(any())).thenReturn(session);
    session.getUser().setEmail("mail@" + FIELD_VALUE_EMAIL_DUMMY_SUFFIX);

    emailNotificationFacade.sendReassignRequestNotification("id", new TenantData(42L, "tenant"));

    verifyNoInteractions(mailService);
    assertThat(TenantContext.getCurrentTenant()).isNull();
  }

  @Test
  void sendReassignRequestNotification_ShouldClearTenantContext_WhenSessionLookupFails() {
    when(sessionService.getSessionByMatrixRoomId(MATRIX_ROOM_ID))
        .thenThrow(new IllegalStateException("Matrix room lookup failed"));

    assertThrows(
        IllegalStateException.class,
        () ->
            emailNotificationFacade.sendReassignRequestNotification(
                MATRIX_ROOM_ID, new TenantData(42L, "tenant")));

    assertThat(TenantContext.getCurrentTenant()).isNull();
  }

  @Test
  void
      sendReassignRequestNotification_Should_SendEmail_When_NewNotificationModeEnabledAndAskerDoesNotWantToReceiveNotifications() {
    var session = new EasyRandom().nextObject(Session.class);
    when(sessionService.getSessionByMatrixRoomId(any())).thenReturn(session);
    session.getUser().setEmail("mail@valid.de");
    session
        .getUser()
        .setNotificationsSettings(
            JsonSerializationUtils.serializeToJsonString(
                new NotificationsSettingsDTO().reassignmentNotificationEnabled(false)));
    when(releaseToggleService.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS))
        .thenReturn(true);

    emailNotificationFacade.sendReassignRequestNotification("id", null);

    verifyNoInteractions(mailService);
  }

  @Test
  void sendReassignConfirmationNotification_Should_sendEmail_When_consultantsExists() {
    var randomConsultant = new EasyRandom().nextObject(Consultant.class);
    when(consultantService.getConsultant(any())).thenReturn(Optional.of(randomConsultant));
    var reassignmentNotification = new EasyRandom().nextObject(ReassignmentNotificationDTO.class);
    randomConsultant.setNotificationsSettings(
        JsonSerializationUtils.serializeToJsonString(new NotificationsSettingsDTO()));

    emailNotificationFacade.sendReassignConfirmationNotification(reassignmentNotification, null);

    verifyAsync(a -> mailService.sendEmailNotification(Mockito.any()));
  }

  @Test
  void
      sendReassignConfirmationNotification_Should_sendNotEmail_When_newEmailNotificationsEnabledAndConsultantsDoesNotWantToReceiveNotifications() {
    var randomConsultant = new EasyRandom().nextObject(Consultant.class);
    when(consultantService.getConsultant(any())).thenReturn(Optional.of(randomConsultant));
    randomConsultant.setNotificationsSettings(
        JsonSerializationUtils.serializeToJsonString(
            new NotificationsSettingsDTO().reassignmentNotificationEnabled(false)));
    var reassignmentNotification = new EasyRandom().nextObject(ReassignmentNotificationDTO.class);
    randomConsultant.setNotificationsSettings(
        JsonSerializationUtils.serializeToJsonString(new NotificationsSettingsDTO()));
    when(releaseToggleService.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS))
        .thenReturn(true);

    emailNotificationFacade.sendReassignConfirmationNotification(reassignmentNotification, null);

    verifyAsync(a -> mailService.sendEmailNotification(Mockito.any()));
  }

  @Test
  void
      sendReassignConfirmationNotification_Should_sendEmail_When_newEmailNotificationsEnabledAndConsultantsDoesWantsToReceiveNotifications() {
    var randomConsultant = new EasyRandom().nextObject(Consultant.class);
    when(consultantService.getConsultant(any())).thenReturn(Optional.of(randomConsultant));
    randomConsultant.setNotificationsSettings(
        JsonSerializationUtils.serializeToJsonString(
            new NotificationsSettingsDTO().reassignmentNotificationEnabled(true)));
    var reassignmentNotification = new EasyRandom().nextObject(ReassignmentNotificationDTO.class);
    randomConsultant.setNotificationsSettings(
        JsonSerializationUtils.serializeToJsonString(new NotificationsSettingsDTO()));
    when(releaseToggleService.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS))
        .thenReturn(true);

    emailNotificationFacade.sendReassignConfirmationNotification(reassignmentNotification, null);

    verifyAsync(a -> mailService.sendEmailNotification(Mockito.any()));
  }

  @Test
  void
      sendReassignConfirmationNotification_ShouldThrow_NotFoundEception_When_consultantDoesNotExist() {
    assertThrows(
        NotFoundException.class,
        () -> {
          var reassignmentNotification =
              new EasyRandom().nextObject(ReassignmentNotificationDTO.class);
          when(consultantService.getConsultant(any())).thenReturn(Optional.empty());

          emailNotificationFacade.sendReassignConfirmationNotification(
              reassignmentNotification, null);
        });
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-06
  // ---------------------------------------------------------------------------

  @Test
  void sendNewDirectEnquiryEmailNotification_Should_SendEmail_When_MailsGenerated() {
    when(newDirectEnquiryEmailSupplier.generateEmails()).thenReturn(getMailDTOS());

    emailNotificationFacade.sendNewDirectEnquiryEmailNotification(SESSION, null);

    verify(mailService).sendEmailNotification(Mockito.any(MailsDTO.class));
  }

  @Test
  void sendNewDirectEnquiryEmailNotification_ForwardsRequestFactsToTransport() {
    when(newDirectEnquiryEmailSupplier.generateEmails()).thenReturn(getMailDTOS());
    when(notificationRequestFacts.forSession(SESSION))
        .thenReturn(List.of(new TemplateDataDTO().key("requestTopic").value("Housing")));

    emailNotificationFacade.sendNewDirectEnquiryEmailNotification(SESSION, null);

    var sent = ArgumentCaptor.forClass(MailsDTO.class);
    verify(mailService).sendEmailNotification(sent.capture());
    assertThat(sent.getValue().getMails().getFirst().getTemplateData())
        .extracting(TemplateDataDTO::getKey)
        .contains("requestTopic");
  }

  @Test
  void sendNewDirectEnquiryEmailNotification_ShouldNot_SendEmail_When_MailListIsEmpty() {
    when(newDirectEnquiryEmailSupplier.generateEmails()).thenReturn(List.of());

    emailNotificationFacade.sendNewDirectEnquiryEmailNotification(SESSION, null);

    verify(mailService, times(0)).sendEmailNotification(Mockito.any(MailsDTO.class));
  }

  @Test
  void sendNewDirectEnquiryEmailNotification_Should_LogError_When_GenerateEmailsThrows() {
    when(newDirectEnquiryEmailSupplier.generateEmails())
        .thenThrow(new EmailNotificationException(new Exception()));

    emailNotificationFacade.sendNewDirectEnquiryEmailNotification(SESSION, null);

    org.assertj.core.api.Assertions.assertThat(
            facadeLogCaptor.contains(
                Level.ERROR, "Failed to send NEW_DIRECT_ENQUIRY_EMAIL_NOTIFICATION"))
        .isTrue();
  }

  @Test
  void sendInquiryAcceptedNotification_ShouldNot_SendEmail_When_UserHasInvalidEmail() {
    emailNotificationFacade.sendInquiryAcceptedNotification(USER_NO_EMAIL, CONSULTANT, null);

    verifyNoInteractions(mailService);
  }

  @Test
  void
      sendInquiryAcceptedNotification_ShouldNot_SendEmail_When_ToggleEnabledAndUserHasNotificationsDisabled() {
    when(releaseToggleService.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS))
        .thenReturn(true);

    emailNotificationFacade.sendInquiryAcceptedNotification(USER, CONSULTANT, null);

    verifyNoInteractions(mailService);
  }

  @Test
  void automaticNoticeEmitsExplicitOccasionTenantScopeAndGermanDialect() {
    USER.setTenantId(7L);
    USER.setLanguageFormal(false);
    emailNotificationFacade.sendInquiryAcceptedNotification(
        USER, CONSULTANT_WITH_PSEUDONYM, new TenantData(7L, "tenant"));

    var captor = org.mockito.ArgumentCaptor.forClass(MailsDTO.class);
    verify(mailService).sendEmailNotification(captor.capture());
    var mail = captor.getValue().getMails().get(0);
    assertThat(mail.getTemplate()).isEqualTo("inquiry-accepted-notification");
    assertThat(mail.getDialect())
        .isEqualTo(de.caritas.cob.userservice.mailservice.generated.web.model.Dialect.INFORMAL);
    assertThat(mail.getTemplateData())
        .anySatisfy(
            item -> {
              assertThat(item.getKey()).isEqualTo("tenantId");
              assertThat(item.getValue()).isEqualTo("7");
            })
        .anySatisfy(
            item -> {
              assertThat(item.getKey()).isEqualTo("recipientTenantId");
              assertThat(item.getValue()).isEqualTo("7");
            })
        .noneSatisfy(item -> assertThat(item.getKey()).isIn("subject", "text", "name", "topic"));
  }

  @Test
  void automaticNoticeRejectsDifferentRecipientAndRequestTenantsAndClearsContext() {
    USER.setTenantId(7L);
    emailNotificationFacade.sendInquiryAcceptedNotification(
        USER, CONSULTANT_WITH_PSEUDONYM, new TenantData(8L, "other"));
    verifyNoInteractions(mailService);
    assertThat(TenantContext.getCurrentTenant()).isNull();
  }

  @Test
  void automaticNoticeRejectsAnUnidentifiedRecipientTenantInsteadOfAssumingOne() {
    USER.setTenantId(null);
    emailNotificationFacade.sendInquiryAcceptedNotification(USER, CONSULTANT_WITH_PSEUDONYM, null);
    verifyNoInteractions(mailService);
    assertThat(facadeLogCaptor.events())
        .anySatisfy(event -> assertThat(event.getThrowableProxy().getMessage()).contains("tenant"));
  }

  @Test
  void automaticNoticeSingleTenantModeUsesTheActualUserTenantWithoutAContextGuess() {
    USER.setTenantId(7L);
    emailNotificationFacade.sendInquiryAcceptedNotification(USER, CONSULTANT, null);
    var sent = org.mockito.ArgumentCaptor.forClass(MailsDTO.class);
    verify(mailService).sendEmailNotification(sent.capture());
    assertThat(sent.getValue().getMails().get(0).getTemplateData())
        .filteredOn(item -> List.of("tenantId", "recipientTenantId").contains(item.getKey()))
        .hasSize(2)
        .allSatisfy(item -> assertThat(item.getValue()).isEqualTo("7"));
  }

  @Test
  void automaticNoticeMultitenancyRequiresAnExplicitRequestTenant() {
    USER.setTenantId(7L);
    ReflectionTestUtils.setField(emailNotificationFacade, "multiTenancyEnabled", true);
    emailNotificationFacade.sendInquiryAcceptedNotification(USER, CONSULTANT, null);
    verifyNoInteractions(mailService);
    assertThat(TenantContext.getCurrentTenant()).isNull();
  }

  @Test
  void automaticNoticeOptOutClearsTheCapturedContextBeforeReturning() {
    USER.setTenantId(7L);
    when(releaseToggleService.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS))
        .thenReturn(true);
    emailNotificationFacade.sendInquiryAcceptedNotification(
        USER, CONSULTANT, new TenantData(7L, "tenant"));
    verifyNoInteractions(mailService);
    assertThat(TenantContext.getCurrentTenant()).isNull();
  }

  @Test
  void sendInquiryAcceptedNotification_Should_SendEmail_When_ToggleDisabled() {
    emailNotificationFacade.sendInquiryAcceptedNotification(USER, CONSULTANT, null);

    verify(mailService).sendEmailNotification(Mockito.any(MailsDTO.class));
  }

  @Test
  void sendInquiryAcceptedNotification_Should_KeepSubjectAndBodyNeutral_When_ConsultantIsNull() {
    USER.setLanguageFormal(true);
    emailNotificationFacade.sendInquiryAcceptedNotification(USER, null, null);

    var captor = org.mockito.ArgumentCaptor.forClass(MailsDTO.class);
    verify(mailService).sendEmailNotification(captor.capture());
    var mail = captor.getValue().getMails().get(0);
    assertThat(mail.getTemplate()).isEqualTo("inquiry-accepted-notification");
    assertThat(mail.getDialect())
        .isEqualTo(de.caritas.cob.userservice.mailservice.generated.web.model.Dialect.FORMAL);
    assertThat(mail.getTemplateData())
        .noneSatisfy(item -> assertThat(item.getKey()).isIn("subject", "text", "name"));
  }

  // ---------------------------------------------------------------------------
  // The mailbox subject and preview must reveal neither the counsellor nor the case.
  // ---------------------------------------------------------------------------

  @Test
  void sendInquiryAcceptedNotification_Should_NotExposeAnyConsultantName() {
    emailNotificationFacade.sendInquiryAcceptedNotification(USER, CONSULTANT_WITH_PSEUDONYM, null);

    org.assertj.core.api.Assertions.assertThat(capturedInquiryAcceptedMetadata())
        .doesNotContain("Frau M.")
        .doesNotContain(REAL_FIRST_NAME)
        .doesNotContain(REAL_LAST_NAME);
  }

  @Test
  void sendInquiryAcceptedNotification_Should_NotExposeUsername_When_NoDisplayNameIsSet() {
    emailNotificationFacade.sendInquiryAcceptedNotification(
        USER, CONSULTANT_WITHOUT_PSEUDONYM, null);

    org.assertj.core.api.Assertions.assertThat(capturedInquiryAcceptedMetadata())
        .doesNotContain("beraterin1")
        .doesNotContain(REAL_FIRST_NAME)
        .doesNotContain(REAL_LAST_NAME);
  }

  @Test
  void sendInquiryAcceptedNotification_Should_KeepBodyNeutral_When_NoPublicNameExists() {
    emailNotificationFacade.sendInquiryAcceptedNotification(
        USER, CONSULTANT_WITHOUT_ANY_PUBLIC_NAME, null);

    org.assertj.core.api.Assertions.assertThat(capturedInquiryAcceptedMetadata())
        .doesNotContain("Beraterin")
        .doesNotContain(REAL_FIRST_NAME)
        .doesNotContain(REAL_LAST_NAME);
  }

  private String capturedInquiryAcceptedMetadata() {
    var captor = org.mockito.ArgumentCaptor.forClass(MailsDTO.class);
    verify(mailService).sendEmailNotification(captor.capture());
    return captor.getValue().getMails().get(0).getTemplateData().stream()
        .map(de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO::getValue)
        .collect(java.util.stream.Collectors.joining(" "));
  }

  @Test
  void sendInquiryAcceptedNotification_Should_PreserveAllSevenVariantsAndGermanNullLocale() {
    Object[][] variants = {
      {null, true}, {LanguageCode.de, true}, {LanguageCode.de, false},
      {LanguageCode.en, false}, {LanguageCode.fr, false}, {LanguageCode.ru, false},
      {LanguageCode.ti, false}, {LanguageCode.tr, false}
    };
    for (Object[] variant : variants) {
      USER.setLanguageCode((LanguageCode) variant[0]);
      USER.setLanguageFormal((boolean) variant[1]);
      emailNotificationFacade.sendInquiryAcceptedNotification(
          USER, CONSULTANT_WITH_PSEUDONYM, null);
      var captor = org.mockito.ArgumentCaptor.forClass(MailsDTO.class);
      verify(mailService).sendEmailNotification(captor.capture());
      var sentMail = captor.getValue().getMails().get(0);
      assertThat(sentMail.getLanguage().toString())
          .isEqualTo(variant[0] == null ? "de" : variant[0].toString());
      assertThat(sentMail.getTemplate()).isEqualTo("inquiry-accepted-notification");
      assertThat(sentMail.getDialect())
          .isEqualTo(
              (boolean) variant[1]
                  ? de.caritas.cob.userservice.mailservice.generated.web.model.Dialect.FORMAL
                  : de.caritas.cob.userservice.mailservice.generated.web.model.Dialect.INFORMAL);
      assertThat(sentMail.getTemplateData())
          .allSatisfy(
              item ->
                  assertThat(item.getValue())
                      .doesNotContain("Frau M.", REAL_FIRST_NAME, REAL_LAST_NAME, "suchtberatung"));
      Mockito.clearInvocations(mailService);
    }
  }

  @Test
  void sendInquiryAcceptedNotification_Should_ValidateCanonicalConfiguredPlatformName() {
    bindCanonicalPlatformName("  Independent Platform  ");
    USER.setLanguageCode(LanguageCode.en);

    emailNotificationFacade.sendInquiryAcceptedNotification(USER, CONSULTANT_WITH_PSEUDONYM, null);

    var captor = org.mockito.ArgumentCaptor.forClass(MailsDTO.class);
    verify(mailService).sendEmailNotification(captor.capture());
    assertThat(captor.getValue().getMails().get(0).getTemplate())
        .isEqualTo("inquiry-accepted-notification");
    org.assertj.core.api.Assertions.assertThat(emailBrand.values("https://app.example.org", null))
        .containsEntry("platformName", "Independent Platform")
        .containsEntry("offeringName", "Independent Platform");
  }

  @Test
  void sendInquiryAcceptedNotification_Should_NotSendAndReportMissingCanonicalPlatformName() {
    bindCanonicalPlatformName("  ");

    emailNotificationFacade.sendInquiryAcceptedNotification(
        USER, CONSULTANT_WITH_PSEUDONYM, new TenantData(42L, "tenant"));

    verifyNoInteractions(mailService);
    org.assertj.core.api.Assertions.assertThat(facadeLogCaptor.events())
        .anySatisfy(
            event ->
                org.assertj.core.api.Assertions.assertThat(event.getThrowableProxy().getMessage())
                    .contains("EMAIL_BRANDING_NAME"));
    org.assertj.core.api.Assertions.assertThat(TenantContext.getCurrentTenant()).isNull();
  }

  private void bindCanonicalPlatformName(String name) {
    try (var context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "mail-test",
                  Map.of(
                      "email.branding.name",
                      name,
                      "app.base.url",
                      APPLICATION_BASE_URL,
                      "multitenancy.enabled",
                      "false")));
      context.registerBean(OrisoEmailBrand.class, () -> emailBrand);
      context.registerBean(EmailNotificationFacade.class, () -> emailNotificationFacade);
      context.refresh();
    }
  }

  @Test
  void
      sendInquiryAcceptedNotification_Should_UseTenantTemplateAttributes_When_MultiTenancyEnabled() {
    ReflectionTestUtils.setField(emailNotificationFacade, "multiTenancyEnabled", true);
    when(tenantTemplateSupplier.getTemplateAttributes())
        .thenReturn(
            List.of(
                new de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO()
                    .key("tenantKey")
                    .value("tenantValue")));

    emailNotificationFacade.sendInquiryAcceptedNotification(
        USER, CONSULTANT, new TenantData(1L, "tenant"));

    var captor = org.mockito.ArgumentCaptor.forClass(MailsDTO.class);
    verify(mailService).sendEmailNotification(captor.capture());
    var templateData = captor.getValue().getMails().get(0).getTemplateData();
    org.assertj.core.api.Assertions.assertThat(
            templateData.stream().anyMatch(td -> "tenantKey".equals(td.getKey())))
        .isTrue();
    ReflectionTestUtils.setField(emailNotificationFacade, "multiTenancyEnabled", false);
  }

  @Test
  void sendInquiryAcceptedNotification_Should_LogError_When_MailServiceThrows() {
    doThrow(new RuntimeException("boom")).when(mailService).sendEmailNotification(any());

    emailNotificationFacade.sendInquiryAcceptedNotification(USER, CONSULTANT, null);

    org.assertj.core.api.Assertions.assertThat(
            facadeLogCaptor.contains(Level.ERROR, "Failed to send inquiry accepted notification"))
        .isTrue();
  }
}
