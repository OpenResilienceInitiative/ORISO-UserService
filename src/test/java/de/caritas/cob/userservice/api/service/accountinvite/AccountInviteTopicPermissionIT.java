package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AgencyTopicPermissionLookup.AgencyTopicSettings;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.ExistingAgencyClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.ExistingAgencyClient.ExistingAgency;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseProcessor;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The topic permission per invited counsellor (ORISO-Admin#1026, slice 6).
 *
 * <p>Three levels: {@code NONE} (only the assigned department), {@code SELECT_EXISTING} (pick from
 * the agency's departments) and {@code CREATE} (the wizard's "+", today's behaviour). The agency
 * default prefills the permission when the invite is created; the inviting admin may override it.
 * Admins with rights on the agency may change it later in the invite table — also after the account
 * exists, in which case the counsellor's own permission follows.
 *
 * <p>Runs against a real database; only the remote services (AgencyService, TenantService,
 * Keycloak, SMTP) are replaced.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  AccountInviteService.class,
  AccountInviteAccessPolicy.class,
  AccountInviteTopicPermissionService.class,
  AccountInviteTopicPermissionIT.CallerConfig.class
})
class AccountInviteTopicPermissionIT {

  private static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;

  /** Seeded restricted agency admin who administers agency 1 only. */
  private static final String AGENCY_ADMIN_ID = "d42c2e5e-143c-4db1-a90f-7cccf82fbb15";

  /** A seeded consultant that stands in for the account an accepted invite created. */
  private static final String SEEDED_CONSULTANT_ID = "0b3b1cc6-be98-4787-aa56-212259d811b9";

  /** Agency of tenant 1 that existed before the setting: its default is CREATE. */
  private static final long LEGACY_AGENCY = 1L;

  /** Agency of tenant 1 whose default is SELECT_EXISTING, with two topics. */
  private static final long SELECT_AGENCY = 2L;

  /** Agency of tenant 1 created after the setting: its default is NONE. */
  private static final long NEW_STYLE_AGENCY = 6L;

  /** Agency of tenant 1 without any topic. */
  private static final long TOPICLESS_AGENCY = 5L;

  /** Agency of tenant 2. */
  private static final long FOREIGN_AGENCY = 3L;

  @TestConfiguration
  static class CallerConfig {
    @Bean
    AuthenticatedUser authenticatedUser() {
      return new AuthenticatedUser();
    }
  }

  @Autowired private AccountInviteTopicPermissionService service;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private AuthenticatedUser caller;

  @MockitoBean private AgencyTopicPermissionLookup agencyTopicPermissionLookup;
  @MockitoBean private IdentityEmailOwnerLookup identityEmailOwnerLookup;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private TenantIdAllocationClient tenantIdAllocationClient;
  @MockitoBean private AgencyIdAllocationClient agencyIdAllocationClient;
  @MockitoBean private ExistingAgencyClient existingAgencyClient;
  @MockitoBean private IdReservationReleaseProcessor reservationReleaseProcessor;
  @MockitoBean private InviteAcceptUrlBuilder inviteAcceptUrlBuilder;
  @MockitoBean private InviteMailDispatchService inviteMailDispatchService;
  @MockitoBean private InviteEmailDeliveryFailureRecorder deliveryFailureRecorder;
  @MockitoBean private AgencyService agencyService;

  @BeforeEach
  void givenAgencies() {
    when(identityEmailOwnerLookup.findByEmail(anyString())).thenReturn(Optional.empty());
    givenAgency(LEGACY_AGENCY, OWN_TENANT, TopicPermission.CREATE, List.of(11L));
    givenAgency(SELECT_AGENCY, OWN_TENANT, TopicPermission.SELECT_EXISTING, List.of(21L, 22L));
    givenAgency(NEW_STYLE_AGENCY, OWN_TENANT, TopicPermission.NONE, List.of(51L, 52L));
    givenAgency(TOPICLESS_AGENCY, OWN_TENANT, TopicPermission.CREATE, List.of());
    givenAgency(FOREIGN_AGENCY, FOREIGN_TENANT, TopicPermission.CREATE, List.of(31L));
    when(agencyIdAllocationClient.reserve(any(), any())).thenReturn(4711L);
    when(agencyIdAllocationClient.getAvailability(anyLong()))
        .thenReturn(IdAllocationStatus.RESERVED);
  }

  @AfterEach
  void cleanUp() {
    accountInviteRepository.deleteAll();
    consultantRepository
        .findById(SEEDED_CONSULTANT_ID)
        .ifPresent(
            consultant -> {
              consultant.setTopicPermission(TopicPermission.CREATE);
              consultantRepository.save(consultant);
            });
  }

  // --- prefill at invite time -------------------------------------------------------------------

