package de.caritas.cob.userservice.api.service.emailsupplier;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_NEW_ENQUIRY_NOTIFICATION;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.AGENCY_DTO_U25;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT_2;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggle;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.api.testHelper.TestLogAppender;
import de.caritas.cob.userservice.mailservice.generated.web.model.LanguageCode;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class NewEnquiryEmailSupplierTest {

  private NewEnquiryEmailSupplier newEnquiryEmailSupplier;

  @Mock private Session session;

  @Mock private ConsultantAgencyRepository consultantAgencyRepository;

  @Mock private AgencyService agencyService;

  @Mock private ReleaseToggleService releaseToggleService;

  @Mock private Logger log;

  private TestLogAppender testAppender;

  @BeforeEach
  void setup() {
    this.newEnquiryEmailSupplier =
        new NewEnquiryEmailSupplier(
            consultantAgencyRepository, agencyService, releaseToggleService, null);
    this.newEnquiryEmailSupplier.setCurrentSession(session);

    // Attach a custom appender to the logger
    Logger logger = (Logger) LoggerFactory.getLogger(NewEnquiryEmailSupplier.class);
    testAppender = new TestLogAppender();
    testAppender.start();
    logger.addAppender(testAppender);
  }

  @Test
  void generateEmails_Should_ReturnEmptyList_When_NoParametersAreProvided() {
    List<MailDTO> generatedMails = newEnquiryEmailSupplier.generateEmails();

    assertThat(generatedMails).isEmpty();
  }

  @Test
  void generateEmails_Should_ReturnEmptyList_When_NoValidConsultantWasFound() {
    Consultant absentConsultant = new Consultant();
    absentConsultant.setAbsent(true);
    absentConsultant.setEmail("email");
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(anyLong()))
        .thenReturn(
            asList(
                null,
                new ConsultantAgency(
                    0L, new Consultant(), 0L, nowInUtc(), nowInUtc(), nowInUtc(), null, null),
                new ConsultantAgency(
                    1L, absentConsultant, 1L, nowInUtc(), nowInUtc(), nowInUtc(), null, null)));

    List<MailDTO> generatedMails = newEnquiryEmailSupplier.generateEmails();

    assertThat(generatedMails).isEmpty();
  }

  @Test
  void generateEmails_Should_ReturnExpectedMailDTO_When_PresentConsultantsWereFound() {
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(anyLong()))
        .thenReturn(
            asList(
                new ConsultantAgency(
                    0L, CONSULTANT_2, 0L, nowInUtc(), nowInUtc(), nowInUtc(), null, null),
                new ConsultantAgency(
                    1L, CONSULTANT_2, 1L, nowInUtc(), nowInUtc(), nowInUtc(), null, null)));
    when(agencyService.getAgency(any())).thenReturn(AGENCY_DTO_U25);
    when(session.getPostcode()).thenReturn("12345");

    List<MailDTO> generatedMails = newEnquiryEmailSupplier.generateEmails();

    assertThat(generatedMails).hasSize(2);
    MailDTO generatedMail = generatedMails.get(0);
    assertThat(generatedMail.getTemplate()).isEqualTo(TEMPLATE_NEW_ENQUIRY_NOTIFICATION);
    assertThat(generatedMail.getEmail()).isEqualTo("email@email.com");
    assertThat(generatedMail.getLanguage()).isEqualTo(LanguageCode.DE);
    List<TemplateDataDTO> templateData = generatedMail.getTemplateData();
    assertThat(templateData).hasSize(4);
    assertThat(templateData.get(0).getKey()).isEqualTo("name");
    assertThat(templateData.get(0).getValue()).isEqualTo("first name last name");
    assertThat(templateData.get(1).getKey()).isEqualTo("plz");
    assertThat(templateData.get(1).getValue()).isEqualTo("12345");
    assertThat(templateData.get(2).getKey()).isEqualTo("beratungsstelle");
    assertThat(templateData.get(2).getValue()).isEqualTo("Test Beratungsstelle");
    assertThat(templateData.get(3).getKey()).isEqualTo("url");
  }

  @Test
  void
      generateEmails_Should_ReturnExpectedMailDTO_When_PresentConsultantsWereFoundAndNotificatonsForConsultantEnabled() {
    when(releaseToggleService.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS))
        .thenReturn(true);
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(anyLong()))
        .thenReturn(
            asList(
                new ConsultantAgency(
                    0L, CONSULTANT_2, 0L, nowInUtc(), nowInUtc(), nowInUtc(), null, null),
                new ConsultantAgency(
                    1L, CONSULTANT_2, 1L, nowInUtc(), nowInUtc(), nowInUtc(), null, null)));
    when(agencyService.getAgency(any())).thenReturn(AGENCY_DTO_U25);
    when(session.getPostcode()).thenReturn("12345");

    List<MailDTO> generatedMails = newEnquiryEmailSupplier.generateEmails();

    assertThat(generatedMails).hasSize(2);
    MailDTO generatedMail = generatedMails.get(0);
    assertThat(generatedMail.getTemplate()).isEqualTo(TEMPLATE_NEW_ENQUIRY_NOTIFICATION);
    assertThat(generatedMail.getEmail()).isEqualTo("email@email.com");
    assertThat(generatedMail.getLanguage()).isEqualTo(LanguageCode.DE);
    List<TemplateDataDTO> templateData = generatedMail.getTemplateData();
    assertThat(templateData).hasSize(4);
    assertThat(templateData.get(0).getKey()).isEqualTo("name");
    assertThat(templateData.get(0).getValue()).isEqualTo("first name last name");
    assertThat(templateData.get(1).getKey()).isEqualTo("plz");
    assertThat(templateData.get(1).getValue()).isEqualTo("12345");
    assertThat(templateData.get(2).getKey()).isEqualTo("beratungsstelle");
    assertThat(templateData.get(2).getValue()).isEqualTo("Test Beratungsstelle");
    assertThat(templateData.get(3).getKey()).isEqualTo("url");
  }

  @Test
  void
      generateEmails_Should_ReturnEmptyList_When_NewNotificationsFeatureEnabledButConsultantNotificationsDisabled() {
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(anyLong()))
        .thenReturn(
            asList(
                new ConsultantAgency(
                    0L, CONSULTANT, 0L, nowInUtc(), nowInUtc(), nowInUtc(), null, null),
                new ConsultantAgency(
                    1L, CONSULTANT, 1L, nowInUtc(), nowInUtc(), nowInUtc(), null, null)));

    List<MailDTO> generatedMails = newEnquiryEmailSupplier.generateEmails();

    assertThat(generatedMails).isEmpty();
  }

  @Test
  @Disabled("TODO this is passing locally but failing in mvn. Fix in CARITAS-285")
  void generateEmails_Should_LogDebugMessage_When_ConsultantIsNull() {
    // given
    ConsultantAgency consultantAgency = new ConsultantAgency();
    consultantAgency.setId(1L);
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(anyLong()))
        .thenReturn(Collections.singletonList(consultantAgency));

    // when
    newEnquiryEmailSupplier.generateEmails();

    // then
    assertTrue(testAppender.contains("consultant is null for agency", Level.DEBUG));
  }

  @Test
  @Disabled("TODO this is passing locally but failing in mvn. Fix in CARITAS-285")
  void generateEmails_Should_LogDebugMessage_When_ConsultantEmailIsBlank() {
    // given
    Consultant consultant = new Consultant();
    consultant.setId("consultant-id");
    consultant.setEmail("");

    ConsultantAgency consultantAgency = new ConsultantAgency();
    consultantAgency.setConsultant(consultant);

    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(anyLong()))
        .thenReturn(Collections.singletonList(consultantAgency));

    // when
    newEnquiryEmailSupplier.generateEmails();

    // then
    assertTrue(testAppender.contains("email is blank for consultant", Level.DEBUG));
  }

  @Test
  @Disabled("TODO this is passing locally but failing in mvn. Fix in CARITAS-285")
  void generateEmails_Should_LogDebugMessage_When_ConsultantIsAbsent() {
    // given
    Consultant consultant = new Consultant();
    consultant.setId("consultant-id");
    consultant.setEmail("test@test.de");
    consultant.setAbsent(true);

    ConsultantAgency consultantAgency = new ConsultantAgency();
    consultantAgency.setConsultant(consultant);

    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(anyLong()))
        .thenReturn(Collections.singletonList(consultantAgency));

    // when
    newEnquiryEmailSupplier.generateEmails();

    // then
    assertTrue(testAppender.contains("is marked as absent", Level.DEBUG));
  }

  @Test
  @Disabled("TODO this is passing locally but failing in mvn. Fix in CARITAS-285")
  void generateEmails_Should_LogDebugMessage_When_NotificationsAreEnabledAndConsultantAbsent() {
    // given
    Consultant consultant = new Consultant();
    consultant.setId("consultant-id");
    consultant.setEmail("test@test.de");
    consultant.setAbsent(true);
    consultant.setNotificationsEnabled(true);

    ConsultantAgency consultantAgency = new ConsultantAgency();
    consultantAgency.setConsultant(consultant);

    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(anyLong()))
        .thenReturn(Collections.singletonList(consultantAgency));

    // when
    newEnquiryEmailSupplier.generateEmails();

    // then
    assertTrue(testAppender.contains("is marked as absent", Level.DEBUG));
  }
}
