package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_NEW_ENQUIRY_NOTIFICATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.EnquiryData;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.dpa.NewCounsellingDpaPolicy;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.testConfig.ConsultingTypeManagerTestConfig;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailsDTO;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * ORISO-Frontend#1208: an advice seeker's first enquiry must set off its follow-ups — the
 * counselling centre is told by e-mail, and the chat receives the "first steps" system message
 * (Erstantwort).
 *
 * <p>Both halves already have unit tests with every collaborator mocked. This runs the chain
 * through the real Spring context: the real facade, consultant selection, e-mail supplier,
 * Erstantwort builder, system-message service and database. Only the outer edges are mocked — the
 * Matrix homeserver, the identity status lookup, the mail transport, the agency service and the DPA
 * gate, all reached over HTTP. That a real inbox receives the mail is a Dev check, not something a
 * build can prove.
 */
@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({ConsultingTypeManagerTestConfig.class})
@Transactional
class FirstEnquiryFollowUpChainIT {

  private static final String ROOM_ID = "!first-enquiry-1208:matrix.example";
  private static final String ASKER_MATRIX_ID = "@first-enquiry-1208:matrix.example";
  private static final String ENQUIRY_EVENT_ID = "$first-enquiry-1208";

  @Autowired CreateEnquiryMessageFacade createEnquiryMessageFacade;
  @Autowired SessionRepository sessionRepository;
  @Autowired UserRepository userRepository;
  @Autowired ConsultantAgencyRepository consultantAgencyRepository;

  @MockitoBean MatrixSynapseService matrixSynapseService;
  @MockitoBean MailService mailService;
  @MockitoBean NewCounsellingDpaPolicy dpaPolicy;
  @MockitoBean AgencyService agencyService;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService identities;

  @Autowired de.caritas.cob.userservice.api.port.out.ConsultantRepository consultants;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  private String ownedConsultantId;
  private Long ownedMembershipId;

  @Test
  void theFirstEnquiryEmailsTheCentreAndPostsTheFirstSteps() {
    var counsellor = aCounsellorWhoReceivesEnquiryMails();
    var session = anOpenSessionWithoutEnquiryAt(counsellor.getAgencyId());
    org.mockito.Mockito.doReturn(Optional.of(true))
        .when(identities)
        .findEnabledById(counsellor.getConsultant().getId());
    stubMatrix();
    when(agencyService.getAgency(counsellor.getAgencyId()))
        .thenReturn(new AgencyDTO().id(counsellor.getAgencyId()).name("Test counselling centre"));

    var enquiry =
        new EnquiryData(session.getUser(), session.getId(), "I have debts with my bank.", "de");
    enquiry.setMatrixEventId(ENQUIRY_EVENT_ID);
    enquiry.setUiLocale("de");
    createEnquiryMessageFacade.createEnquiryMessage(enquiry);

    // The centre is told: the new-enquiry mail goes to its counsellor (sent asynchronously).
    var mails = ArgumentCaptor.forClass(MailsDTO.class);
    verify(mailService, timeout(10_000)).sendEmailNotification(mails.capture());
    assertThat(mails.getValue().getMails())
        .extracting(MailDTO::getTemplate, MailDTO::getEmail)
        .contains(
            org.assertj.core.groups.Tuple.tuple(
                TEMPLATE_NEW_ENQUIRY_NOTIFICATION, counsellor.getConsultant().getEmail()));

    // The advice seeker is answered: exactly one first-steps system message in their room.
    var posted = ArgumentCaptor.forClass(String.class);
    verify(matrixSynapseService, times(1)).sendMessage(eq(ROOM_ID), posted.capture(), anyString());
    assertThat(posted.getValue())
        .startsWith("[SYSTEM_NOTIFICATION]")
        .contains("\"type\":\"FIRST_RESPONSE\"");

    var stored = sessionRepository.findById(session.getId()).orElseThrow();
    assertThat(stored.getEnquiryMessageDate()).isNotNull();
  }

  private ConsultantAgency aCounsellorWhoReceivesEnquiryMails() {
    // The async supplier needs a committed eligible recipient, not an unqualified seed row.
    var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
    transaction.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction.execute(
        ignored -> {
          ownedConsultantId = java.util.UUID.randomUUID().toString();
          var consultant =
              consultants.saveAndFlush(
                  de.caritas.cob.userservice.api.model.Consultant.builder()
                      .id(ownedConsultantId)
                      .username(ownedConsultantId)
                      .firstName("Synthetic")
                      .lastName("Recipient")
                      .email(ownedConsultantId + "@synthetic.oriso.test")
                      .tenantId(1L)
                      .status(de.caritas.cob.userservice.api.model.ConsultantStatus.IN_PROGRESS)
                      .notificationsEnabled(true)
                      .notificationsSettings("{\"initialEnquiryNotificationEnabled\":true}")
                      .languageFormal(true)
                      .languageCode(com.neovisionaries.i18n.LanguageCode.de)
                      .encourage2fa(true)
                      .magicLinkLoginEnabled(false)
                      .notifyEnquiriesRepeating(false)
                      .notifyNewChatMessageFromAdviceSeeker(false)
                      .createDate(java.time.LocalDateTime.now())
                      .updateDate(java.time.LocalDateTime.now())
                      .build());
          var membership =
              consultantAgencyRepository.save(
                  ConsultantAgency.builder()
                      .consultant(consultant)
                      .agencyId(12081208L)
                      .tenantId(1L)
                      .status(
                          de.caritas.cob.userservice.api.model.ConsultantAgencyStatus.IN_PROGRESS)
                      .build());
          ownedMembershipId = membership.getId();
          return membership;
        });
  }

  @org.springframework.test.context.transaction.AfterTransaction
  void removeOnlyTheCommittedRecipientFixture() {
    var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
    // Separate transactions: a failed membership delete must not roll back the consultant delete.
    try {
      transaction.executeWithoutResult(
          ignored -> {
            if (ownedMembershipId != null) consultantAgencyRepository.deleteById(ownedMembershipId);
          });
    } finally {
      transaction.executeWithoutResult(
          ignored -> {
            if (ownedConsultantId != null) consultants.deleteById(ownedConsultantId);
          });
    }
  }

  private Session anOpenSessionWithoutEnquiryAt(Long agencyId) {
    var session =
        StreamSupport.stream(sessionRepository.findAll().spliterator(), false)
            .filter(s -> s.getUser() != null && s.getConsultant() == null)
            .filter(s -> s.getEnquiryMessageDate() == null)
            .filter(s -> s.getRegistrationType() != RegistrationType.ANONYMOUS)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No seeded session without enquiry"));
    session.setAgencyId(agencyId);
    session.setStatus(SessionStatus.INITIAL);
    session.setMatrixRoomId(ROOM_ID);
    session.setIsConsultantDirectlySet(false);
    var asker = session.getUser();
    asker.setMatrixUserId(ASKER_MATRIX_ID);
    userRepository.save(asker);
    return sessionRepository.save(session);
  }

  private void stubMatrix() {
    when(matrixSynapseService.loginAsUserAccessToken(anyString())).thenReturn("token");
    when(matrixSynapseService.getRoomEvent(eq(ROOM_ID), eq(ENQUIRY_EVENT_ID), anyString()))
        .thenReturn(
            Optional.of(
                Map.of(
                    "event_id", ENQUIRY_EVENT_ID,
                    "type", "m.room.encrypted",
                    "sender", ASKER_MATRIX_ID)));
    when(matrixSynapseService.sendMessage(anyString(), anyString(), anyString()))
        .thenReturn(Map.of("event_id", "$erstantwort"));
  }
}