  @Test
  void createInvite_Should_TakeTheAgencyDefault_When_TheAdminLeavesThePermissionOpen() {
    actAsTenantAdmin();

    assertThat(service.createInvite(counsellorInto(LEGACY_AGENCY, 11L), null).getTopicPermission())
        .isEqualTo(TopicPermission.CREATE);
    assertThat(service.createInvite(counsellorInto(SELECT_AGENCY, 21L), null).getTopicPermission())
        .isEqualTo(TopicPermission.SELECT_EXISTING);
    assertThat(
            service.createInvite(counsellorInto(NEW_STYLE_AGENCY, 51L), null).getTopicPermission())
        .isEqualTo(TopicPermission.NONE);
  }

  @Test
  void createInvite_Should_StoreTheAdminsChoice_When_ItDiffersFromTheAgencyDefault() {
    actAsTenantAdmin();

    assertThat(
            service
                .createInvite(counsellorInto(LEGACY_AGENCY, 11L), TopicPermission.NONE)
                .getTopicPermission())
        .isEqualTo(TopicPermission.NONE);
    assertThat(
            service
                .createInvite(counsellorInto(NEW_STYLE_AGENCY, 51L), TopicPermission.CREATE)
                .getTopicPermission())
        .isEqualTo(TopicPermission.CREATE);
  }

  @Test
  void createInvite_Should_LetTheFounderCreateTopics_When_TheAgencyDoesNotExistYet() {
    actAsTenantAdmin();

    AccountInvite invite = service.createInvite(counsellorIntoNewAgency(null), null);

    assertThat(invite.getTopicPermission()).isEqualTo(TopicPermission.CREATE);
    verify(agencyTopicPermissionLookup, never()).find(anyLong());
  }

