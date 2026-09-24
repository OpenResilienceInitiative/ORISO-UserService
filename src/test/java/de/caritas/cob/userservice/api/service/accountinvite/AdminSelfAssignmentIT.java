package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GrantConsultantIdentityDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.create.GrantConsultantIdentityService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AdminSelfAssignmentService.SelfAssignmentCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AdminSelfAssignmentService.SelfAssignmentRole;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  AdminSelfAssignmentService.class,
  AccountInviteAccessPolicy.class,
  de.caritas.cob.userservice.api.admin.service.admin.AdminScope.class,
  AdminSelfAssignmentIT.CallerConfig.class
})
class AdminSelfAssignmentIT {

  private static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;

  private static final String TENANT_ADMIN_ID = "5e1f0000-1026-4a4a-9d1e-00000000000a";

  /** Seeded restricted agency admin who administers agency 1 (tenant 1) only. */
  private static final String AGENCY_ADMIN_ID = "d42c2e5e-143c-4db1-a90f-7cccf82fbb15";

  /** Seeded consultant who already counsels in agency 1. */
  private static final String COUNSELLING_CALLER_ID = "473f7c4b-f011-4fc2-847c-ceb636a5b399";

  private static final long OWN_AGENCY = 1L;
  private static final long OTHER_OWN_TENANT_AGENCY = 2L;
  private static final long FOREIGN_AGENCY = 3L;
  private static final long MISSING_AGENCY = 999L;
  private static final long TOPICLESS_AGENCY = 4L;

  @TestConfiguration
  static class CallerConfig {
    @Bean
    AuthenticatedUser authenticatedUser() {
      return new AuthenticatedUser();
    }
  }

  @Autowired private AdminSelfAssignmentService service;
  @Autowired private AdminRepository adminRepository;
  @Autowired private AdminAgencyRepository adminAgencyRepository;
  @Autowired private AuthenticatedUser caller;
  @Autowired private de.caritas.cob.userservice.api.admin.service.admin.AdminScope adminScope;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;

  @MockitoBean private AgencyFacts agencyFacts;
  @MockitoBean private de.caritas.cob.userservice.api.service.agency.AgencyService agencyService;
  @MockitoBean private GrantConsultantIdentityService grantConsultantIdentityService;

  @MockitoBean
  private ConsultantAgencyRelationCreatorService consultantAgencyRelationCreatorService;

