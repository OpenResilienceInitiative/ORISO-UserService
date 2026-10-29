package de.caritas.cob.userservice.api.service.emailsupplier;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_NEW_DIRECT_ENQUIRY_NOTIFICATION;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.AGENCY_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT_2;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.POSTCODE;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggle;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.mailservice.generated.web.model.LanguageCode;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NewDirectEnquiryEmailSupplierTest {

  private NewDirectEnquiryEmailSupplier newDirectEnquiryEmailSupplier;

  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private ReleaseToggleService releaseToggleService;
  @Mock private de.caritas.cob.userservice.api.port.out.IdentityAccountStatusLookup identities;

  @Mock
  private de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService
      accountLifecycle;

  @BeforeEach
  void setup() {
    org.mockito.Mockito.lenient()
        .when(identities.findEnabledById(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(java.util.Optional.of(true));
    newDirectEnquiryEmailSupplier =
        new NewDirectEnquiryEmailSupplier(
            consultantAgencyRepository,
            new de.caritas.cob.userservice.api.service.notification.NotificationAccountEligibility(
                accountLifecycle, identities),
            null,
            releaseToggleService);
    newDirectEnquiryEmailSupplier.setAgencyId(AGENCY_ID);
    newDirectEnquiryEmailSupplier.setConsultantId(CONSULTANT_ID);
    newDirectEnquiryEmailSupplier.setPostCode(POSTCODE);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      de.caritas.cob.userservice.api.model.ConsultantStatus.class)
  void onlyCreatedOrInProgressConsultantsReceiveNewEnquiries(
      de.caritas.cob.userservice.api.model.ConsultantStatus status) {
    var recipient = currentRecipient();
    org.assertj.core.api.Assertions.assertThat(newDirectEnquiryEmailSupplier.generateEmails())
        .hasSize(1);
    recipient.setStatus(status);
    org.assertj.core.api.Assertions.assertThat(newDirectEnquiryEmailSupplier.generateEmails())
        .hasSize(
            status == de.caritas.cob.userservice.api.model.ConsultantStatus.CREATED
                    || status == de.caritas.cob.userservice.api.model.ConsultantStatus.IN_PROGRESS
                ? 1
                : 0);
  }

  @Test
  void deletedConsultantBehindALiveRelationReceivesNoNewEnquiry() {
    var recipient = currentRecipient();
    org.assertj.core.api.Assertions.assertThat(newDirectEnquiryEmailSupplier.generateEmails())
        .hasSize(1);
    recipient.setDeleteDate(java.time.LocalDateTime.now());
    org.assertj.core.api.Assertions.assertThat(newDirectEnquiryEmailSupplier.generateEmails())
        .isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.Status
          .class)
  void inactiveLifecycleCannotReceiveNewEnquiriesDespiteRetainedAgencyMembership(
      de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.Status
          status) {
    var recipient = currentRecipient();
    org.assertj.core.api.Assertions.assertThat(newDirectEnquiryEmailSupplier.generateEmails())
        .hasSize(1);
    var now = java.time.Instant.now();
    when(accountLifecycle.snapshot(recipient.getId()))
        .thenReturn(
            java.util.Optional.of(
                new de.caritas.cob.userservice.api.workflow.accountinactivity
                    .AccountInactivityService.Snapshot(
                    recipient.getId(), 7L, 12, 0, now, now, status, 0, null)));
    org.assertj.core.api.Assertions.assertThat(newDirectEnquiryEmailSupplier.generateEmails())
        .hasSize(
            status
                    == de.caritas.cob.userservice.api.workflow.accountinactivity
                        .AccountInactivityService.Status.ACTIVE
                ? 1
                : 0);
  }

  @Test
  void disabledIdentityBehindALiveAgencyRelationCannotReceiveANewEnquiry() {
    currentRecipient();
    org.assertj.core.api.Assertions.assertThat(newDirectEnquiryEmailSupplier.generateEmails())
        .hasSize(1);
    org.mockito.Mockito.lenient()
        .when(identities.findEnabledById("guard-recipient"))
        .thenReturn(java.util.Optional.of(false));
    org.assertj.core.api.Assertions.assertThat(newDirectEnquiryEmailSupplier.generateEmails())
        .isEmpty();
  }

  @Test
  void generateEmails_Should_ReturnEmptyList_When_NoParametersAreProvided() {
    List<MailDTO> generatedMails = newDirectEnquiryEmailSupplier.generateEmails();

    assertThat(generatedMails, hasSize(0));
  }

  @Test
  void generateEmails_Should_ReturnEmptyList_When_NoValidConsultantWasFound() {
    var absentConsultant = new Consultant();
    absentConsultant.setAbsent(true);
    absentConsultant.setEmail("email");

    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            anyString(), anyLong()))
        .thenReturn(
            asList(
                new ConsultantAgency(
                    0L, new Consultant(), 1L, nowInUtc(), nowInUtc(), nowInUtc(), null, null),
                new ConsultantAgency(
                    1L, absentConsultant, 1L, nowInUtc(), nowInUtc(), nowInUtc(), null, null)));

    var generatedMails = newDirectEnquiryEmailSupplier.generateEmails();

    assertThat(generatedMails, hasSize(0));
  }

  @Test
  void generateEmails_Should_ReturnExpectedMailDTO_When_PresentConsultantHasBeenFound() {
    var consultantAgency =
        new ConsultantAgency(
            0L, CONSULTANT_2, AGENCY_ID, nowInUtc(), nowInUtc(), nowInUtc(), null, null);
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            CONSULTANT_ID, AGENCY_ID))
        .thenReturn(List.of(consultantAgency));

    var generatedMails = newDirectEnquiryEmailSupplier.generateEmails();

    assertThat(generatedMails, hasSize(1));

    var generatedMail = generatedMails.get(0);
    assertThat(generatedMail.getTemplate(), is(TEMPLATE_NEW_DIRECT_ENQUIRY_NOTIFICATION));
    assertThat(generatedMail.getEmail(), is("email@email.com"));
    assertThat(generatedMail.getLanguage(), is(LanguageCode.DE));
    assertThat(generatedMail.getDialect(), is(CONSULTANT_2.getDialect()));

    var templateData = generatedMail.getTemplateData();
    assertThat(templateData, hasSize(3));
    assertThat(templateData.get(0).getKey(), is("name"));
    assertThat(templateData.get(0).getValue(), is("first name last name"));
    assertThat(templateData.get(1).getKey(), is("plz"));
    assertThat(templateData.get(1).getValue(), is(POSTCODE));
    assertThat(templateData.get(2).getKey(), is("url"));
  }

  @Test
  void
      generateEmails_Should_ReturnEmptyList_When_NewNotificationsFeatureEnabledButConsultantNotificationsDisabled() {
    var consultantAgency =
        new ConsultantAgency(
            0L, CONSULTANT, AGENCY_ID, nowInUtc(), nowInUtc(), nowInUtc(), null, null);
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            CONSULTANT_ID, AGENCY_ID))
        .thenReturn(List.of(consultantAgency));
    var generatedMails = newDirectEnquiryEmailSupplier.generateEmails();

    Assertions.assertThat(generatedMails).isEmpty();
  }

  @Test
  void
      generateEmails_Should_ReturnExpectedMailDTO_When_NewNotificationsFeatureEnabledAndConsultantHasNotificationsEnabled() {
    var consultantAgency =
        new ConsultantAgency(
            0L, CONSULTANT_2, AGENCY_ID, nowInUtc(), nowInUtc(), nowInUtc(), null, null);
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            CONSULTANT_ID, AGENCY_ID))
        .thenReturn(List.of(consultantAgency));
    when(releaseToggleService.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS))
        .thenReturn(true);
    var generatedMails = newDirectEnquiryEmailSupplier.generateEmails();

    assertThat(generatedMails, hasSize(1));

    var generatedMail = generatedMails.get(0);
    assertThat(generatedMail.getTemplate(), is(TEMPLATE_NEW_DIRECT_ENQUIRY_NOTIFICATION));
    assertThat(generatedMail.getEmail(), is("email@email.com"));
    assertThat(generatedMail.getLanguage(), is(LanguageCode.DE));
    assertThat(generatedMail.getDialect(), is(CONSULTANT_2.getDialect()));
    var templateData = generatedMail.getTemplateData();
    assertThat(templateData, hasSize(3));
    assertThat(templateData.get(0).getKey(), is("name"));
    assertThat(templateData.get(0).getValue(), is("first name last name"));

    assertThat(templateData.get(1).getKey(), is("plz"));
    assertThat(templateData.get(1).getValue(), is(POSTCODE));
    assertThat(templateData.get(2).getKey(), is("url"));
  }

  private Consultant currentRecipient() {
    var recipient = new Consultant();
    recipient.setId("guard-recipient");
    recipient.setTenantId(7L);
    recipient.setEmail("guard@synthetic.oriso.test");
    recipient.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
    recipient.setStatus(de.caritas.cob.userservice.api.model.ConsultantStatus.CREATED);
    var relation = ConsultantAgency.builder().consultant(recipient).agencyId(70L).build();
    newDirectEnquiryEmailSupplier.setAgencyId(70L);
    newDirectEnquiryEmailSupplier.setConsultantId("guard-recipient");
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            anyString(), anyLong()))
        .thenReturn(List.of(relation));
    return recipient;
  }
}
