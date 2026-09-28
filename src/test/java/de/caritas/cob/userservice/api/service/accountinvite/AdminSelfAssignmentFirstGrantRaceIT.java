package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.create.GrantConsultantIdentityService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityEmailAddressUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.IdentityLocaleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.accountinvite.AdminSelfAssignmentService.SelfAssignmentCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AdminSelfAssignmentService.SelfAssignmentRole;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.ExistingAgencyClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.ExistingAgencyClient.ExistingAgency;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two first-time self-assignments of the same admin at once, through the real consultant-identity
 * grant. Keycloak and Matrix are fakes that remember what the grant did to them.
 */
@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  AdminSelfAssignmentService.class,
  GrantConsultantIdentityService.class,
  ConsultantService.class,
  AccountInviteAccessPolicy.class,
  ConsultantTopicAgencyCompatibilityValidator.class,
  de.caritas.cob.userservice.api.admin.service.admin.AdminScope.class,
  AdminSelfAssignmentFirstGrantRaceIT.CallerConfig.class
})
@WithTenant(AdminSelfAssignmentFirstGrantRaceIT.OWN_TENANT)
class AdminSelfAssignmentFirstGrantRaceIT {

  static final long OWN_TENANT = 1L;
  private static final long AGENCY = 2L;
  private static final long TOPIC = 21L;
  private static final String ADMIN_ID = "5e1f0000-1026-4a4a-9d1e-0000000000ra";
  private static final String CHAT_ACCOUNT = "@race-admin:example.org";
  private static final String CONSULTANT_ROLE = UserRole.CONSULTANT.getValue();

  @TestConfiguration
  static class CallerConfig {
    @Bean
    AuthenticatedUser authenticatedUser() {
      return new AuthenticatedUser();
    }
  }

  @Autowired private AdminSelfAssignmentService service;
  @Autowired private AdminRepository adminRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;
  @Autowired private AuthenticatedUser caller;

  @MockitoBean private ExistingAgencyClient existingAgencyClient;
  @MockitoBean private AgencyService agencyService;

  // One Keycloak mock carrying every port, as the real KeycloakService does.
  @MockitoBean(
      extraInterfaces = {
        IdentityAccountRemover.class,
        IdentityAuthentication.class,
        IdentityDeactivator.class,
        IdentityDummyEmailUpdater.class,
        IdentityEmailAddressUpdater.class,
        IdentityEmailOwnerLookup.class,
        IdentityLocaleLookup.class,
        IdentityPasswordUpdater.class,
        IdentityProfileLookup.class,
        IdentityProfileUpdater.class,
        IdentityRoleLookup.class,
        IdentityRoleUpdater.class,
        IdentitySecondFactor.class,
        IdentityUsernameAvailability.class
      })
  private IdentityClient identityClient;

  @MockitoBean private MatrixUserClient matrixUserClient;
  @MockitoBean private UserHelper userHelper;
  @MockitoBean private ChatRecoveryEnrollmentPolicyService chatRecoveryEnrollmentPolicyService;
  @MockitoBean private ConsultantDisplayNameResolver consultantDisplayNameResolver;

  @MockitoBean
  private ConsultantAgencyRelationCreatorService consultantAgencyRelationCreatorService;

  /** The admin's realm roles as Keycloak would hold them. */
  private final Set<String> keycloakRoles = ConcurrentHashMap.newKeySet();

