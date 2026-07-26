package de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.service;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
// LENIENT is required: @BeforeEach registers default stubs for recipientResolver and
// claimWriter that are only exercised when inactive accounts are actually found.
// Tests that verify "no notification" paths (e.g. activity below threshold) do not reach
// those call sites, so Mockito would otherwise report UnnecessaryStubbingException.
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
  @Mock private InactiveAccountNotificationClaimWriter claimWriter;
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
    when(claimWriter.claim(any()))
        .thenAnswer(
            invocation -> {
              InactiveAccountNotificationAuditLog auditLog = invocation.getArgument(0);
              auditLog.setId(1L);
              return auditLog;
            });
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

    verify(claimWriter).claim(any());
    verify(mailService, never()).sendEmailNotification(any());
  }

  @Test
  void scanAndNotifyInactiveAccounts_shouldKeepAskerIndependentFromConsultantActivity() {
    User inactiveAsker = new User("asker-inactive", null, "asker", "asker@example.com", true);
    inactiveAsker.setTenantId(1L);
    Consultant activeConsultant =
        Consultant.builder()
            .id("consultant-active")
            .matrixUserId("rc-consultant-active")
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

    verify(claimWriter).claim(any());
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
    verify(claimWriter).claim(captor.capture());
    assertThat(captor.getValue().getAccountId()).isEqualTo("admin-target");
  }

  @Test
  void scanAndNotifyInactiveAccounts_Should_notNotify_When_lastActivityAbsent() {
    User user = new User("user-1", null, "user1", "u1@example.com", true);
    user.setTenantId(1L);
    when(userRepository.findAllByDeleteDateIsNull()).thenReturn(singletonList(user));
    when(askerActivityCalculator.lastActivity(user)).thenReturn(Optional.empty());

    service.scanAndNotifyInactiveAccounts();

    verify(claimWriter, never()).claim(any());
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

    verify(claimWriter, never()).claim(any());
  }

  @Test
  void scanAndNotifyInactiveAccounts_Should_skipDuplicateFingerprint() {
    User user = new User("user-1", null, "user1", "u1@example.com", true);
    user.setTenantId(1L);
    LocalDateTime now = LocalDateTime.now();
    when(userRepository.findAllByDeleteDateIsNull()).thenReturn(singletonList(user));
    when(askerActivityCalculator.lastActivity(user)).thenReturn(Optional.of(now.minusDays(400)));
    doThrow(new DataIntegrityViolationException("duplicate fingerprint"))
        .when(claimWriter)
        .claim(any());

    service.scanAndNotifyInactiveAccounts();

    verify(claimWriter).claim(any());
    verify(mailService, never()).sendEmailNotification(any());
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

    verify(claimWriter, org.mockito.Mockito.times(2)).claim(any());
  }

  @Test
  void scanAndNotifyInactiveAccounts_Should_dispatchEmail_When_emailDispatchEnabled() {
    setField(service, "emailDispatchEnabled", true);
    when(mailService.sendEmailNotification(any())).thenReturn(true);
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
    verify(claimWriter).claim(auditCaptor.capture());
    assertThat(auditCaptor.getValue().isEmailDispatched()).isFalse();
    verify(claimWriter).markEmailDispatched(1L);
  }

  @Test
  void scanAndNotifyInactiveAccounts_Should_keepAuditUndispatched_When_mailTransportRejects() {
    setField(service, "emailDispatchEnabled", true);
    when(mailService.sendEmailNotification(any())).thenReturn(false);
    User user = new User("user-1", null, "user1", "u1@example.com", true);
    user.setTenantId(1L);
    LocalDateTime now = LocalDateTime.now();
    when(userRepository.findAllByDeleteDateIsNull()).thenReturn(singletonList(user));
    when(askerActivityCalculator.lastActivity(user)).thenReturn(Optional.of(now.minusDays(400)));

    service.scanAndNotifyInactiveAccounts();

    verify(mailService).sendEmailNotification(any());
    verify(claimWriter).claim(any());
    verify(claimWriter, never()).markEmailDispatched(any());
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
    verify(claimWriter).claim(auditCaptor.capture());
    assertThat(auditCaptor.getValue().isEmailDispatched()).isFalse();
    verify(claimWriter, never()).markEmailDispatched(any());
  }
}
