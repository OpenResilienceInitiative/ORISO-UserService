package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.SendInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseProcessor;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdReservation;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.web.client.HttpClientErrorException;

/**
 * Invites into a unit that does not exist yet wait (not sent) and need a pending admin invite for
 * the same ID; once the unit exists they are released with their template.
 */
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
  AccountInviteTopicPermissionService.class,
  AccountInviteAccessPolicy.class,
  de.caritas.cob.userservice.api.admin.service.admin.AdminScope.class,
  AccountInviteUnitQueueIT.CallerConfig.class
})
@WithTenant(AccountInviteUnitQueueIT.OWN_TENANT)
class AccountInviteUnitQueueIT {

  static final long OWN_TENANT = 1L;
  private static final long NEW_AGENCY = 500L;
  private static final long EXISTING_AGENCY = 1L;
  private static final long NEW_TENANT = 900L;

  @TestConfiguration
  static class CallerConfig {
    @Bean
    AuthenticatedUser authenticatedUser() {
      return new AuthenticatedUser();
    }
  }

  @Autowired private AccountInviteService service;
  @Autowired private UnitQueue queue;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private InviteEmailTemplateRepository templateRepository;
  @Autowired private InviteEmailDeliveryRepository deliveryRepository;
  @Autowired private AuthenticatedUser caller;

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

  private Long templateId;

