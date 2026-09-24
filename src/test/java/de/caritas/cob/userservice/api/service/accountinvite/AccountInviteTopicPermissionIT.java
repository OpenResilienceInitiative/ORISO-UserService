package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseProcessor;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.tenant.TenantFixtures;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

/** Real database; only AgencyService, TenantService, Keycloak and SMTP are replaced. */
@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  AccountInviteService.class,
  InviteTargetResolver.class,
  ReservationLedger.class,
  UnitQueue.class,
  InviteDelivery.class,
  AccountInviteAccessPolicy.class,
  de.caritas.cob.userservice.api.admin.service.admin.AdminScope.class,
  AccountInviteTopicPermissionService.class,
  AccountInviteTopicPermissionIT.CallerConfig.class,
  TenantFixtures.class
})
@WithTenant(AccountInviteTopicPermissionIT.OWN_TENANT)
class AccountInviteTopicPermissionIT {

  static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;

  /** Seeded restricted agency admin who administers agency 1 only. */
  private static final String AGENCY_ADMIN_ID = "d42c2e5e-143c-4db1-a90f-7cccf82fbb15";

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

  @Autowired private TenantFixtures fixtures;
  @Autowired private AccountInviteTopicPermissionService service;
  @Autowired private AccountInviteService invites;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private AuthenticatedUser caller;
  @Autowired private InviteEmailTemplateRepository templateRepository;

