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
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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

  @InjectMocks private EmailNotificationFacade emailNotificationFacade;

  @Mock private NewEnquiryEmailSupplier newEnquiryEmailSupplier;

  @SuppressWarnings("unused")
  @Mock
  private NewDirectEnquiryEmailSupplier newDirectEnquiryEmailSupplier;

  @Spy private AssignEnquiryEmailSupplier assignEnquiryEmailSupplier;
  @Mock private MailService mailService;

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
    when(identityClientConfig.getEmailDummySuffix()).thenReturn(FIELD_VALUE_EMAIL_DUMMY_SUFFIX);
    ReflectionTestUtils.setField(
        emailNotificationFacade, APPLICATION_BASE_URL_FIELD_NAME, APPLICATION_BASE_URL);
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
    emailNotificationFacade.sendAssignEnquiryEmailNotification(CONSULTANT, USER_ID, NAME, null);
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

    emailNotificationFacade.sendNewDirectEnquiryEmailNotification(
        CONSULTANT_ID, AGENCY_ID, "88045", null);

    verify(mailService).sendEmailNotification(Mockito.any(MailsDTO.class));
  }

  @Test
  void sendNewDirectEnquiryEmailNotification_ShouldNot_SendEmail_When_MailListIsEmpty() {
    when(newDirectEnquiryEmailSupplier.generateEmails()).thenReturn(List.of());

    emailNotificationFacade.sendNewDirectEnquiryEmailNotification(
        CONSULTANT_ID, AGENCY_ID, "88045", null);

    verify(mailService, times(0)).sendEmailNotification(Mockito.any(MailsDTO.class));
  }

  @Test
  void sendNewDirectEnquiryEmailNotification_Should_LogError_When_GenerateEmailsThrows() {
    when(newDirectEnquiryEmailSupplier.generateEmails())
        .thenThrow(new EmailNotificationException(new Exception()));

    emailNotificationFacade.sendNewDirectEnquiryEmailNotification(
        CONSULTANT_ID, AGENCY_ID, "88045", null);

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
  void sendInquiryAcceptedNotification_Should_SendEmail_When_ToggleDisabled() {
    emailNotificationFacade.sendInquiryAcceptedNotification(USER, CONSULTANT, null);

    verify(mailService).sendEmailNotification(Mockito.any(MailsDTO.class));
  }

  @Test
  void sendInquiryAcceptedNotification_Should_UseDefaultConsultantName_When_ConsultantIsNull() {
    emailNotificationFacade.sendInquiryAcceptedNotification(USER, null, null);

    var captor = org.mockito.ArgumentCaptor.forClass(MailsDTO.class);
    verify(mailService).sendEmailNotification(captor.capture());
    var text = captor.getValue().getMails().get(0).getTemplateData().get(1).getValue();
    org.assertj.core.api.Assertions.assertThat(text).contains("Ihre Beraterin/Ihr Berater");
  }

  @Test
  void sendInquiryAcceptedNotification_Should_UseConsultantFullName_When_ConsultantProvided() {
    emailNotificationFacade.sendInquiryAcceptedNotification(USER, CONSULTANT, null);

    var captor = org.mockito.ArgumentCaptor.forClass(MailsDTO.class);
    verify(mailService).sendEmailNotification(captor.capture());
    var text = captor.getValue().getMails().get(0).getTemplateData().get(1).getValue();
    org.assertj.core.api.Assertions.assertThat(text).contains(CONSULTANT.getFullName());
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

    emailNotificationFacade.sendInquiryAcceptedNotification(USER, CONSULTANT, null);

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