  @BeforeEach
  void upstreams() {
    when(identityEmailOwnerLookup.findByEmail(anyString())).thenReturn(Optional.empty());
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), anyString()))
        .thenReturn("https://admin.example.org/admin/counsellor-onboarding/token");
    when(inviteMailDispatchService.send(
            anyString(), anyString(), anyString(), anyString(), any(), any()))
        .thenAnswer(call -> new InviteMailSendReceipt(call.getArgument(0), Instant.now()));
    // The new agency 500 is free until an admin invite reserves it; afterwards it is RESERVED.
    when(agencyIdAllocationClient.getAvailability(NEW_AGENCY)).thenReturn(IdAllocationStatus.FREE);
    when(agencyIdAllocationClient.reserve(NEW_AGENCY, OWN_TENANT))
        .thenAnswer(
            call -> {
              when(agencyIdAllocationClient.getAvailability(NEW_AGENCY))
                  .thenReturn(IdAllocationStatus.RESERVED);
              return NEW_AGENCY;
            });
    when(agencyIdAllocationClient.getAvailability(EXISTING_AGENCY))
        .thenReturn(IdAllocationStatus.ASSIGNED);
    when(agencyFacts.find(EXISTING_AGENCY))
        .thenReturn(
            Optional.of(
                new AgencyFacts.Agency(
                    EXISTING_AGENCY,
                    OWN_TENANT,
                    false,
                    java.util.List.of(),
                    TopicPermission.CREATE)));
    when(tenantService.getRestrictedTenantData(NEW_TENANT))
        .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "", null, null, null));
    templateId =
        templateRepository
            .save(
                InviteEmailTemplate.builder()
                    .kind(InviteEmailTemplateKind.COUNSELLOR_INVITE)
                    .name("queue-it")
                    .language("de")
                    .subject("Einladung")
                    .body("Hallo {{firstName}}")
                    .active(true)
                    .createDate(LocalDateTime.now())
                    .build())
            .getId();
  }

  @AfterEach
  void cleanUp() {
    deliveryRepository.deleteAll();
    accountInviteRepository.deleteAll();
    templateRepository.deleteById(templateId);
  }

  // --- queueing ---------------------------------------------------------------------------------

  @Test
  void counsellorIntoANewAgency_Should_Wait_When_AnAgencyAdminInviteIsPending() {
    actAsTenantAdmin();
    service.createInvite(agencyAdmin(NEW_AGENCY));

    AccountInvite counsellor = service.createInvite(counsellor(NEW_AGENCY));

    assertThat(counsellor.getStatus()).isEqualTo(AccountInviteStatus.WAITING_FOR_UNIT);
    assertThat(counsellor.getWaitingForUnit()).isEqualTo(InviteUnitType.AGENCY);
    assertThat(counsellor.getAgencyId()).isEqualTo(NEW_AGENCY);
    assertThat(counsellor.getTenantId()).isEqualTo(OWN_TENANT);
    assertThat(counsellor.getExpiresAt()).isNull();
    assertThat(counsellor.getTokenHash()).isNull();
    assertThat(queue.problemOf(counsellor)).isNull();
    verify(agencyIdAllocationClient, times(1)).reserve(NEW_AGENCY, OWN_TENANT);
  }

  @Test
  void counsellorIntoANewAgency_Should_Answer409_When_NoAgencyAdminInviteIsPending() {
    actAsTenantAdmin();

    assertReason(
        () -> service.createInvite(counsellor(NEW_AGENCY)),
        HttpStatusExceptionReason.NO_PENDING_UNIT_ADMIN);
    assertThat(accountInviteRepository.count()).isZero();
    verify(agencyIdAllocationClient, never()).reserve(any(), any());
  }

  @Test
  void counsellorWithAnAutoAgency_Should_Answer409_BecauseNoAdminCanHoldThatId() {
    actAsTenantAdmin();

    assertReason(
        () ->
            service.createInvite(
                command(
                    AccountInviteTargetRole.COUNSELLOR, null, null, null, IdAllocationMode.AUTO)),
        HttpStatusExceptionReason.NO_PENDING_UNIT_ADMIN);
  }

  @Test
  void counsellorWithManualOnAnExistingAgency_Should_StillAnswer409() {
    actAsTenantAdmin();

    assertThatThrownBy(() -> service.createInvite(counsellor(EXISTING_AGENCY)))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
  }

  @Test
  void queuedInvite_Should_NotBeSent_ButKeepItsTemplate_When_CreatedWithDirectSend() {
    actAsTenantAdmin();
    service.createInvite(agencyAdmin(NEW_AGENCY));

    var result = service.createAndSendInvite(counsellor(NEW_AGENCY), templateId);

    assertThat(result.invite().getStatus()).isEqualTo(AccountInviteStatus.WAITING_FOR_UNIT);
    assertThat(result.rawToken()).isNull();
    AccountInvite stored = accountInviteRepository.findById(result.invite().getId()).orElseThrow();
    assertThat(stored.getQueuedTemplateId()).isEqualTo(templateId);
    verify(inviteMailDispatchService, never())
        .send(anyString(), anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  void waitingInvite_Should_HoldTheRecipientAddress() {
    actAsTenantAdmin();
    service.createInvite(agencyAdmin(NEW_AGENCY));
    CreateAccountInviteCommand counsellor = counsellor(NEW_AGENCY);
    service.createInvite(counsellor);

    assertReason(
        () -> service.createInvite(counsellor), HttpStatusExceptionReason.EMAIL_NOT_AVAILABLE);
  }

  // --- several admins, revoked / expired admin -------------------------------------------------

  @Test
  void secondAgencyAdminForTheSameNewAgency_Should_ShareTheReservation() {
    actAsTenantAdmin();
    AccountInvite first = service.createInvite(agencyAdmin(NEW_AGENCY));

    AccountInvite second = service.createInvite(agencyAdmin(NEW_AGENCY));

    assertThat(second.getAgencyId()).isEqualTo(first.getAgencyId());
    assertThat(second.getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    verify(agencyIdAllocationClient, times(1)).reserve(any(), any());
  }

  @Test
  void revokedAdmin_Should_LeaveTheQueueWithNoUnitAdmin_UntilANewAdminIsInvited() {
    actAsTenantAdmin();
    AccountInvite admin = service.createInvite(agencyAdmin(NEW_AGENCY));
    AccountInvite counsellor = service.createInvite(counsellor(NEW_AGENCY));

    service.revokeInvite(admin.getId());
    assertThat(queue.problemOf(reload(counsellor))).isEqualTo(InviteQueueProblem.NO_UNIT_ADMIN);

    service.createInvite(agencyAdmin(NEW_AGENCY));
    assertThat(queue.problemOf(reload(counsellor))).isNull();
    verify(agencyIdAllocationClient, times(1)).reserve(any(), any());
  }

  @Test
  void expiredAdmin_Should_LeaveTheQueueWithNoUnitAdmin() {
    actAsTenantAdmin();
    AccountInvite admin = service.createInvite(agencyAdmin(NEW_AGENCY));
    AccountInvite counsellor = service.createInvite(counsellor(NEW_AGENCY));

    AccountInvite stored = reload(admin);
    stored.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    accountInviteRepository.save(stored);

    assertThat(queue.problemOf(reload(counsellor))).isEqualTo(InviteQueueProblem.NO_UNIT_ADMIN);
  }

  // --- release ----------------------------------------------------------------------------------

  @Test
  void release_Should_SendWaitingInvitesWithTheirTemplate_AndStartTheExpiryClockThere() {
    actAsTenantAdmin();
    service.createInvite(agencyAdmin(NEW_AGENCY));
    var queued = service.createAndSendInvite(counsellor(NEW_AGENCY, 10L), templateId);
    when(agencyIdAllocationClient.getAvailability(NEW_AGENCY))
        .thenReturn(IdAllocationStatus.ASSIGNED);

    List<Long> released = queue.release(InviteUnitType.AGENCY, NEW_AGENCY);

    assertThat(released).containsExactly(queued.invite().getId());
    AccountInvite sent = reload(queued.invite());
    assertThat(sent.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(sent.getWaitingForUnit()).isNull();
    assertThat(sent.getTokenHash()).isNotNull();
    assertThat(sent.getExpiresAt())
        .isAfter(LocalDateTime.now().plusDays(10).minusMinutes(5))
        .isBefore(LocalDateTime.now().plusDays(10).plusMinutes(5));
    verify(inviteMailDispatchService)
        .send(eq(sent.getRecipientEmail()), anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  void twoReleasesAtOnce_Should_MailOnce_AndKeepTheMailedLinkValid() throws Exception {
    actAsTenantAdmin();
    service.createInvite(agencyAdmin(NEW_AGENCY));
    var queued = service.createAndSendInvite(counsellor(NEW_AGENCY), templateId);
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), anyString()))
        .thenAnswer(call -> "https://admin.example.org/onboarding/" + call.getArgument(1));
    List<String> mailedLinks = new CopyOnWriteArrayList<>();
    when(inviteMailDispatchService.send(
            anyString(), anyString(), anyString(), anyString(), any(), any()))
        .thenAnswer(
            call -> {
              mailedLinks.add(call.getArgument(3));
              Thread.sleep(300);
              return new InviteMailSendReceipt(call.getArgument(0), Instant.now());
            });
    var start = new CountDownLatch(1);
    Callable<List<Long>> trigger =
        () -> {
          start.await();
          return queue.release(InviteUnitType.AGENCY, NEW_AGENCY);
        };
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<List<Long>> first = executor.submit(trigger);
      Future<List<Long>> second = executor.submit(trigger);
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertThat(mailedLinks).hasSize(1);
    String mailedToken = mailedLinks.get(0).substring(mailedLinks.get(0).lastIndexOf('/') + 1);
    AccountInvite sent = reload(queued.invite());
    assertThat(sent.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(sent.getTokenHash()).isEqualTo(AccountInviteService.hash(mailedToken));
  }

  @Test
  void release_Should_LeaveADraftWithoutLink_When_SmtpConfirmsTheMailWasNotSent() {
    actAsTenantAdmin();
    service.createInvite(agencyAdmin(NEW_AGENCY));
    var queued = service.createAndSendInvite(counsellor(NEW_AGENCY), templateId);
    when(inviteMailDispatchService.send(
            anyString(), anyString(), anyString(), anyString(), any(), any()))
        .thenThrow(
            new SmtpSendException(
                SmtpSendException.Category.SMTP_DISABLED_OR_INCOMPLETE, "smtp off"));

    List<Long> released = queue.release(InviteUnitType.AGENCY, NEW_AGENCY);

    assertThat(released).containsExactly(queued.invite().getId());
    AccountInvite draft = reload(queued.invite());
    assertThat(draft.getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    assertThat(draft.getTokenHash()).isNull();
  }

  @Test
  void release_Should_TurnAWaitingInviteWithoutTemplateIntoADraft() {
    actAsTenantAdmin();
    service.createInvite(agencyAdmin(NEW_AGENCY));
    AccountInvite queued = service.createInvite(counsellor(NEW_AGENCY));

    queue.release(InviteUnitType.AGENCY, NEW_AGENCY);

    AccountInvite draft = reload(queued);
    assertThat(draft.getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    assertThat(draft.getExpiresAt()).isNotNull();
    verify(inviteMailDispatchService, never())
        .send(anyString(), anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  void manualSend_Should_Answer409_WhileTheUnitDoesNotExist() {
    actAsTenantAdmin();
    service.createInvite(agencyAdmin(NEW_AGENCY));
    AccountInvite queued = service.createInvite(counsellor(NEW_AGENCY));

    assertReason(
        () -> service.sendInvite(new SendInviteCommand(queued.getId(), templateId)),
        HttpStatusExceptionReason.UNIT_NOT_CREATED);
  }

  @Test
  void manualSend_Should_ReleaseTheInvite_OnceTheUnitExists() {
    actAsTenantAdmin();
    service.createInvite(agencyAdmin(NEW_AGENCY));
    AccountInvite queued = service.createInvite(counsellor(NEW_AGENCY));
    when(agencyIdAllocationClient.getAvailability(NEW_AGENCY))
        .thenReturn(IdAllocationStatus.ASSIGNED);

    var result = service.sendInvite(new SendInviteCommand(queued.getId(), templateId));

    assertThat(result.invite().getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(result.rawToken()).isNotBlank();
  }

  // --- a new Träger -----------------------------------------------------------------------------

  @Test
  void agencyAdminIntoANewTenant_Should_WaitForTheTenant_AndReserveItsAgencyOnRelease() {
    actAsPlatformAdmin();
    givenTheNewTenantCanBeReserved();
    service.createInvite(newTenantAdmin());

    AccountInvite agencyAdmin =
        service.createInvite(
            command(
                AccountInviteTargetRole.AGENCY_ADMIN,
                NEW_TENANT,
                IdAllocationMode.MANUAL,
                null,
                IdAllocationMode.AUTO));

    assertThat(agencyAdmin.getStatus()).isEqualTo(AccountInviteStatus.WAITING_FOR_UNIT);
    assertThat(agencyAdmin.getWaitingForUnit()).isEqualTo(InviteUnitType.TENANT);
    assertThat(agencyAdmin.getAgencyId()).isNull();
    verify(agencyIdAllocationClient, never()).reserve(any(), any());

    when(agencyIdAllocationClient.reserve(null, NEW_TENANT)).thenReturn(701L);
    queue.release(InviteUnitType.TENANT, NEW_TENANT);

    AccountInvite released = reload(agencyAdmin);
    assertThat(released.getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    assertThat(released.getAgencyId()).isEqualTo(701L);
  }

  @Test
  void agencyAdminIntoANewTenant_Should_Answer409_WithoutAPendingTenantAdmin() {
    actAsPlatformAdmin();

    assertReason(
        () ->
            service.createInvite(
                command(
                    AccountInviteTargetRole.AGENCY_ADMIN,
                    NEW_TENANT,
                    IdAllocationMode.MANUAL,
                    null,
                    IdAllocationMode.AUTO)),
        HttpStatusExceptionReason.NO_PENDING_UNIT_ADMIN);
  }

  @Test
  void secondTenantAdminForTheSameNewTenant_Should_ShareTheReservation() {
    actAsPlatformAdmin();
    givenTheNewTenantCanBeReserved();
    AccountInvite first = service.createInvite(newTenantAdmin());

    AccountInvite second = service.createInvite(newTenantAdmin());

    assertThat(second.getTenantIdReservationToken())
        .isEqualTo(first.getTenantIdReservationToken())
        .isNotNull();
    verify(tenantIdAllocationClient, times(1)).reserve(anyLong());
  }

  // --- helpers ----------------------------------------------------------------------------------

  private void givenTheNewTenantCanBeReserved() {
    when(tenantIdAllocationClient.getAvailability(NEW_TENANT))
        .thenReturn(IdAllocationStatus.RESERVED);
    when(tenantIdAllocationClient.reserve(NEW_TENANT))
        .thenReturn(new TenantIdReservation(NEW_TENANT, "reservation-token-900"));
  }

  private AccountInvite reload(AccountInvite invite) {
    return accountInviteRepository.findById(invite.getId()).orElseThrow();
  }

  private static void assertReason(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
      HttpStatusExceptionReason reason) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            conflict -> {
              assertThat(conflict.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(conflict.getCustomHttpHeaders().getFirst("X-Reason"))
                  .isEqualTo(reason.name());
            });
  }

  private void actAsTenantAdmin() {
    Tenants.actAs(
        caller,
        "tenant-admin-1",
        OWN_TENANT,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
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

  private static CreateAccountInviteCommand agencyAdmin(Long agencyId) {
    return command(
        AccountInviteTargetRole.AGENCY_ADMIN, null, null, agencyId, IdAllocationMode.MANUAL);
  }

  private static CreateAccountInviteCommand counsellor(Long agencyId) {
    return counsellor(agencyId, null);
  }

  private static CreateAccountInviteCommand counsellor(Long agencyId, Long expiresInDays) {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.COUNSELLOR,
        null,
        UUID.randomUUID() + "@example.org",
        "Ada",
        "Lovelace",
        agencyId,
        null,
        expiresInDays,
        null,
        IdAllocationMode.MANUAL,
        null);
  }

  private static CreateAccountInviteCommand newTenantAdmin() {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.TENANT_ADMIN,
        NEW_TENANT,
        UUID.randomUUID() + "@example.org",
        "Grace",
        "Hopper",
        null,
        null,
        null,
        IdAllocationMode.MANUAL,
        null,
        null);
  }

  private static CreateAccountInviteCommand command(
      AccountInviteTargetRole role,
      Long tenantId,
      IdAllocationMode tenantMode,
      Long agencyId,
      IdAllocationMode agencyMode) {
    return new CreateAccountInviteCommand(
        role,
        tenantId,
        UUID.randomUUID() + "@example.org",
        "Ada",
        "Lovelace",
        agencyId,
        null,
        null,
        tenantMode,
        agencyMode,
        null);
  }
}
