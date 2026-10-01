package de.caritas.cob.userservice.api.workflow.enquirynotification.service;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_DAILY_ENQUIRY_NOTIFICATION;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailsDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnquiryNotificationServiceTest {

  @InjectMocks private EnquiryNotificationService enquiryNotificationService;

  @Mock private MailService mailService;

  @Mock private SessionRepository sessionRepository;

  @Mock private ConsultantAgencyService consultantAgencyService;

  @Mock private AgencyService agencyService;

  @Mock private TenantService tenantService;

  @Mock private TenantTemplateSupplier tenantTemplateSupplier;

  @Mock private ReleaseToggleService releaseToggleService;

  @Mock private ScheduledTaskClaimService taskClaimService;

  @BeforeEach
  public void setup() {
    setField(enquiryNotificationService, "openEnquiryCheckHours", 12L);
    setField(enquiryNotificationService, "applicationBaseUrl", "https://app.example.test");
    setField(enquiryNotificationService, "claimDuration", Duration.ofMinutes(30));
    when(taskClaimService.tryClaim("enquiry-notification", Duration.ofMinutes(30)))
        .thenReturn(true);
  }

  @Test
  void
      sendEmailNotificationsForOpenEnquiries_Should_sendExpectedMailsToConsultantsOfAgency_When_agencyHasOpenEnquiries() {
    var openEnquiries = openEnquiriesForAgency(1L, nowInUtc().minusHours(13L), 3);
    openEnquiries.addAll(openEnquiriesForAgency(2L, nowInUtc().minusHours(13L), 2));
    openEnquiries.addAll(openEnquiriesForAgency(3L, nowInUtc().minusHours(13L), 1));
    openEnquiries.addAll(openEnquiriesForAgency(4L, nowInUtc().minusHours(11L), 5));
    when(sessionRepository.findByStatus(SessionStatus.NEW)).thenReturn(openEnquiries);
    when(consultantAgencyService.findConsultantsByAgencyId(1L))
        .thenReturn(
            List.of(
                createConsultantAgencyWithConsultantsMailAddress(
                    "consultant1", "firstname1 lastname1"),
                createConsultantAgencyWithConsultantsMailAddress(
                    "consultant2", "firstname2 lastname2")));
    when(consultantAgencyService.findConsultantsByAgencyId(2L))
        .thenReturn(
            List.of(
                createConsultantAgencyWithConsultantsMailAddress(
                    "consultant3", "firstname3 lastname3")));
    when(consultantAgencyService.findConsultantsByAgencyId(3L))
        .thenReturn(
            List.of(
                createConsultantAgencyWithConsultantsMailAddress(
                    "consultant4", "firstname4 lastname4")));
    var agencies =
        asList(
            createAgency(1L, "Blue Agency"),
            createAgency(2L, "Red Agency"),
            createAgency(3L, "Yellow Agency"));
    when(agencyService.getAgencies(asList(1L, 2L, 3L))).thenReturn(agencies);

    enquiryNotificationService.sendEmailNotificationsForOpenEnquiries();

    var argumentCaptor = ArgumentCaptor.forClass(MailsDTO.class);
    verify(mailService, times(3)).sendEmailNotification(argumentCaptor.capture());
    var resultMailsDTO =
        argumentCaptor.getAllValues().stream()
            .map(MailsDTO::getMails)
            .flatMap(Collection::stream)
            .collect(Collectors.toList());
    assertThat(resultMailsDTO.stream().map(MailDTO::getEmail).toList())
        .containsExactlyInAnyOrder("consultant1", "consultant2", "consultant3", "consultant4");
    resultMailsDTO.forEach(
        mail -> {
          var facts = factsOf(mail);
          assertThat(mail.getTemplate()).isEqualTo(TEMPLATE_DAILY_ENQUIRY_NOTIFICATION);
          assertThat(facts.get("enquiries"))
              .isEqualTo(
                  switch (mail.getEmail()) {
                    case "consultant1", "consultant2" -> "3";
                    case "consultant3" -> "2";
                    default -> "1";
                  });
          assertThat(facts.get("oldestRequestAge")).isEqualTo("13 h");
          assertThat(facts.get("digestGeneratedAt"))
              .matches("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2} UTC");
        });
  }

  @Test
  void digestUsesOldestSameTenantEnquiryAndCarriesAgencyTenantIdentity() {
    setField(enquiryNotificationService, "multitenancyEnabled", true);
    var recent = openEnquiriesForAgency(1L, nowInUtc().minusHours(13), 1).getFirst();
    recent.setTenantId(7L);
    var oldest = openEnquiriesForAgency(1L, nowInUtc().minusHours(49), 1).getFirst();
    oldest.setTenantId(7L);
    var foreign = openEnquiriesForAgency(1L, nowInUtc().minusHours(99), 1).getFirst();
    foreign.setTenantId(8L);
    when(sessionRepository.findByStatus(SessionStatus.NEW))
        .thenReturn(List.of(recent, oldest, foreign));
    when(agencyService.getAgencies(List.of(1L)))
        .thenReturn(List.of(createAgency(1L, "Agency").tenantId(7L)));
    var tenant = new RestrictedTenantDTO().subdomain("seven");
    when(tenantService.getRestrictedTenantData(7L)).thenReturn(tenant);
    when(tenantTemplateSupplier.getTenantBaseUrl(tenant)).thenReturn("https://seven.example.test");
    var recipient = createConsultantAgencyWithConsultantsMailAddress("correct", "First Last");
    recipient.getConsultant().setTenantId(7L);
    var wrongRecipient = createConsultantAgencyWithConsultantsMailAddress("wrong", "Other Person");
    wrongRecipient.getConsultant().setTenantId(8L);
    when(consultantAgencyService.findConsultantsByAgencyId(1L))
        .thenReturn(List.of(recipient, wrongRecipient));

    enquiryNotificationService.sendEmailNotificationsForOpenEnquiries();

    var sent = ArgumentCaptor.forClass(MailsDTO.class);
    verify(mailService).sendEmailNotification(sent.capture());
    assertThat(sent.getValue().getMails()).hasSize(1);
    assertThat(sent.getValue().getMails().getFirst().getEmail()).isEqualTo("correct");
    var facts = factsOf(sent.getValue().getMails().getFirst());
    assertThat(facts.get("tenantId")).isEqualTo("7");
    assertThat(facts.get("enquiries")).isEqualTo("2");
    assertThat(facts.get("oldestRequestAge")).isEqualTo("49 h");
  }

  @Test
  void multitenantDigestUsesExplicitTenantUrl() {
    setField(enquiryNotificationService, "multitenancyEnabled", true);
    var session = openEnquiriesForAgency(1L, nowInUtc().minusHours(13), 1).getFirst();
    session.setTenantId(7L);
    when(sessionRepository.findByStatus(SessionStatus.NEW)).thenReturn(List.of(session));
    when(agencyService.getAgencies(List.of(1L)))
        .thenReturn(List.of(createAgency(1L, "Agency").tenantId(7L)));
    var tenant = new RestrictedTenantDTO().subdomain("seven");
    when(tenantService.getRestrictedTenantData(7L)).thenReturn(tenant);
    when(tenantTemplateSupplier.getTenantBaseUrl(tenant)).thenReturn("https://seven.example.test");
    var recipient = createConsultantAgencyWithConsultantsMailAddress("correct", "First Last");
    recipient.getConsultant().setTenantId(7L);
    when(consultantAgencyService.findConsultantsByAgencyId(1L)).thenReturn(List.of(recipient));

    enquiryNotificationService.sendEmailNotificationsForOpenEnquiries();

    var sent = ArgumentCaptor.forClass(MailsDTO.class);
    verify(mailService).sendEmailNotification(sent.capture());
    assertThat(factsOf(sent.getValue().getMails().getFirst()).get("url"))
        .isEqualTo("https://seven.example.test");
  }

  @Test
  void multitenantDigestSkipsMissingTenantUrl() {
    setField(enquiryNotificationService, "multitenancyEnabled", true);
    var session = openEnquiriesForAgency(1L, nowInUtc().minusHours(13), 1).getFirst();
    session.setTenantId(7L);
    when(sessionRepository.findByStatus(SessionStatus.NEW)).thenReturn(List.of(session));
    when(agencyService.getAgencies(List.of(1L)))
        .thenReturn(List.of(createAgency(1L, "Agency").tenantId(7L)));
    when(tenantService.getRestrictedTenantData(7L))
        .thenReturn(new RestrictedTenantDTO().subdomain("seven"));

    enquiryNotificationService.sendEmailNotificationsForOpenEnquiries();

    verifyNoInteractions(mailService, consultantAgencyService);
  }

  @Test
  void digestDoesNotCombineDifferentTenantsWhenAgencyTenantIsUnavailable() {
    setField(enquiryNotificationService, "multitenancyEnabled", true);
    var first = openEnquiriesForAgency(1L, nowInUtc().minusHours(13), 1).getFirst();
    first.setTenantId(7L);
    var second = openEnquiriesForAgency(1L, nowInUtc().minusHours(15), 1).getFirst();
    second.setTenantId(8L);
    when(sessionRepository.findByStatus(SessionStatus.NEW)).thenReturn(List.of(first, second));
    when(agencyService.getAgencies(List.of(1L))).thenReturn(List.of(createAgency(1L, "Agency")));

    enquiryNotificationService.sendEmailNotificationsForOpenEnquiries();

    verifyNoInteractions(mailService, consultantAgencyService);
  }

  @Test
  void
      sendEmailNotificationsForOpenEnquiries_Should_sendNoMails_When_agencyHasOpenEnquiriesYoungerThanCheckTime() {
    var openEnquiries = openEnquiriesForAgency(1L, nowInUtc().minusHours(11L), 3);
    when(sessionRepository.findByStatus(SessionStatus.NEW)).thenReturn(openEnquiries);

    enquiryNotificationService.sendEmailNotificationsForOpenEnquiries();

    verifyNoInteractions(mailService);
  }

  @Test
  void sendEmailNotificationsForOpenEnquiries_Should_sendNoMails_When_noOpenEnquiriesExists() {
    enquiryNotificationService.sendEmailNotificationsForOpenEnquiries();

    verifyNoInteractions(mailService);
  }

  @Test
  void
      sendEmailNotificationsForOpenEnquiries_Should_sendNoMails_When_agenciesWithOpenEnquiriesHaveNoConsultants() {
    var openEnquiries = openEnquiriesForAgency(1L, nowInUtc().minusHours(13L), 3);
    openEnquiries.addAll(openEnquiriesForAgency(1L, nowInUtc().minusHours(13L), 2));
    openEnquiries.addAll(openEnquiriesForAgency(2L, nowInUtc().minusHours(13L), 1));
    openEnquiries.addAll(openEnquiriesForAgency(3L, nowInUtc().minusHours(11L), 5));
    when(sessionRepository.findByStatus(SessionStatus.NEW)).thenReturn(openEnquiries);

    enquiryNotificationService.sendEmailNotificationsForOpenEnquiries();

    verifyNoInteractions(mailService);
  }

  @Test
  void
      sendEmailNotificationsForOpenEnquiries_Should_sendNoMails_When_agenciesWithOpenEnquiriesAreNotToBeNotified() {
    var openEnquiries = openEnquiriesForAgency(2L, nowInUtc().minusHours(13L), 1);
    when(agencyService.getAgencies(List.of(2L))).thenReturn(List.of(createAgency(2L, "Agency")));
    when(consultantAgencyService.findConsultantsByAgencyId(2L))
        .thenReturn(
            List.of(
                createConsultantAgencyWithConsultantsMailAddress(
                    "consultant3", "firstname3 lastname3", false)));
    when(sessionRepository.findByStatus(SessionStatus.NEW)).thenReturn(openEnquiries);

    enquiryNotificationService.sendEmailNotificationsForOpenEnquiries();

    verifyNoInteractions(mailService);
  }

  @Test
  void sendEmailNotificationsForOpenEnquiries_Should_sendNoMails_When_enquiryDateDoesNotExist() {
    var openEnquiries = openEnquiriesForAgency(1L, null, 3);
    when(sessionRepository.findByStatus(SessionStatus.NEW)).thenReturn(openEnquiries);

    enquiryNotificationService.sendEmailNotificationsForOpenEnquiries();

    verifyNoInteractions(mailService);
  }

  @Test
  void sendEmailNotificationsForOpenEnquiriesShouldDoNoWorkWhenAnotherReplicaOwnsClaim() {
    when(taskClaimService.tryClaim("enquiry-notification", Duration.ofMinutes(30)))
        .thenReturn(false);

    enquiryNotificationService.sendEmailNotificationsForOpenEnquiries();

    verifyNoInteractions(
        mailService,
        sessionRepository,
        consultantAgencyService,
        agencyService,
        releaseToggleService);
  }

  private List<Session> openEnquiriesForAgency(
      Long agencyId, LocalDateTime enquiryDate, int amount) {
    var enquiries = new ArrayList<Session>();
    for (int i = 0; i < amount; i++) {
      var session = new Session();
      session.setAgencyId(agencyId);
      session.setEnquiryMessageDate(enquiryDate);
      enquiries.add(session);
    }
    return enquiries;
  }

  private ConsultantAgency createConsultantAgencyWithConsultantsMailAddress(
      String mail, String fullName) {
    return createConsultantAgencyWithConsultantsMailAddress(mail, fullName, true);
  }

  private ConsultantAgency createConsultantAgencyWithConsultantsMailAddress(
      String mail, String fullName, boolean notifyEnqRep) {
    var consultant = new Consultant();
    String[] firstNameLastName = fullName.split(" ");
    consultant.setFirstName(firstNameLastName[0]);
    consultant.setLastName(firstNameLastName[1]);
    consultant.setEmail(mail);
    consultant.setLanguageCode(LanguageCode.de);
    consultant.setNotifyEnquiriesRepeating(notifyEnqRep);
    var consultantAgency = new ConsultantAgency();
    consultantAgency.setConsultant(consultant);

    return consultantAgency;
  }

  private AgencyDTO createAgency(long id, String name) {
    AgencyDTO agency = new AgencyDTO();
    agency.setId(id);
    agency.setName(name);
    return agency;
  }

  private Map<String, String> factsOf(MailDTO mail) {
    return mail.getTemplateData().stream()
        .collect(Collectors.toMap(TemplateDataDTO::getKey, TemplateDataDTO::getValue));
  }
}
