package de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.service;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.InactiveAccountNotificationAuditLogRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.helper.MailService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class InactiveAccountNotificationServiceReplicaIT {

  private static final String ACCOUNT_ID = "replica-inactive-asker";
  private static final String RECIPIENT_ID = "replica-inactive-admin";

  @Autowired private InactiveAccountNotificationAuditLogRepository auditLogRepository;
  @Autowired private InactiveAccountNotificationClaimWriter claimWriter;

  @AfterEach
  void deleteReplicaProofAuditRows() {
    var rows =
        auditLogRepository.findAll().stream()
            .filter(row -> ACCOUNT_ID.equals(row.getAccountId()))
            .toList();
    auditLogRepository.deleteAll(rows);
  }

  @Test
  void twoServiceInstancesDispatchOneEmailAndPersistOneAuditClaim() throws Exception {
    var userRepository = mock(UserRepository.class);
    var consultantRepository = mock(ConsultantRepository.class);
    var adminRepository = mock(AdminRepository.class);
    var askerActivityCalculator = mock(AskerActivityCalculator.class);
    var consultantActivityCalculator = mock(ConsultantActivityCalculator.class);
    var adminActivityCalculator = mock(AdminActivityCalculator.class);
    var recipientResolver = mock(InactiveAccountNotificationRecipientResolver.class);
    var mailService = mock(MailService.class);
    var inactiveUser = new User(ACCOUNT_ID, null, "inactive", "inactive@example.invalid", true);
    inactiveUser.setTenantId(1L);
    var recipient =
        Admin.builder()
            .id(RECIPIENT_ID)
            .username("replica-admin")
            .firstName("Replica")
            .lastName("Admin")
            .email("replica-admin@example.invalid")
            .type(Admin.AdminType.TENANT)
            .tenantId(1L)
            .build();
    var lastActivity = LocalDateTime.now().minusDays(400);
    when(userRepository.findAllByDeleteDateIsNull()).thenReturn(List.of(inactiveUser));
    when(consultantRepository.findByDeleteDateIsNull()).thenReturn(emptyList());
    when(adminRepository.findAll()).thenReturn(emptyList());
    when(askerActivityCalculator.lastActivity(inactiveUser)).thenReturn(Optional.of(lastActivity));
    when(recipientResolver.resolveRecipients(any())).thenReturn(List.of(recipient));
    when(mailService.sendEmailNotification(any())).thenReturn(true);
    var firstInstance =
        newServiceInstance(
            userRepository,
            consultantRepository,
            adminRepository,
            askerActivityCalculator,
            consultantActivityCalculator,
            adminActivityCalculator,
            recipientResolver,
            mailService);
    var secondInstance =
        newServiceInstance(
            userRepository,
            consultantRepository,
            adminRepository,
            askerActivityCalculator,
            consultantActivityCalculator,
            adminActivityCalculator,
            recipientResolver,
            mailService);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> scan(firstInstance, ready, start));
      var second = executor.submit(() -> scan(secondInstance, ready, start));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    assertThat(
            auditLogRepository.findAll().stream()
                .filter(row -> ACCOUNT_ID.equals(row.getAccountId())))
        .singleElement()
        .satisfies(row -> assertThat(row.isEmailDispatched()).isTrue());
    verify(mailService, times(1)).sendEmailNotification(any());
  }

  private InactiveAccountNotificationService newServiceInstance(
      UserRepository userRepository,
      ConsultantRepository consultantRepository,
      AdminRepository adminRepository,
      AskerActivityCalculator askerActivityCalculator,
      ConsultantActivityCalculator consultantActivityCalculator,
      AdminActivityCalculator adminActivityCalculator,
      InactiveAccountNotificationRecipientResolver recipientResolver,
      MailService mailService) {
    var service =
        new InactiveAccountNotificationService(
            userRepository,
            consultantRepository,
            adminRepository,
            askerActivityCalculator,
            consultantActivityCalculator,
            adminActivityCalculator,
            recipientResolver,
            claimWriter,
            mailService);
    setField(service, "inactivityThresholdDays", 365L);
    setField(service, "emailDispatchEnabled", true);
    setField(service, "appBaseUrl", "https://app.oriso.org");
    return service;
  }

  private void scan(
      InactiveAccountNotificationService service, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    service.scanAndNotifyInactiveAccounts();
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