  @BeforeEach
  void givenAnAdminWhoDoesNotCounselYet() throws Exception {
    var agency = new AgencyDTO().id(AGENCY).tenantId(OWN_TENANT).topicIds(List.of(TOPIC));
    when(agencyService.getAgencyWithoutCaching(AGENCY)).thenReturn(agency);
    when(agencyService.getAgenciesWithoutCaching(List.of(AGENCY))).thenReturn(List.of(agency));
    when(existingAgencyClient.find(AGENCY))
        .thenReturn(Optional.of(new ExistingAgency(AGENCY, OWN_TENANT, false, List.of(TOPIC))));
    when(chatRecoveryEnrollmentPolicyService.forNewConsultant(any()))
        .thenReturn(new RecoveryPolicySnapshot("RECOVERY_KEY", 1L));
    when(userHelper.getRandomPassword()).thenReturn("random-matrix-password");
    doAnswer(call -> keycloakRoles.addAll(call.<Collection<String>>getArgument(1)))
        .when((IdentityRoleUpdater) identityClient)
        .ensureRoles(eq(ADMIN_ID), anyCollection());
    doAnswer(call -> keycloakRoles.remove(call.<String>getArgument(1)))
        .when(identityClient)
        .removeRoleIfPresent(eq(ADMIN_ID), anyString());
    when(matrixUserClient.createUserId(anyString(), anyString(), any()))
        .thenAnswer(
            call -> {
              // A real remote call: the window in which the second click used to slip through.
              Thread.sleep(300);
              return CHAT_ACCOUNT;
            });
    doAnswer(
            call -> {
              var now = LocalDateTime.now();
              consultantAgencyRepository.save(
                  ConsultantAgency.builder()
                      .consultant(consultantRepository.findById(ADMIN_ID).orElseThrow())
                      .agencyId(AGENCY)
                      .tenantId(OWN_TENANT)
                      .createDate(now)
                      .updateDate(now)
                      .build());
              return null;
            })
        .when(consultantAgencyRelationCreatorService)
        .createNewConsultantAgency(eq(ADMIN_ID), any());
    adminRepository.save(
        Admin.builder()
            .id(ADMIN_ID)
            .type(Admin.AdminType.TENANT)
            .tenantId(OWN_TENANT)
            .username("race-tenant-admin")
            .firstName("Ada")
            .lastName("Lovelace")
            .email("race-admin@example.org")
            .createDate(LocalDateTime.now())
            .updateDate(LocalDateTime.now())
            .build());
  }

  @AfterEach
  void cleanUp() {
    Tenants.acrossAll(
        () -> {
          consultantAgencyRepository.deleteAll(
              consultantAgencyRepository.findByConsultantId(ADMIN_ID));
          consultantRepository.findById(ADMIN_ID).ifPresent(consultantRepository::delete);
          adminRepository.deleteById(ADMIN_ID);
        });
  }

  @Test
  void aDoubleClick_Should_LeaveOneWorkingCounsellorIdentity() throws Exception {
    Tenants.actAs(
        caller,
        ADMIN_ID,
        OWN_TENANT,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
    var command = new SelfAssignmentCommand(SelfAssignmentRole.COUNSELLOR, AGENCY);
    var start = new CountDownLatch(1);
    Callable<Object> click =
        () -> {
          start.await();
          try {
            Tenants.in(OWN_TENANT, () -> service.assign(command));
            return HttpStatus.CREATED;
          } catch (CustomValidationHttpStatusException conflict) {
            return conflict.getHttpStatus();
          }
        };
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Object> first = executor.submit(click);
      Future<Object> second = executor.submit(click);
      start.countDown();

      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
    } finally {
      executor.shutdownNow();
    }

    // The loser must not have rolled back the winner's Keycloak role.
    assertThat(keycloakRoles).contains(CONSULTANT_ROLE);
    verify(identityClient, never()).removeRoleIfPresent(eq(ADMIN_ID), anyString());
    // One chat account, and it belongs to the one counsellor row: nothing orphaned in Matrix.
    verify(matrixUserClient, times(1)).createUserId(anyString(), anyString(), any());
    var counsellor = consultantRepository.findById(ADMIN_ID).orElseThrow();
    assertThat(counsellor.getDeleteDate()).isNull();
    assertThat(counsellor.getMatrixUserId()).isEqualTo(CHAT_ACCOUNT);
    assertThat(consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(ADMIN_ID))
        .extracting(ConsultantAgency::getAgencyId)
        .containsExactly(AGENCY);
  }
}