  @BeforeEach
  void givenAgenciesAndATenantAdmin() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        adminScope, "multitenancyEnabled", true);
    givenAgency(OWN_AGENCY, OWN_TENANT, List.of(11L));
    givenAgency(OTHER_OWN_TENANT_AGENCY, OWN_TENANT, List.of(21L));
    givenAgency(FOREIGN_AGENCY, FOREIGN_TENANT, List.of(31L));
    when(agencyFacts.find(MISSING_AGENCY)).thenReturn(Optional.empty());
    adminRepository.save(
        Admin.builder()
            .id(TENANT_ADMIN_ID)
            .type(Admin.AdminType.TENANT)
            .tenantId(OWN_TENANT)
            .username("self-assigning-tenant-admin")
            .firstName("Grace")
            .lastName("Hopper")
            .email("grace@example.org")
            .createDate(LocalDateTime.now())
            .updateDate(LocalDateTime.now())
            .build());
  }

  @AfterEach
  void cleanUp() {
    consultantAgencyRepository.deleteAll(
        consultantAgencyRepository
            .findByConsultantIdAndDeleteDateIsNull(COUNSELLING_CALLER_ID)
            .stream()
            .filter(relation -> relation.getAgencyId() == OTHER_OWN_TENANT_AGENCY)
            .toList());
    adminAgencyRepository.deleteAll(adminAgencyRepository.findByAdminId(TENANT_ADMIN_ID));
    adminRepository.deleteById(TENANT_ADMIN_ID);
  }

  @Test
  void tenantAdmin_MayNot_AssignThemselvesInAForeignTenantsAgency() {
    actAsTenantAdmin(TENANT_ADMIN_ID);

    assertThatThrownBy(
            () ->
                service.assign(
                    new SelfAssignmentCommand(SelfAssignmentRole.COUNSELLOR, FOREIGN_AGENCY)))
        .isInstanceOf(ForbiddenException.class);
    verify(grantConsultantIdentityService, never())
        .grantConsultantIdentityToAdmin(anyString(), any());
  }

  @Test
  void unknownAgency_Should_Answer404() {
    actAsTenantAdmin(TENANT_ADMIN_ID);

    assertThatThrownBy(
            () ->
                service.assign(
                    new SelfAssignmentCommand(SelfAssignmentRole.COUNSELLOR, MISSING_AGENCY)))
        .isInstanceOf(NotFoundException.class);
  }

  // --- as counsellor ---

  @Test
  void tenantAdmin_May_AssignThemselvesAsCounsellor_ThroughTheUsersAreaGrant() {
    actAsTenantAdmin(TENANT_ADMIN_ID);

    service.assign(
        new SelfAssignmentCommand(SelfAssignmentRole.COUNSELLOR, OTHER_OWN_TENANT_AGENCY));

    ArgumentCaptor<GrantConsultantIdentityDTO> grant =
        ArgumentCaptor.forClass(GrantConsultantIdentityDTO.class);
    verify(grantConsultantIdentityService)
        .grantConsultantIdentityToAdmin(eq(TENANT_ADMIN_ID), grant.capture());
    assertThat(grant.getValue().getAgencyIds()).containsExactly(OTHER_OWN_TENANT_AGENCY);
    assertThat(grant.getValue().getTopicIds()).containsExactly(21L);
  }

  @Test
  void agencyAdmin_May_AssignThemselvesAsCounsellorOfTheirOwnAgency() {
    actAsAgencyAdmin();

    service.assign(new SelfAssignmentCommand(SelfAssignmentRole.COUNSELLOR, OWN_AGENCY));

    verify(grantConsultantIdentityService)
        .grantConsultantIdentityToAdmin(eq(AGENCY_ADMIN_ID), any(GrantConsultantIdentityDTO.class));
  }

  @Test
  void agencyAdmin_MayNot_AssignThemselvesAsCounsellorOfAnotherAgency() {
    actAsAgencyAdmin();

    assertThatThrownBy(
            () ->
                service.assign(
                    new SelfAssignmentCommand(
                        SelfAssignmentRole.COUNSELLOR, OTHER_OWN_TENANT_AGENCY)))
        .isInstanceOf(ForbiddenException.class);
    verify(grantConsultantIdentityService, never())
        .grantConsultantIdentityToAdmin(anyString(), any());
  }

  @Test
  void anAdminWhoAlreadyCounsels_Should_OnlyGetTheAgencyAdded() {
    actAsTenantAdmin(COUNSELLING_CALLER_ID);

    service.assign(
        new SelfAssignmentCommand(SelfAssignmentRole.COUNSELLOR, OTHER_OWN_TENANT_AGENCY));

    ArgumentCaptor<CreateConsultantAgencyDTO> relation =
        ArgumentCaptor.forClass(CreateConsultantAgencyDTO.class);
    verify(consultantAgencyRelationCreatorService)
        .createNewConsultantAgency(eq(COUNSELLING_CALLER_ID), relation.capture());
    assertThat(relation.getValue().getAgencyId()).isEqualTo(OTHER_OWN_TENANT_AGENCY);
    verify(grantConsultantIdentityService, never())
        .grantConsultantIdentityToAdmin(anyString(), any());
  }

  @Test
  void anAdminWhoAlreadyCounselsInThatAgency_Should_Get409() {
    actAsTenantAdmin(COUNSELLING_CALLER_ID);

    assertThatThrownBy(
            () ->
                service.assign(
                    new SelfAssignmentCommand(SelfAssignmentRole.COUNSELLOR, OWN_AGENCY)))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            conflict -> assertThat(conflict.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void selfAssignment_Should_Answer400_When_TheAgencyOffersNoTopic() {
    givenAgency(TOPICLESS_AGENCY, OWN_TENANT, List.of());
    actAsTenantAdmin(TENANT_ADMIN_ID);

    assertThatThrownBy(
            () ->
                service.assign(
                    new SelfAssignmentCommand(SelfAssignmentRole.COUNSELLOR, TOPICLESS_AGENCY)))
        .isInstanceOf(BadRequestException.class);
    verify(grantConsultantIdentityService, never())
        .grantConsultantIdentityToAdmin(anyString(), any());
  }

  @Test
  void aDoubleClick_Should_AddTheAgencyOnce() throws Exception {
    actAsTenantAdmin(COUNSELLING_CALLER_ID);
    doAnswer(
            invocation -> {
              // The real relation creator calls AgencyService before it inserts the row.
              Thread.sleep(300);
              var now = LocalDateTime.now();
              consultantAgencyRepository.save(
                  ConsultantAgency.builder()
                      .consultant(
                          consultantRepository.findById(COUNSELLING_CALLER_ID).orElseThrow())
                      .agencyId(OTHER_OWN_TENANT_AGENCY)
                      .tenantId(OWN_TENANT)
                      .createDate(now)
                      .updateDate(now)
                      .build());
              return null;
            })
        .when(consultantAgencyRelationCreatorService)
        .createNewConsultantAgency(eq(COUNSELLING_CALLER_ID), any());
    var command = new SelfAssignmentCommand(SelfAssignmentRole.COUNSELLOR, OTHER_OWN_TENANT_AGENCY);
    var start = new CountDownLatch(1);
    Callable<Object> click =
        () -> {
          start.await();
          try {
            service.assign(command);
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

      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
    } finally {
      executor.shutdownNow();
    }
    assertThat(
            consultantAgencyRepository
                .findByConsultantIdAndDeleteDateIsNull(COUNSELLING_CALLER_ID)
                .stream()
                .filter(relation -> relation.getAgencyId() == OTHER_OWN_TENANT_AGENCY))
        .hasSize(1);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private void actAsTenantAdmin(String userId) {
    actAs(userId, OWN_TENANT, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);
  }

  private void actAsAgencyAdmin() {
    actAs(AGENCY_ADMIN_ID, OWN_TENANT, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
  }

  private void actAs(String userId, Long tenantId, UserRole... roles) {
    caller.setUserId(userId);
    caller.setUsername(userId);
    caller.setTenantId(tenantId);
    caller.setRoles(
        java.util.Arrays.stream(roles)
            .map(UserRole::getValue)
            .collect(java.util.stream.Collectors.toSet()));
    caller.setGrantedAuthorities(Set.of());
  }

  private void givenAgency(long agencyId, long tenantId, List<Long> topicIds) {
    when(agencyFacts.find(agencyId))
        .thenReturn(
            Optional.of(
                new AgencyFacts.Agency(
                    agencyId, tenantId, false, topicIds, TopicPermission.CREATE)));
  }
}
