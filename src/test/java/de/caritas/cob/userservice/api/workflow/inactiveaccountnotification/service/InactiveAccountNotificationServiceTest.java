package de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.service;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.InactiveAccountNotificationAuditLogRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.helper.MailService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InactiveAccountNotificationServiceTest {

  @InjectMocks private InactiveAccountNotificationService service;

  @Mock private UserRepository userRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private AdminRepository adminRepository;
  @Mock private AskerActivityCalculator askerActivityCalculator;
  @Mock private ConsultantActivityCalculator consultantActivityCalculator;
  @Mock private AdminActivityCalculator adminActivityCalculator;
  @Mock private InactiveAccountNotificationRecipientResolver recipientResolver;
  @Mock private InactiveAccountNotificationAuditLogRepository auditLogRepository;
  @Mock private MailService mailService;

  private Admin recipientAdmin;

  @BeforeEach
  void setUp() {
    setField(service, "inactivityThresholdDays", 365L);
    setField(service, "emailDispatchEnabled", false);
    setField(service, "appBaseUrl", "https://app.oriso-dev.site");

    recipientAdmin =
        Admin.builder()
            .id("admin-1")
            .username("admin1")
            .firstName("Tenant")
            .lastName("Admin")
            .email("admin@example.com")
            .type(Admin.AdminType.TENANT)
            .tenantId(1L)
            .build();

    when(consultantRepository.findByDeleteDateIsNull()).thenReturn(emptyList());
    when(adminRepository.findAll()).thenReturn(emptyList());
    when(recipientResolver.resolveRecipients(any())).thenReturn(singletonList(recipientAdmin));
    when(auditLogRepository.existsByNotificationFingerprint(any())).thenReturn(false);
  }

  @Test
  void scanAndNotifyInactiveAccounts_shouldTriggerOnlyForBeyondThresholdBoundary() {
    User user364 = new User("user-364", null, "user364", "u364@example.com", true);
    user364.setTenantId(1L);
    User user366 = new User("user-366", null, "user366", "u366@example.com", true);
    user366.setTenantId(1L);

    LocalDateTime now = LocalDateTime.now();
    when(userRepository.findAllByDeleteDateIsNull()).thenReturn(Arrays.asList(user364, user366));
    when(askerActivityCalculator.lastActivity(user364)).thenReturn(Optional.of(now.minusDays(364)));
    when(askerActivityCalculator.lastActivity(user366)).thenReturn(Optional.of(now.minusDays(366)));

    service.scanAndNotifyInactiveAccounts();

    verify(auditLogRepository).save(any());
    verify(mailService, never()).sendEmailNotification(any());
  }

  @Test
  void scanAndNotifyInactiveAccounts_shouldKeepAskerIndependentFromConsultantActivity() {
    User inactiveAsker = new User("asker-inactive", null, "asker", "asker@example.com", true);
    inactiveAsker.setTenantId(1L);
    Consultant activeConsultant =
        Consultant.builder()
            .id("consultant-active")
            .rocketChatId("rc-consultant-active")
            .username("consultant")
            .firstName("Con")
            .lastName("Sultant")
            .email("consultant@example.com")
            .languageFormal(true)
            .build();
    activeConsultant.setTenantId(1L);

    LocalDateTime now = LocalDateTime.now();
    when(userRepository.findAllByDeleteDateIsNull()).thenReturn(singletonList(inactiveAsker));
    when(consultantRepository.findByDeleteDateIsNull()).thenReturn(singletonList(activeConsultant));
    when(askerActivityCalculator.lastActivity(inactiveAsker))
        .thenReturn(Optional.of(now.minusDays(400)));
    when(consultantActivityCalculator.lastActivity(activeConsultant))
        .thenReturn(Optional.of(now.minusDays(10)));

    service.scanAndNotifyInactiveAccounts();

    verify(auditLogRepository).save(any());
  }
}
