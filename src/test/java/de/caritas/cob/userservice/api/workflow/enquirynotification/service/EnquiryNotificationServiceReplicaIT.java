package de.caritas.cob.userservice.api.workflow.enquirynotification.service;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class EnquiryNotificationServiceReplicaIT {

  private static final String TASK_NAME = "enquiry-notification";

  @Autowired private ScheduledTaskClaimService taskClaimService;
  @Autowired private ScheduledTaskClaimRepository claimRepository;

  @BeforeEach
  @AfterEach
  void deleteReplicaProofClaim() {
    claimRepository.findById(TASK_NAME).ifPresent(claimRepository::delete);
  }

  @Test
  void twoServiceInstancesSendOneEnquiryNotificationBatch() throws Exception {
    var mailService = mock(MailService.class);
    var sessionRepository = mock(SessionRepository.class);
    var consultantAgencyService = mock(ConsultantAgencyService.class);
    var agencyService = mock(AgencyService.class);
    var releaseToggleService = mock(ReleaseToggleService.class);
    var session = new Session();
    session.setAgencyId(1L);
    session.setEnquiryMessageDate(nowInUtc().minusHours(13));
    var consultant = new Consultant();
    consultant.setFirstName("Replica");
    consultant.setLastName("Consultant");
    consultant.setEmail("replica-consultant@example.invalid");
    consultant.setLanguageCode(LanguageCode.de);
    consultant.setNotifyEnquiriesRepeating(true);
    var consultantAgency = new ConsultantAgency();
    consultantAgency.setConsultant(consultant);
    var agency = new AgencyDTO();
    agency.setId(1L);
    agency.setName("Replica Agency");
    when(sessionRepository.findByStatus(SessionStatus.NEW)).thenReturn(List.of(session));
    when(agencyService.getAgencies(List.of(1L))).thenReturn(List.of(agency));
    when(consultantAgencyService.findConsultantsByAgencyId(1L))
        .thenReturn(List.of(consultantAgency));
    when(mailService.sendEmailNotification(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    var first =
        newService(
            mailService,
            sessionRepository,
            consultantAgencyService,
            agencyService,
            releaseToggleService,
            taskClaimService);
    var second =
        newService(
            mailService,
            sessionRepository,
            consultantAgencyService,
            agencyService,
            releaseToggleService,
            taskClaimService);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var firstResult = executor.submit(() -> run(first, ready, start));
      var secondResult = executor.submit(() -> run(second, ready, start));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      firstResult.get(5, TimeUnit.SECONDS);
      secondResult.get(5, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    verify(mailService, times(1)).sendEmailNotification(org.mockito.ArgumentMatchers.any());
    verify(sessionRepository, times(1)).findByStatus(SessionStatus.NEW);
    verify(agencyService, times(1)).getAgencies(List.of(1L));
    verify(consultantAgencyService, times(1)).findConsultantsByAgencyId(1L);
    assertThat(claimRepository.findById(TASK_NAME)).isPresent();
  }

  private EnquiryNotificationService newService(
      MailService mailService,
      SessionRepository sessionRepository,
      ConsultantAgencyService consultantAgencyService,
      AgencyService agencyService,
      ReleaseToggleService releaseToggleService,
      ScheduledTaskClaimService taskClaimService) {
    var service =
        new EnquiryNotificationService(
            mailService,
            sessionRepository,
            consultantAgencyService,
            agencyService,
            releaseToggleService,
            taskClaimService);
    setField(service, "openEnquiryCheckHours", 12L);
    setField(service, "applicationBaseUrl", "https://app.oriso.org");
    setField(service, "claimDuration", Duration.ofMinutes(30));
    return service;
  }

  private void run(EnquiryNotificationService service, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    service.sendEmailNotificationsForOpenEnquiries();
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent replica proof");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
