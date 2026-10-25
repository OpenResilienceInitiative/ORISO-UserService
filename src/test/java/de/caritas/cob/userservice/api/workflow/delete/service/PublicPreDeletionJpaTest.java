package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.AccountManager;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationRepairService;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationSagaStore;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyDeletionValidationService;
import de.caritas.cob.userservice.api.admin.service.consultant.ConsultantAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga;
import de.caritas.cob.userservice.api.admin.service.consultant.delete.ConsultantPreDeletionService;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.picture.ConsultantPictureAccess;
import de.caritas.cob.userservice.api.picture.ConsultantPictureStore;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyRevocation;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.notification.SupervisorAddedEmailNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionOwnershipService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Actual public services, original repositories and JpaTM. No native acceptance is claimed here.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PublicPreDeletionJpaTest {
  private static final String ACCOUNT = "81000000-0000-4000-8000-000000000011";
  private static final String HUMAN = "81000000-0000-4000-8000-000000000012";
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private EntityManager entities;
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private AdminRepository admins;
  @Autowired private AdminAgencyRepository adminAgencies;
  @Autowired private ConsultantAgencyRepository consultantAgencies;
  @Autowired private UserAgencyRepository userAgencies;
  @Autowired private SessionRepository sessions;
  @Autowired private ConsultantPictureRepository pictures;
  private final AtomicInteger genericAttempts = new AtomicInteger();
  private AuthenticatedUser actor;
  private IdentityClientConfig mode;
  private DeletionLifecycleService lifecycle;
  private UserService userService;
  private AdminScope scope;
  private JdbcTemplate jdbc;
  private final MembersOnlyRevocation memberships = mock(MembersOnlyRevocation.class);
  private final StatisticsService statistics = mock(StatisticsService.class);

  /** The real generic adapter is deliberately closed in C; reaching it cannot prove delivery. */
  private final class ClosedGenericDeactivator implements IdentityDeactivator {
    @Override
    public void deactivateUser(String account) {
      genericAttempts.incrementAndGet();
      throw new ScopedIdentityCommandRequiredException();
    }
  }

  @BeforeEach
  void actualJpaPublicServiceFixtures() {
    assertInstanceOf(JpaTransactionManager.class, transactions);
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    TenantContext.clear();
    jdbc = new JdbcTemplate(dataSource);
    users.findById(ACCOUNT).ifPresent(users::delete);
    consultants.findById(ACCOUNT).ifPresent(consultants::delete);
    admins.findById(ACCOUNT).ifPresent(admins::delete);
    admins.findById(HUMAN).ifPresent(admins::delete);
    actor =
        new AuthenticatedUser(
            HUMAN,
            "operator",
            Set.of("single-tenant-admin", "user-admin"),
            "fixture-human",
            Set.of("ROLE_SINGLE_TENANT_ADMIN", "ROLE_USER_ADMIN"));
    actor.setTenantId(7L);
    admins.save(
        Admin.builder()
            .id(HUMAN)
            .tenantId(7L)
            .username("operator")
            .firstName("Fixture")
            .lastName("Operator")
            .email("operator@fixture.invalid")
            .type(Admin.AdminType.TENANT)
            .build());
    mode = mock(IdentityClientConfig.class);
    when(mode.isFiveActorMode()).thenReturn(true);
    lifecycle = new DeletionLifecycleService();
    org.springframework.test.util.ReflectionTestUtils.setField(
        lifecycle, "globalReadOnlyHours", 48L);
    org.springframework.test.util.ReflectionTestUtils.setField(
        lifecycle, "tenantOverrideConfig", "");
    userService =
        new UserService(
            users,
            mock(UserMobileTokenRepository.class),
            mock(AuditingHandler.class),
            mock(AccountInactivityEnrollmentService.class));
    scope =
        new AdminScope(
            actor,
            admins,
            adminAgencies,
            consultants,
            consultantAgencies,
            mock(AgencyService.class),
            users,
            sessions,
            userAgencies);
    org.springframework.test.util.ReflectionTestUtils.setField(scope, "multitenancyEnabled", true);
  }

  @AfterEach
  void releaseTenant() {
    TenantContext.clear();
  }

  @Test
  void selfAskerStartUsesOwnedAcknowledgementBeforePersistingTheReadOnlyWindow() {
    asker();
    actor.setUserId(ACCOUNT);
    actor.setUsername("pre-deletion");
    actor.setRoles(Set.of("user"));
    actor.setGrantedAuthorities(Set.of("ROLE_USER"));
    UserAccountService service = selfService();

    assertDoesNotThrow(service::deactivateAndFlagUserAccountForDeletion);

    assertEquals(0, genericAttempts.get(), "C must never fall back to the generic identity port");
    User saved = users.findById(ACCOUNT).orElseThrow();
    assertNotNull(saved.getDeleteDate());
    assertEquals(DeletionLifecycleState.READ_ONLY_SAFEGUARD, saved.getDeletionLifecycleState());
    assertNotNull(saved.getDeletionReadOnlyUntil());
    verify(statistics, times(1)).fireEvent(any());
  }

  @Test
  void adminAskerStartUsesActualScopeAndOwnedAcknowledgementBeforeSql() {
    asker();
    var service =
        new AskerUserAdminFacade(
            new ClosedGenericDeactivator(),
            mock(IdentityReactivationSagaStore.class),
            mock(IdentityReactivationRepairService.class),
            userService,
            new UsernameTranscoder(),
            lifecycle,
            scope);
    service.setIdentityMode(mode);

    assertDoesNotThrow(() -> service.markAskerForDeletion(ACCOUNT));

    assertEquals(0, genericAttempts.get(), "C must never fall back to the generic identity port");
    User saved = users.findById(ACCOUNT).orElseThrow();
    assertNotNull(saved.getDeleteDate());
    assertEquals(DeletionLifecycleState.READ_ONLY_SAFEGUARD, saved.getDeletionLifecycleState());
    assertNotNull(saved.getDeletionReadOnlyUntil());
  }

  @Test
  void adminConsultantStartUsesOwnedAcknowledgementBeforeTheOriginalPresteps() {
    consultant();
    ConsultantAdminService service = consultantService();

    assertDoesNotThrow(() -> service.markConsultantForDeletion(ACCOUNT, false));

    assertEquals(0, genericAttempts.get(), "C must never fall back to the generic identity port");
    Consultant saved = consultants.findById(ACCOUNT).orElseThrow();
    assertNotNull(saved.getDeleteDate());
    assertEquals(DeletionLifecycleState.READ_ONLY_SAFEGUARD, saved.getDeletionLifecycleState());
    assertNotNull(saved.getDeletionReadOnlyUntil());
  }

  @Test
  void consultantUnknownNativeOutcomeCannotBeginMembershipPictureOrSqlPresteps() {
    consultant();
    ConsultantAdminService service = consultantService();

    assertThrows(
        ScopedIdentityCommandRequiredException.class,
        () -> service.markConsultantForDeletion(ACCOUNT, false));

    verifyNoInteractions(memberships);
    Consultant saved = consultants.findById(ACCOUNT).orElseThrow();
    assertNull(saved.getDeleteDate());
    assertEquals(DeletionLifecycleState.ACTIVE, saved.getDeletionLifecycleState());
  }

  private void asker() {
    User user =
        new User(
            ACCOUNT,
            null,
            new UsernameTranscoder().encodeUsername("pre-deletion"),
            "asker@fixture.invalid",
            false);
    user.setTenantId(7L);
    user.setLanguageCode(LanguageCode.en);
    user.setEncourage2fa(false);
    user.setDeletionLifecycleState(DeletionLifecycleState.ACTIVE);
    users.save(user);
  }

  private void consultant() {
    Consultant consultant =
        Consultant.builder()
            .id(ACCOUNT)
            .tenantId(7L)
            .username(new UsernameTranscoder().encodeUsername("pre-deletion"))
            .firstName("Fixture")
            .lastName("Consultant")
            .email("consultant@fixture.invalid")
            .languageCode(LanguageCode.en)
            .encourage2fa(false)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(true)
            .notifyNewChatMessageFromAdviceSeeker(true)
            .createDate(LocalDateTime.now(ZoneOffset.UTC).minusYears(1))
            .status(de.caritas.cob.userservice.api.model.ConsultantStatus.CREATED)
            .deletionLifecycleState(DeletionLifecycleState.ACTIVE)
            .build();
    consultants.save(consultant);
  }

  private UserAccountService selfService() {
    return new UserAccountService(
        userService,
        new ConsultantService(consultants, mock(ConsultantMobileTokenRepository.class)),
        mock(AppointmentService.class),
        actor,
        mock(IdentityClient.class),
        mock(IdentityEmailAddressUpdater.class),
        new ClosedGenericDeactivator(),
        mock(UserHelper.class),
        mode,
        statistics,
        mock(SupervisorAddedEmailNotificationService.class),
        lifecycle);
  }

  private ConsultantAdminService consultantService() {
    var store =
        new ConsultantPictureStore(
            consultants,
            pictures,
            mock(ConsultantPictureAccess.class),
            entities,
            mock(
                de.caritas.cob.userservice.api.service.accountinvite.onboarding
                    .CounsellorOnboardingService.class));
    var presteps =
        new ConsultantPreDeletionService(
            new ConsultantAgencyDeletionValidationService(
                consultantAgencies, mock(AgencyService.class), sessions),
            sessions,
            new ClosedGenericDeactivator());
    var target =
        new ConsultantAdminService(
            consultants,
            store,
            mock(CreateConsultantSaga.class),
            mock(ConsultantUpdateService.class),
            presteps,
            sessions,
            mock(MembersOnlyChatAccess.class),
            memberships,
            mock(SessionOwnershipService.class),
            mock(CaseHandoverRequestRepository.class),
            mock(SessionSupervisorRepository.class),
            actor,
            mock(AccountManager.class),
            mock(AppointmentService.class),
            lifecycle,
            mock(ConsultantTopicRepository.class),
            mock(TopicService.class));
    // The initial implementation owns one real transaction around its public legacy method.
    // C will need an outer transaction-free entry and explicit owned local callbacks instead.
    var interceptor = new TransactionInterceptor();
    interceptor.setTransactionManager(transactions);
    interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    var proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(interceptor);
    return (ConsultantAdminService) proxy.getProxy();
  }
}