  @MockitoBean private IdentityEmailOwnerLookup identityEmailOwnerLookup;
  @MockitoBean private de.caritas.cob.userservice.api.service.agency.AgencyService agencyService;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private TenantIdAllocationClient tenantIdAllocationClient;
  @MockitoBean private AgencyIdAllocationClient agencyIdAllocationClient;
  @MockitoBean private AgencyFacts agencyFacts;
  @MockitoBean private IdReservationReleaseProcessor reservationReleaseProcessor;
  @MockitoBean private InviteAcceptUrlBuilder inviteAcceptUrlBuilder;
  @MockitoBean private InviteMailDispatchService inviteMailDispatchService;
  @MockitoBean private InviteEmailDeliveryFailureRecorder deliveryFailureRecorder;

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
    fixtures.removeAll();
  }

  // --- prefill at invite time -------------------------------------------------------------------

  @Test
  void createInvite_Should_TakeTheAgencyDefault_When_TheAdminLeavesThePermissionOpen() {
    actAsTenantAdmin();

    assertThat(
            invites
                .createInvite(withPermission(counsellorInto(LEGACY_AGENCY, 11L), null))
                .getTopicPermission())
        .isEqualTo(TopicPermission.CREATE);
    assertThat(
            invites
                .createInvite(withPermission(counsellorInto(SELECT_AGENCY, 21L), null))
                .getTopicPermission())
        .isEqualTo(TopicPermission.SELECT_EXISTING);
    assertThat(
            invites
                .createInvite(withPermission(counsellorInto(NEW_STYLE_AGENCY, 51L), null))
                .getTopicPermission())
        .isEqualTo(TopicPermission.NONE);
  }

  @Test
  void createInvite_Should_AskAgencyServiceOnce_ForScopeBindingAndPermission() {
    actAsTenantAdmin();

    invites.createInvite(withPermission(counsellorInto(SELECT_AGENCY, null), null));

    verify(agencyFacts, times(1)).find(SELECT_AGENCY);
  }

  @Test
  void createInvite_Should_StoreTheAdminsChoice_When_ItDiffersFromTheAgencyDefault() {
    actAsTenantAdmin();

    assertThat(
            invites
                .createInvite(
                    withPermission(counsellorInto(LEGACY_AGENCY, 11L), TopicPermission.NONE))
                .getTopicPermission())
        .isEqualTo(TopicPermission.NONE);
    assertThat(
            invites
                .createInvite(
                    withPermission(counsellorInto(NEW_STYLE_AGENCY, 51L), TopicPermission.CREATE))
                .getTopicPermission())
        .isEqualTo(TopicPermission.CREATE);
  }

  @Test
  void createInvite_Should_LetTheFounderCreateTopics_When_TheAgencyDoesNotExistYet() {
    actAsTenantAdmin();

    // The founder of a new agency is its agency admin.
    AccountInvite invite = invites.createInvite(withPermission(agencyAdminIntoNewAgency(), null));

    assertThat(invite.getTopicPermission()).isEqualTo(TopicPermission.CREATE);
    verify(agencyFacts, never()).find(anyLong());
  }

  @Test
  void createInvite_Should_KeepThePermission_When_TheCounsellorWaitsForANewAgency() {
    actAsTenantAdmin();

    // No topic check yet: the new agency's admin brings the topics before the release.
    givenAPendingAdminForTheNewAgency();
    AccountInvite open =
        invites.createInvite(withPermission(counsellorWaitingForNewAgency(), null));
    AccountInvite fixed =
        invites.createInvite(
            withPermission(counsellorWaitingForNewAgency(), TopicPermission.SELECT_EXISTING));
    AccountInvite free =
        invites.createInvite(
            withPermission(counsellorWaitingForNewAgency(), TopicPermission.CREATE));

    assertThat(open.getStatus()).isEqualTo(AccountInviteStatus.WAITING_FOR_UNIT);
    assertThat(open.getTopicPermission()).isEqualTo(TopicPermission.NONE);
    assertThat(free.getTopicPermission()).isEqualTo(TopicPermission.CREATE);
    assertThat(fixed.getStatus()).isEqualTo(AccountInviteStatus.WAITING_FOR_UNIT);
    assertThat(accountInviteRepository.findById(fixed.getId()).orElseThrow().getTopicPermission())
        .isEqualTo(TopicPermission.SELECT_EXISTING);
  }

  @Test
  void createInvite_Should_Refuse_When_TheCounsellorWouldBeLeftWithoutATopic() {
    actAsTenantAdmin();

    // An agency without topics has nothing to fix or to select.
    assertThatThrownBy(
            () ->
                invites.createInvite(
                    withPermission(counsellorInto(TOPICLESS_AGENCY, null), TopicPermission.NONE)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                invites.createInvite(
                    withPermission(
                        counsellorInto(TOPICLESS_AGENCY, null), TopicPermission.SELECT_EXISTING)))
        .isInstanceOf(BadRequestException.class);
    assertThat(accountInviteRepository.findAll()).isEmpty();
  }

  @Test
  void createInvite_Should_AllowNoneWithoutDepartment_When_TheAgencyHasTopicsToPickOneFrom() {
    actAsTenantAdmin();

    AccountInvite invite =
        invites.createInvite(withPermission(counsellorInto(NEW_STYLE_AGENCY, null), null));

    assertThat(invite.getTopicPermission()).isEqualTo(TopicPermission.NONE);
  }

  @Test
  void createInvite_Should_StoreCreate_When_TheInviteIsForAnAgencyAdmin() {
    actAsTenantAdmin();

    // Whatever the agency default or the inviter's choice says.
    AccountInvite intoLegacy =
        invites.createInvite(withPermission(agencyAdminInto(LEGACY_AGENCY, "a"), null));
    AccountInvite intoNoneDefault =
        invites.createInvite(
            withPermission(agencyAdminInto(NEW_STYLE_AGENCY, "c"), TopicPermission.NONE));

    assertThat(intoLegacy.getTopicPermission()).isEqualTo(TopicPermission.CREATE);
    assertThat(
            accountInviteRepository
                .findById(intoNoneDefault.getId())
                .orElseThrow()
                .getTopicPermission())
        .isEqualTo(TopicPermission.CREATE);
  }

  @Test
  void createAndSend_Should_KeepTheChosenPermission_When_SmtpLeavesTheDeliveryUncertain() {
    actAsTenantAdmin();
    Long templateId =
        templateRepository
            .save(
                InviteEmailTemplate.builder()
                    .kind(InviteEmailTemplateKind.COUNSELLOR_INVITE)
                    .name("uncertain-smtp")
                    .language("de")
                    .subject("Einladung")
                    .body("Hallo")
                    .active(true)
                    .createDate(LocalDateTime.now())
                    .build())
            .getId();
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), anyString()))
        .thenReturn("https://admin.example.org/onboarding/token");
    when(inviteMailDispatchService.send(
            anyString(), anyString(), anyString(), anyString(), any(), any()))
        .thenThrow(
            new SmtpSendException(
                SmtpSendException.Category.SMTP_TRANSPORT_FAILED,
                SmtpSendException.DeliveryDisposition.DELIVERY_UNCERTAIN,
                "timeout after DATA"));
    try {
      assertThatThrownBy(
              () ->
                  invites.createAndSendInvite(
                      withPermission(counsellorInto(LEGACY_AGENCY, 11L), TopicPermission.NONE),
                      templateId))
          .isInstanceOf(SmtpSendException.class);

      // The claim stays so a retry cannot mail twice; it must carry the chosen permission.
      AccountInvite kept = accountInviteRepository.findAll().get(0);
      assertThat(kept.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
      assertThat(kept.getTopicPermission()).isEqualTo(TopicPermission.NONE);
    } finally {
      accountInviteRepository.deleteAll();
      templateRepository.deleteById(templateId);
    }
  }

  // --- later changes from the invite table ------------------------------------------------------

  @Test
  void updatePermission_Should_ChangeTheInvite_When_TheAccountDoesNotExistYet() {
    actAsTenantAdmin();
    AccountInvite invite =
        invites.createInvite(withPermission(counsellorInto(LEGACY_AGENCY, 11L), null));

    AccountInvite updated = service.updatePermission(invite.getId(), TopicPermission.NONE);

    assertThat(updated.getTopicPermission()).isEqualTo(TopicPermission.NONE);
    assertThat(accountInviteRepository.findById(invite.getId()).orElseThrow().getTopicPermission())
        .isEqualTo(TopicPermission.NONE);
  }

  @Test
  void updatePermission_Should_AlsoChangeTheCounsellor_When_TheAccountExists() {
    actAsTenantAdmin();
    AccountInvite invite =
        invites.createInvite(withPermission(counsellorInto(LEGACY_AGENCY, 11L), null));
    var counsellor = fixtures.consultant(OWN_TENANT, LEGACY_AGENCY);
    invite.setProvisionedUserId(counsellor.getId());
    accountInviteRepository.save(invite);

    service.updatePermission(invite.getId(), TopicPermission.SELECT_EXISTING);

    assertThat(consultantRepository.findById(counsellor.getId()).orElseThrow().getTopicPermission())
        .isEqualTo(TopicPermission.SELECT_EXISTING);
  }

  @Test
  void updatePermission_Should_BeAllowedForTheAgencyAdminOfThatAgency() {
    actAsTenantAdmin();
    AccountInvite invite =
        invites.createInvite(withPermission(counsellorInto(LEGACY_AGENCY, 11L), null));

    actAsAgencyAdmin();
    assertThat(service.updatePermission(invite.getId(), TopicPermission.NONE).getTopicPermission())
        .isEqualTo(TopicPermission.NONE);
  }

  @Test
  void updatePermission_Should_BeForbidden_When_TheInviteBelongsToAnotherAgency() {
    actAsTenantAdmin();
    AccountInvite invite =
        invites.createInvite(withPermission(counsellorInto(SELECT_AGENCY, 21L), null));

    actAsAgencyAdmin();
    assertThatThrownBy(() -> service.updatePermission(invite.getId(), TopicPermission.CREATE))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void updatePermission_Should_BeForbidden_When_TheInviteBelongsToAnotherTraeger() {
    actAsPlatformAdmin();
    AccountInvite invite =
        invites.createInvite(withPermission(counsellorIntoForeignTenant(), null));

    actAsTenantAdmin();
    assertThatThrownBy(() -> service.updatePermission(invite.getId(), TopicPermission.NONE))
        .isInstanceOf(ForbiddenException.class);
    assertThat(accountInviteRepository.findById(invite.getId()).orElseThrow().getTopicPermission())
        .isEqualTo(TopicPermission.CREATE);
  }

  @Test
  void updatePermission_Should_Refuse_Invalid_Requests() {
    actAsTenantAdmin();
    AccountInvite counsellorInvite =
        invites.createInvite(withPermission(counsellorInto(LEGACY_AGENCY, 11L), null));
    AccountInvite topiclessInvite =
        invites.createInvite(withPermission(counsellorInto(TOPICLESS_AGENCY, null), null));
    givenAPendingAdminForTheNewAgency();
    AccountInvite waitingInvite =
        invites.createInvite(withPermission(counsellorWaitingForNewAgency(), null));
    AccountInvite adminInvite =
        invites.createInvite(withPermission(agencyAdminInto(LEGACY_AGENCY, "b"), null));

    assertThatThrownBy(() -> service.updatePermission(adminInvite.getId(), TopicPermission.NONE))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.updatePermission(counsellorInvite.getId(), null))
        .isInstanceOf(BadRequestException.class);
    // An agency without topics leaves nothing to be limited to.
    assertThatThrownBy(
            () -> service.updatePermission(topiclessInvite.getId(), TopicPermission.NONE))
        .isInstanceOf(BadRequestException.class);
    // A counsellor waiting for a new agency: its admin brings the topics first.
    when(agencyFacts.find(4711L)).thenReturn(Optional.empty());
    assertThat(
            service
                .updatePermission(waitingInvite.getId(), TopicPermission.SELECT_EXISTING)
                .getTopicPermission())
        .isEqualTo(TopicPermission.SELECT_EXISTING);
    assertThatThrownBy(() -> service.updatePermission(987654L, TopicPermission.NONE))
        .isInstanceOf(NotFoundException.class);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private void actAsTenantAdmin() {
    Tenants.actAs(
        caller,
        "tenant-admin-1",
        OWN_TENANT,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  private void actAsAgencyAdmin() {
    Tenants.actAs(
        caller, AGENCY_ADMIN_ID, OWN_TENANT, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
  }

  private void actAsPlatformAdmin() {
    Tenants.actAs(
        caller,
        "platform-admin",
        0L,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  private void givenAgency(
      long agencyId, long tenantId, TopicPermission agencyDefault, List<Long> topicIds) {
    when(agencyFacts.find(agencyId))
        .thenReturn(
            Optional.of(
                new AgencyFacts.Agency(agencyId, tenantId, false, topicIds, agencyDefault)));
  }

  private static CreateAccountInviteCommand withPermission(
      CreateAccountInviteCommand command, TopicPermission permission) {
    return new CreateAccountInviteCommand(
        command.targetRole(),
        command.tenantId(),
        command.recipientEmail(),
        command.firstName(),
        command.lastName(),
        command.agencyId(),
        command.departmentId(),
        command.expiresInDays(),
        command.tenantIdAllocationMode(),
        command.agencyIdAllocationMode(),
        command.alsoCounsellor(),
        permission);
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

  private static CreateAccountInviteCommand agencyAdminIntoNewAgency() {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.AGENCY_ADMIN,
        OWN_TENANT,
        "new-agency-admin-" + System.nanoTime() + "@example.org",
        "Grace",
        "Hopper",
        null,
        null,
        null,
        null,
        IdAllocationMode.AUTO);
  }

  private void givenAPendingAdminForTheNewAgency() {
    invites.createInvite(
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.AGENCY_ADMIN,
            OWN_TENANT,
            "founder-" + System.nanoTime() + "@example.org",
            "Grace",
            "Hopper",
            4711L,
            null,
            null,
            null,
            IdAllocationMode.MANUAL));
  }

  /** A counsellor of the not-yet-created agency 4711. */
  private static CreateAccountInviteCommand counsellorWaitingForNewAgency() {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.COUNSELLOR,
        OWN_TENANT,
        "waiting-" + System.nanoTime() + "@example.org",
        "Ada",
        "Lovelace",
        4711L,
        null,
        null,
        null,
        IdAllocationMode.MANUAL);
  }
}
