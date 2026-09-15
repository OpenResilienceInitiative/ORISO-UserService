package de.caritas.cob.userservice.api.service.emailsupplier;

import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_ASSIGN_ENQUIRY_NOTIFICATION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggle;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class AssignEnquiryEmailSupplierTest {

  private AssignEnquiryEmailSupplier assignEnquiryEmailSupplier;

  @Mock private Consultant receiverConsultant;

  @Mock private ConsultantService consultantService;

  @Mock private ReleaseToggleService releaseToggleService;

  private LogbackCaptor logCaptor;

  @BeforeEach
  public void setup() {
    String applicationBaseUrl = "application base url";
    String askerUserName = "asker user name";
    String senderUserId = "sender user id";
    this.assignEnquiryEmailSupplier =
        new AssignEnquiryEmailSupplier(
            receiverConsultant,
            senderUserId,
            askerUserName,
            applicationBaseUrl,
            consultantService,
            null,
            releaseToggleService,
            false);
    logCaptor = LogbackCaptor.forClass(AssignEnquiryEmailSupplier.class);
  }

  @AfterEach
  public void tearDown() {
    logCaptor.detach();
  }

  @ParameterizedTest
  @CsvSource({
    "true,true,false,0", "true,false,true,0", "true,true,true,1",
    "true,true,missing,1", "true,true,none,1", "true,true,null,1",
    "false,false,false,1"
  })
  void respectsAssignmentOptOutAndMasterOnlyInModernMode(
      boolean modern, boolean master, String row, int expected) {
    var receiver = new Consultant();
    receiver.setEmail("recipient@example.org");
    receiver.setFirstName("Example");
    receiver.setLastName("Recipient");
    receiver.setLanguageCode(LanguageCode.de);
    receiver.setNotificationsEnabled(master);
    receiver.setNotificationsSettings(
        row.equals("none")
            ? null
            : row.equals("missing") ? "{}" : "{\"assignmentNotificationEnabled\":" + row + "}");
    assignEnquiryEmailSupplier.setReceiverConsultant(receiver);
    when(releaseToggleService.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS))
        .thenReturn(modern);
    var sender = new Consultant();
    sender.setFirstName("Example");
    sender.setLastName("Sender");
    org.mockito.Mockito.lenient()
        .when(consultantService.getConsultant(any()))
        .thenReturn(Optional.of(sender));

    assertThat(assignEnquiryEmailSupplier.generateEmails(), hasSize(expected));
    if (expected == 0) {
      org.mockito.Mockito.verifyNoInteractions(consultantService);
    }
  }

  @Test
  public void generateEmails_Should_ReturnEmptyListAndLogError_When_NoParametersAreProvided() {
    List<MailDTO> generatedMails = assignEnquiryEmailSupplier.generateEmails();

    assertThat(generatedMails, hasSize(0));
    org.assertj.core.api.Assertions.assertThat(
            logCaptor.contains(Level.ERROR, "Receiver consultant with id"))
        .isTrue();
  }

  @Test
  public void
      generateEmails_Should_ReturnEmptyListAndLogError_When_ReceiverIsValidAndSenderDoesntExist() {
    when(receiverConsultant.getEmail()).thenReturn("Valid email");
    when(consultantService.getConsultant(any())).thenReturn(Optional.empty());

    List<MailDTO> generatedMails = assignEnquiryEmailSupplier.generateEmails();

    assertThat(generatedMails, hasSize(0));
    org.assertj.core.api.Assertions.assertThat(
            logCaptor.contains(Level.ERROR, "Sender consultant with id"))
        .isTrue();
  }

  @Test
  public void generateEmails_Should_ReturnExpectedMailDTO_When_ReceiverAndSenderIsValid() {
    when(receiverConsultant.getEmail()).thenReturn("Valid email");
    when(receiverConsultant.getFullName()).thenReturn("Moritz Mustermann");
    when(receiverConsultant.getLanguageCode()).thenReturn(LanguageCode.de);
    Consultant validConsultant = new Consultant();
    validConsultant.setFirstName("Max");
    validConsultant.setLastName("Mustermann");
    when(consultantService.getConsultant(any())).thenReturn(Optional.of(validConsultant));

    List<MailDTO> generatedMails = assignEnquiryEmailSupplier.generateEmails();

    assertThat(generatedMails, hasSize(1));
    MailDTO generatedMail = generatedMails.get(0);
    assertThat(generatedMail.getTemplate(), is(TEMPLATE_ASSIGN_ENQUIRY_NOTIFICATION));
    assertThat(generatedMail.getEmail(), is("Valid email"));
    assertThat(
        generatedMail.getLanguage(),
        is(de.caritas.cob.userservice.mailservice.generated.web.model.LanguageCode.DE));
    List<TemplateDataDTO> templateData = generatedMail.getTemplateData();
    assertThat(templateData, hasSize(4));
    assertThat(templateData.get(0).getKey(), is("name_sender"));
    assertThat(templateData.get(0).getValue(), is("Max Mustermann"));
    assertThat(templateData.get(1).getKey(), is("name_recipient"));
    assertThat(templateData.get(1).getValue(), is("Moritz Mustermann"));
    assertThat(templateData.get(2).getKey(), is("name_user"));
    assertThat(templateData.get(2).getValue(), is("asker user name"));
    assertThat(templateData.get(3).getKey(), is("url"));
    assertThat(templateData.get(3).getValue(), is("application base url"));
  }
}