  @Test
  void createInvite_Should_Refuse_When_TheCounsellorWouldBeLeftWithoutATopic() {
    actAsTenantAdmin();

    // An agency without topics has nothing to fix or to select.
    assertThatThrownBy(
            () ->
                service.createInvite(counsellorInto(TOPICLESS_AGENCY, null), TopicPermission.NONE))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                service.createInvite(
                    counsellorInto(TOPICLESS_AGENCY, null), TopicPermission.SELECT_EXISTING))
        .isInstanceOf(BadRequestException.class);
    // A new agency has no departments yet, so only CREATE works without an assigned department.
    assertThatThrownBy(
            () ->
                service.createInvite(
                    counsellorIntoNewAgency(null), TopicPermission.SELECT_EXISTING))
        .isInstanceOf(BadRequestException.class);
    assertThat(accountInviteRepository.findAll()).isEmpty();
  }

  @Test
  void createInvite_Should_AllowNoneWithoutDepartment_When_TheAgencyHasTopicsToPickOneFrom() {
    actAsTenantAdmin();

    AccountInvite invite = service.createInvite(counsellorInto(NEW_STYLE_AGENCY, null), null);

    assertThat(invite.getTopicPermission()).isEqualTo(TopicPermission.NONE);
  }

  @Test
  void createInvite_Should_StoreNone_When_TheInviteIsNotForACounsellor() {
    actAsTenantAdmin();

    AccountInvite invite = service.createInvite(agencyAdminInto(LEGACY_AGENCY, "a"), null);

    assertThat(invite.getTopicPermission()).isEqualTo(TopicPermission.NONE);
  }

  // --- later changes from the invite table ------------------------------------------------------

  @Test
  void updatePermission_Should_ChangeTheInvite_When_TheAccountDoesNotExistYet() {
    actAsTenantAdmin();
    AccountInvite invite = service.createInvite(counsellorInto(LEGACY_AGENCY, 11L), null);

    AccountInvite updated = service.updatePermission(invite.getId(), TopicPermission.NONE);

    assertThat(updated.getTopicPermission()).isEqualTo(TopicPermission.NONE);
    assertThat(accountInviteRepository.findById(invite.getId()).orElseThrow().getTopicPermission())
        .isEqualTo(TopicPermission.NONE);
  }

  @Test
  void updatePermission_Should_AlsoChangeTheCounsellor_When_TheAccountExists() {
    actAsTenantAdmin();
    AccountInvite invite = service.createInvite(counsellorInto(LEGACY_AGENCY, 11L), null);
    invite.setProvisionedUserId(SEEDED_CONSULTANT_ID);
    accountInviteRepository.save(invite);

    service.updatePermission(invite.getId(), TopicPermission.SELECT_EXISTING);

    assertThat(
            consultantRepository.findById(SEEDED_CONSULTANT_ID).orElseThrow().getTopicPermission())
        .isEqualTo(TopicPermission.SELECT_EXISTING);
  }

  @Test
  void updatePermission_Should_BeAllowedForTheAgencyAdminOfThatAgency() {
    actAsTenantAdmin();
    AccountInvite invite = service.createInvite(counsellorInto(LEGACY_AGENCY, 11L), null);

    actAsAgencyAdmin();
    assertThat(service.updatePermission(invite.getId(), TopicPermission.NONE).getTopicPermission())
        .isEqualTo(TopicPermission.NONE);
  }

  @Test
  void updatePermission_Should_BeForbidden_When_TheInviteBelongsToAnotherAgency() {
    actAsTenantAdmin();
    AccountInvite invite = service.createInvite(counsellorInto(SELECT_AGENCY, 21L), null);

    actAsAgencyAdmin();
    assertThatThrownBy(() -> service.updatePermission(invite.getId(), TopicPermission.CREATE))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void updatePermission_Should_BeForbidden_When_TheInviteBelongsToAnotherTraeger() {
    actAsPlatformAdmin();
    AccountInvite invite = service.createInvite(counsellorIntoForeignTenant(), null);

    actAsTenantAdmin();
    assertThatThrownBy(() -> service.updatePermission(invite.getId(), TopicPermission.NONE))
        .isInstanceOf(ForbiddenException.class);
    assertThat(accountInviteRepository.findById(invite.getId()).orElseThrow().getTopicPermission())
        .isEqualTo(TopicPermission.CREATE);
  }

  @Test
  void updatePermission_Should_Refuse_Invalid_Requests() {
    actAsTenantAdmin();
    AccountInvite counsellorInvite = service.createInvite(counsellorInto(LEGACY_AGENCY, 11L), null);
    AccountInvite founderInvite = service.createInvite(counsellorIntoNewAgency(null), null);
    AccountInvite adminInvite = service.createInvite(agencyAdminInto(LEGACY_AGENCY, "b"), null);

    assertThatThrownBy(() -> service.updatePermission(adminInvite.getId(), TopicPermission.NONE))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.updatePermission(counsellorInvite.getId(), null))
        .isInstanceOf(BadRequestException.class);
    // The founder of a new agency has no department to be limited to.
    when(agencyTopicPermissionLookup.find(4711L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.updatePermission(founderInvite.getId(), TopicPermission.NONE))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.updatePermission(987654L, TopicPermission.NONE))
        .isInstanceOf(NotFoundException.class);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private void actAsTenantAdmin() {
    actAs(
        "tenant-admin-1",
        OWN_TENANT,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  private void actAsAgencyAdmin() {
    actAs(AGENCY_ADMIN_ID, OWN_TENANT, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
  }

  private void actAsPlatformAdmin() {
    actAs("platform-admin", 0L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);
  }

  private void actAs(String userId, Long tenantId, UserRole... roles) {
    caller.setUserId(userId);
    caller.setUsername(userId);
    caller.setTenantId(tenantId);
    caller.setRoles(Arrays.stream(roles).map(UserRole::getValue).collect(Collectors.toSet()));
    caller.setGrantedAuthorities(Set.of());
  }

  private void givenAgency(
      long agencyId, long tenantId, TopicPermission agencyDefault, List<Long> topicIds) {
    when(agencyService.getAgencyWithoutCaching(agencyId))
        .thenReturn(new AgencyDTO().id(agencyId).tenantId(tenantId).topicIds(topicIds));
    when(existingAgencyClient.find(agencyId))
        .thenReturn(Optional.of(new ExistingAgency(agencyId, tenantId, false, topicIds)));
    when(agencyTopicPermissionLookup.find(agencyId))
        .thenReturn(Optional.of(new AgencyTopicSettings(agencyDefault, topicIds)));
  }

  private static CreateAccountInviteCommand counsellorInto(long agencyId, Long departmentId) {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.COUNSELLOR,
        null,
        "counsellor-" + agencyId + "-" + departmentId + "-" + System.nanoTime() + "@example.org",
        "Ada",
        "Lovelace",
        agencyId,
        departmentId,
        null,
        null,
        IdAllocationMode.EXISTING);
  }

  private static CreateAccountInviteCommand agencyAdminInto(long agencyId, String suffix) {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.AGENCY_ADMIN,
        null,
        "agency-admin-" + suffix + "-" + System.nanoTime() + "@example.org",
        "Grace",
        "Hopper",
        agencyId,
        null,
        null,
        null,
        null);
  }

  private static CreateAccountInviteCommand counsellorIntoForeignTenant() {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.COUNSELLOR,
        FOREIGN_TENANT,
        "foreign-" + System.nanoTime() + "@example.org",
        "Ada",
        "Lovelace",
        FOREIGN_AGENCY,
        31L,
        null,
        null,
        IdAllocationMode.EXISTING);
  }

  private static CreateAccountInviteCommand counsellorIntoNewAgency(Long departmentId) {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.COUNSELLOR,
        OWN_TENANT,
        "new-agency-" + System.nanoTime() + "@example.org",
        "Ada",
        "Lovelace",
        null,
        departmentId,
        null,
        null,
        IdAllocationMode.AUTO);
  }
}
