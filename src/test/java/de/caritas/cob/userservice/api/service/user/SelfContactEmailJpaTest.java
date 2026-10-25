package de.caritas.cob.userservice.api.service.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.notification.SupervisorAddedEmailNotificationService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SelfContactEmailJpaTest {
  private static final String ASKER = "82000000-0000-4000-8000-000000000011";
  private static final String CONSULTANT = "82000000-0000-4000-8000-000000000012";
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private PlatformTransactionManager transactions;
  private AppointmentService appointments;
  private SupervisorAddedEmailNotificationService notifications;
  private IdentityEmailAddressUpdater genericEmail;
  private IdentityClientConfig mode;
  private UserHelper helper;

  @BeforeEach
  void actualOwningJpaAndClosedGenericIdentity() {
    assertInstanceOf(JpaTransactionManager.class, transactions);
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    TenantContext.clear();
    TenantContext.setCurrentTenant(7L);
    for (String id : List.of(ASKER, CONSULTANT)) {
      users.findById(id).ifPresent(users::delete);
      consultants.findById(id).ifPresent(consultants::delete);
    }
    mode = mock(IdentityClientConfig.class);
    when(mode.isFiveActorMode()).thenReturn(true);
    when(mode.getEmailDummySuffix()).thenReturn("@dummy.invalid");
    genericEmail = mock(IdentityEmailAddressUpdater.class);
    doThrow(new ScopedIdentityCommandRequiredException())
        .when(genericEmail).updateCurrentUserEmail(anyString());
    doThrow(new ScopedIdentityCommandRequiredException())
        .when(genericEmail).deleteCurrentUserEmail();
    appointments = mock(AppointmentService.class);
    notifications = mock(SupervisorAddedEmailNotificationService.class);
    helper = mock(UserHelper.class);
    when(helper.getDummyEmail(CONSULTANT)).thenReturn(CONSULTANT + "@dummy.invalid");
  }

  @AfterEach
  void releaseTenant() {
    TenantContext.clear();
  }

  @Test
  void anAskerCanChangeContactEmailThroughTheActualPublicService() {
    var user = new User(ASKER, null, encodedUsername(), "old@oriso.org", false);
    user.setTenantId(7L);
    user.setLanguageCode(LanguageCode.en);
    user.setEncourage2fa(false);
    user.setDeletionLifecycleState(DeletionLifecycleState.ACTIVE);
    users.save(user);
    var service = service(ASKER, "user");

    assertDoesNotThrow(
        () -> service.changeUserAccountEmailAddress(Optional.of("changed@oriso.org")));

    assertEquals("changed@oriso.org", users.findById(ASKER).orElseThrow().getEmail());
    verifyNoInteractions(genericEmail);
    verify(appointments).updateAskerEmail(ASKER, "changed@oriso.org");
    verify(notifications)
        .notifyEmailAddressChanged(
            anyString(), eq("changed@oriso.org"), eq(7L), any(), anyString(), eq(LanguageCode.en));
  }

  @Test
  void aConsultantCanRemoveContactEmailWithoutRemovingTheAccount() {
    consultants.save(
        Consultant.builder()
            .id(CONSULTANT)
            .tenantId(7L)
            .username(encodedUsername())
            .firstName("Contact")
            .lastName("Consultant")
            .email("consultant@oriso.org")
            .languageCode(LanguageCode.en)
            .encourage2fa(false)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(true)
            .notifyNewChatMessageFromAdviceSeeker(true)
            .createDate(LocalDateTime.now(ZoneOffset.UTC).minusYears(1))
            .status(ConsultantStatus.CREATED)
            .deletionLifecycleState(DeletionLifecycleState.ACTIVE)
            .build());
    var service = service(CONSULTANT, "consultant");

    assertDoesNotThrow(() -> service.changeUserAccountEmailAddress(Optional.empty()));

    var saved = consultants.findById(CONSULTANT).orElseThrow();
    assertEquals(CONSULTANT + "@dummy.invalid", saved.getEmail());
    assertNull(saved.getDeleteDate());
    assertEquals(DeletionLifecycleState.ACTIVE, saved.getDeletionLifecycleState());
    verifyNoInteractions(genericEmail, appointments, notifications);
  }

  private UserAccountService service(String id, String role) {
    var actor = new AuthenticatedUser(id, encodedUsername(), Set.of(role), "fixture-human", Set.of());
    actor.setTenantId(7L);
    var userService =
        new UserService(
            users,
            mock(UserMobileTokenRepository.class),
            mock(AuditingHandler.class),
            mock(AccountInactivityEnrollmentService.class));
    return new UserAccountService(
        userService,
        new ConsultantService(consultants, mock(ConsultantMobileTokenRepository.class)),
        appointments,
        actor,
        mock(IdentityClient.class),
        genericEmail,
        mock(IdentityDeactivator.class),
        helper,
        mode,
        mock(StatisticsService.class),
        notifications,
        new DeletionLifecycleService());
  }

  private String encodedUsername() {
    return new UsernameTranscoder().encodeUsername("contact-email");
  }
}
