package de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.service;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.InactiveAccountNotificationAuditLog;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.InactiveAccountNotificationAuditLogRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailsDTO;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
    setField(service, "appBaseUrl", "https://app.oriso.org");

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

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-10
  // ---------------------------------------------------------------------------

  @Test
  void scanAndNotifyInactiveAccounts_Should_notifyInactiveAdmin() {
    Admin inactiveAdmin =
        Admin.builder()
            .id("admin-target")
            .username("target")
            .firstName("Target")
            .lastName("Admin")
            .email("target@example.com")
            .type(Admin.AdminType.TENANT)
            .tenantId(1L)
            .build();
    LocalDateTime now = LocalDateTime.now();
    when(userRepository.findAllByDeleteDateIsNull()).thenReturn(emptyList());
    when(adminRepository.findAll()).thenReturn(singletonList(inactiveAdmin));
    when(adminActivityCalculator.lastActivity(inactiveAdmin))
        .thenReturn(Optional.of(now.minusDays(400)));

    service.scanAndNotifyInactiveAccounts();

    ArgumentCaptor<InactiveAccountNotificationAuditLog> captor =
        ArgumentCaptor.forClass(InactiveAccountNotificationAuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    assertThat(captor.getValue().getAccountId()).isEqualTo("admin-target");
  }

  @Test
  void scanAndNotifyInactiveAccounts_Should_notNotify_When_lastActivityAbsent() {
    User user = new User("user-1", null, "user1", "u1@example.com", true);
    user.setTenantId(1L);
    when(userRepository.findAllByDeleteDateIsNull()).thenReturn(singletonList(user));
    when(askerActivityCalculator.lastActivity(user)).thenReturn(Optional.empty());

    service.scanAndNotifyInactiveAccounts();

    verify(auditLogRepository, never()).save(any());
  }

  @Test
  void scanAndNotifyInactiveAccounts_Should_notNotify_When_lastActivityJustAfterCutoff() {
    User user = new User("user-1", null, "user1", "u1@example.com", true);
    user.setTenantId(1L);
    LocalDateTime now = LocalDateTime.now();
    when(userRepository.findAllByDeleteDateIsNull()).thenReturn(singletonList(user));
    // isInactive uses isBefore(cutoff) strictly. The production code computes its own
    // independent `now` internally, so asserting exact equality with a `now` captured here
    // is a race (whichever `now()` call resolves a few nanoseconds later wins the boundary).
    // 1 second after our own `now - 365d` is unambiguously NOT before the production cutoff,
    // regardless of that clock skew.
    when(askerActivityCalculator.lastActivity(user))
        .thenReturn(Optional.of(now.minusDays(365).plusSeconds(1)));

    service.scanAndNotifyInactiveAccounts();

    verify(auditLogRepository, never()).save(any());
  }

  @Test
  void scanAndNotifyInactiveAccounts_Should_skipDuplicateFingerprint() {
    User user = new User("user-1", null, "user1", "u1@example.com", true);
    user.setTenantId(1L);
    LocalDateTime now = LocalDateTime.now();
    when(userRepository.findAllByDeleteDateIsNull()).thenReturn(singletonList(user));
    when(askerActivityCalculator.lastActivity(user)).thenReturn(Optional.of(now.minusDays(400)));
    when(auditLogRepository.existsByNotificationFingerprint(any())).thenReturn(true);

    service.scanAndNotifyInactiveAccounts();

    verify(auditLogRepository, never()).save(any());
  }

  @Test
  void scanAndNotifyInactiveAccounts_Should_notifyAllResolvedRecipients() {
    Admin secondAdmin =
        Admin.builder()
            .id("admin-2")
            .username("admin2")
            .firstName("Second")
            .lastName("Admin")
            .email("admin2@example.com")
            .type(Admin.AdminType.TENANT)
            .tenantId(1L)
            .build();
    User user = new User("user-1", null, "user1", "u1@example.com", true);
    user.setTenantId(1L);
    LocalDateTime now = LocalDateTime.now();
    when(userRepository.findAllByDeleteDateIsNull()).thenReturn(singletonList(user));
    when(askerActivityCalculator.lastActivity(user)).thenReturn(Optional.of(now.minusDays(400)));
    when(recipientResolver.resolveRecipients(any()))
        .thenReturn(Arrays.asList(recipientAdmin, secondAdmin));

    service.scanAndNotifyInactiveAccounts();

    verify(auditLogRepository, org.mockito.Mockito.times(2)).save(any());
  }

  @Test
  void scanAndNotifyInactiveAccounts_Should_dispatchEmail_When_emailDispatchEnabled() {
    setField(service, "emailDispatchEnabled", true);
    User user = new User("user-1", null, "user1", "u1@example.com", true);
    user.setTenantId(1L);
    LocalDateTime now = LocalDateTime.now();
    when(userRepository.findAllByDeleteDateIsNull()).thenReturn(singletonList(user));
    when(askerActivityCalculator.lastActivity(user)).thenReturn(Optional.of(now.minusDays(400)));

    service.scanAndNotifyInactiveAccounts();

    ArgumentCaptor<MailsDTO> mailCaptor = ArgumentCaptor.forClass(MailsDTO.class);
    verify(mailService).sendEmailNotification(mailCaptor.capture());
    assertThat(mailCaptor.getValue().getMails()).hasSize(1);
    assertThat(mailCaptor.getValue().getMails().get(0).getEmail())
        .isEqualTo(recipientAdmin.getEmail());
    ArgumentCaptor<InactiveAccountNotificationAuditLog> auditCaptor =
        ArgumentCaptor.forClass(InactiveAccountNotificationAuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().isEmailDispatched()).isTrue();
  }

  @Test
  void scanAndNotifyInactiveAccounts_Should_notDispatchEmail_When_disabled() {
    User user = new User("user-1", null, "user1", "u1@example.com", true);
    user.setTenantId(1L);
    LocalDateTime now = LocalDateTime.now();
    when(userRepository.findAllByDeleteDateIsNull()).thenReturn(singletonList(user));
    when(askerActivityCalculator.lastActivity(user)).thenReturn(Optional.of(now.minusDays(400)));

    service.scanAndNotifyInactiveAccounts();

    verify(mailService, never()).sendEmailNotification(any());
    ArgumentCaptor<InactiveAccountNotificationAuditLog> auditCaptor =
        ArgumentCaptor.forClass(InactiveAccountNotificationAuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().isEmailDispatched()).isFalse();
  }
}
