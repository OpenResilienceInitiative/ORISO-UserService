package de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.service;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.InactiveAccountNotificationAuditLog;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.InactiveAccountNotificationAuditLogRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.model.InactiveAccountRole;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailsDTO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class InactiveAccountNotificationServiceReplicaIT {

  private static final String ACCOUNT_ID = "replica-inactive-asker";
  private static final String RECIPIENT_ID = "replica-inactive-admin";

  @Autowired private InactiveAccountNotificationAuditLogRepository auditLogRepository;
  @Autowired private InactiveAccountNotificationClaimWriter claimWriter;

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    String mariaDbUrl = System.getenv("LIQUIBASE_IT_DB_URL");
    if (mariaDbUrl == null || mariaDbUrl.isBlank()) {
      return;
    }
    registry.add("spring.datasource.url", () -> mariaDbUrl);
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add(
        "spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MariaDBDialect");
    registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    registry.add("spring.sql.init.mode", () -> "never");
  }

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
    when(mailService.sendEmailNotification(any(), anyString())).thenReturn(true);
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
        .satisfies(
            row -> {
              assertThat(row.isEmailDispatched()).isTrue();
              assertThat(row.getEmailDispatchAttemptCount()).isEqualTo(1);
            });
    verify(mailService, times(1)).sendEmailNotification(any(), anyString());
  }

  @Test
  void acceptedMailIsRecoveredAfterCrashWithSameOpaqueIdempotencyKey() {
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
    var attemptedKeys = new CopyOnWriteArrayList<String>();
    var attemptedMails = new CopyOnWriteArrayList<MailsDTO>();
    var physicalDeliveries = ConcurrentHashMap.<String>newKeySet();
    when(mailService.sendEmailNotification(any(), anyString()))
        .thenAnswer(
            invocation -> {
              String idempotencyKey = invocation.getArgument(1);
              attemptedMails.add(invocation.getArgument(0));
              attemptedKeys.add(idempotencyKey);
              physicalDeliveries.add(idempotencyKey);
              return true;
            });
    var crashingWriter =
        new InactiveAccountNotificationClaimWriter(auditLogRepository) {
          @Override
          public InactiveAccountNotificationAuditLog claim(
              InactiveAccountNotificationAuditLog auditLog) {
            return claimWriter.claim(auditLog);
          }

          @Override
          public Optional<InactiveAccountNotificationAuditLog> findByFingerprint(
              String fingerprint) {
            return claimWriter.findByFingerprint(fingerprint);
          }

          @Override
          public Optional<Integer> tryStartEmailDispatch(
              Long auditLogId, LocalDateTime now, Duration recoveryAfter) {
            return claimWriter.tryStartEmailDispatch(auditLogId, now, recoveryAfter);
          }

          @Override
          public void markEmailDispatched(Long auditLogId) {
            throw new SimulatedProcessCrash();
          }
        };
    var crashingInstance =
        newServiceInstance(
            userRepository,
            consultantRepository,
            adminRepository,
            askerActivityCalculator,
            consultantActivityCalculator,
            adminActivityCalculator,
            recipientResolver,
            crashingWriter,
            mailService,
            true,
            Duration.ZERO);
    var recoveredInstance =
        newServiceInstance(
            userRepository,
            consultantRepository,
            adminRepository,
            askerActivityCalculator,
            consultantActivityCalculator,
            adminActivityCalculator,
            recipientResolver,
            claimWriter,
            mailService,
            true,
            Duration.ZERO);

    assertThatThrownBy(crashingInstance::scanAndNotifyInactiveAccounts)
        .isInstanceOf(SimulatedProcessCrash.class);
    assertThat(
            auditLogRepository.findAll().stream()
                .filter(row -> ACCOUNT_ID.equals(row.getAccountId())))
        .singleElement()
        .satisfies(row -> assertThat(row.isEmailDispatched()).isFalse());

    recoveredInstance.scanAndNotifyInactiveAccounts();

    assertThat(
            auditLogRepository.findAll().stream()
                .filter(row -> ACCOUNT_ID.equals(row.getAccountId())))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.isEmailDispatched()).isTrue();
              assertThat(row.getEmailDispatchAttemptCount()).isEqualTo(2);
            });
    assertThat(attemptedKeys)
        .hasSize(2)
        .allMatch(key -> key.matches("inactive-account-[0-9a-f]{64}"))
        .containsOnly(attemptedKeys.getFirst());
    assertThat(attemptedMails).hasSize(2);
    assertThat(attemptedMails.get(1))
        .usingRecursiveComparison()
        .isEqualTo(attemptedMails.getFirst());
    assertThat(physicalDeliveries).hasSize(1);
  }

  @Test
  void dispatchAttemptCannotBeReclaimedBeforeRecoveryInterval() {
    LocalDateTime firstAttemptAt = LocalDateTime.of(2026, 7, 26, 8, 0);
    var auditLog =
        claimWriter.claim(
            InactiveAccountNotificationAuditLog.builder()
                .notificationFingerprint("bounded-recovery-window")
                .accountRole(InactiveAccountRole.ASKER)
                .accountId(ACCOUNT_ID)
                .thresholdDays(365)
                .recipientAdminId(RECIPIENT_ID)
                .recipientEmail("replica-admin@example.invalid")
                .emailDispatched(false)
                .createDate(firstAttemptAt)
                .build());
    Duration recoveryAfter = Duration.ofMinutes(5);

    assertThat(claimWriter.tryStartEmailDispatch(auditLog.getId(), firstAttemptAt, recoveryAfter))
        .contains(1);
    assertThat(
            claimWriter.tryStartEmailDispatch(
                auditLog.getId(), firstAttemptAt.plusMinutes(4).plusSeconds(59), recoveryAfter))
        .isEmpty();
    assertThat(
            claimWriter.tryStartEmailDispatch(
                auditLog.getId(), firstAttemptAt.plusMinutes(5), recoveryAfter))
        .contains(2);
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
    return newServiceInstance(
        userRepository,
        consultantRepository,
        adminRepository,
        askerActivityCalculator,
        consultantActivityCalculator,
        adminActivityCalculator,
        recipientResolver,
        claimWriter,
        mailService,
        false,
        Duration.ofMinutes(5));
  }

  private InactiveAccountNotificationService newServiceInstance(
      UserRepository userRepository,
      ConsultantRepository consultantRepository,
      AdminRepository adminRepository,
      AskerActivityCalculator askerActivityCalculator,
      ConsultantActivityCalculator consultantActivityCalculator,
      AdminActivityCalculator adminActivityCalculator,
      InactiveAccountNotificationRecipientResolver recipientResolver,
      InactiveAccountNotificationClaimWriter notificationClaimWriter,
      MailService mailService,
      boolean idempotentRecoveryEnabled,
      Duration recoveryAfter) {
    var service =
        new InactiveAccountNotificationService(
            userRepository,
            consultantRepository,
            adminRepository,
            askerActivityCalculator,
            consultantActivityCalculator,
            adminActivityCalculator,
            recipientResolver,
            notificationClaimWriter,
            mailService);
    setField(service, "inactivityThresholdDays", 365L);
    setField(service, "emailDispatchEnabled", true);
    setField(service, "appBaseUrl", "https://app.oriso.org");
    setField(service, "idempotentRecoveryEnabled", idempotentRecoveryEnabled);
    setField(service, "emailDispatchRecoveryAfter", recoveryAfter);
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

  private static final class SimulatedProcessCrash extends RuntimeException {}
}
