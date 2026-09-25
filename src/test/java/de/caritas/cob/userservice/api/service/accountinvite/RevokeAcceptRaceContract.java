package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdReservationReleaseTaskRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseProcessor;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.AgencyCreationClient;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import java.time.LocalDateTime;
import java.util.concurrent.Callable;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An admin revokes an account invite while the invitee finishes onboarding (ORISO-Admin#1026).
 * Exactly one of the two may win: a revoked invite never produces an account, and a late revoke
 * answers 409 without giving back numbers the new account uses. The accept side is the real
 * agency-admin provisioning; only Keycloak, SMTP and the remote ID ledgers are replaced. Run on H2
 * by {@link AccountInviteRevokeAcceptRaceIT} and on MariaDB by {@link
 * AccountInviteRevokeAcceptRaceMariaDbIT}, whose row locks are what production relies on.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  AccountInviteService.class,
  AgencyAdminInviteProvisioningService.class,
  InviteTargetResolver.class,
  ReservationLedger.class,
  UnitQueue.class,
  InviteDelivery.class,
  AccountInviteAccessPolicy.class,
  AccountInviteTopicPermissionService.class,
  de.caritas.cob.userservice.api.admin.service.admin.AdminScope.class,
  IdReservationReleaseProcessor.class,
  InviteRoleChange.class,
  InviteEmailDeliveryFailureRecorder.class,
  CounsellorInviteProvisioningService.class,
  de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService.class,
  RevokeAcceptRaceContract.CallerConfig.class
})
abstract class RevokeAcceptRaceContract {

  static final long OWN_TENANT = 1L;
  private static final long NEW_AGENCY = 700L;
  private static final long EXISTING_AGENCY = 1L;
  private static final long TOPIC = 11L;
  private static final String RAW_TOKEN = "revoke-accept-race-token";
  private static final String ADMIN_ID = "revoke-race-admin";
  private static final String RECIPIENT = "revoke-race@example.org";
  private static final String SECOND_RECIPIENT = "revoke-race-second@example.org";
  private static final String SECOND_TOKEN = "revoke-accept-race-second-token";
  private static final String WRITER_THREAD = "revoke-race-writer";
  private static final String REVOKE_THREAD = "revoke-race-admin-thread";

  /** Long enough for an unlocked revoke to read, short of every lock timeout in play. */
  private static final long REVOKE_READ_GRACE_MILLIS = 1_000L;

  /**
   * Tells the test when the revoking admin has read the invite, and runs a one-off hook when the
   * writer thread first checks the caller's reach, i.e. right after it read the invite.
   */
  static class ObservedCaller extends AuthenticatedUser {
    volatile CountDownLatch revokeDecided = new CountDownLatch(1);
    volatile Runnable afterWriterRead;

    @Override
    public String getUserId() {
      if (Thread.currentThread().getName().startsWith(REVOKE_THREAD)) {
        revokeDecided.countDown();
      }
      runWriterHook();
      return super.getUserId();
    }

    /** Every admin action asks this right after it loaded the invite (AdminScope#current). */
    @Override
    public boolean hasRestrictedAgencyPriviliges() {
      runWriterHook();
      return super.hasRestrictedAgencyPriviliges();
    }

    private void runWriterHook() {
      if (!Thread.currentThread().getName().equals(WRITER_THREAD)) {
        return;
      }
      Runnable hook = afterWriterRead;
      afterWriterRead = null;
      if (hook != null) {
        hook.run();
      }
    }
  }

  @TestConfiguration
  static class CallerConfig {
    @Bean
    ObservedCaller authenticatedUser() {
      return new ObservedCaller();
    }
  }

  @Autowired private AccountInviteService service;
  @Autowired private AgencyAdminInviteProvisioningService agencyAdminProvisioning;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private AdminRepository adminRepository;
  @Autowired private AdminAgencyRepository adminAgencyRepository;
  @Autowired private IdReservationReleaseTaskRepository releaseTaskRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObservedCaller caller;

  @MockitoBean private CreateAdminService createAdminService;
  @MockitoBean private IdentityAccountRemover identityAccountRemover;
  @MockitoBean private AcceptTimeAgencyCheck acceptTimeAgencyCheck;
  @MockitoBean private IdentityEmailOwnerLookup identityEmailOwnerLookup;
  @MockitoBean private de.caritas.cob.userservice.api.service.agency.AgencyService agencyService;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private TenantIdAllocationClient tenantIdAllocationClient;
  @MockitoBean private AgencyIdAllocationClient agencyIdAllocationClient;
  @MockitoBean private AgencyFacts agencyFacts;
  @MockitoBean private InviteAcceptUrlBuilder inviteAcceptUrlBuilder;
  @MockitoBean private InviteMailDispatchService inviteMailDispatchService;
  @MockitoBean private ConsultantAdminFacade consultantAdminFacade;
  @MockitoBean private CreateConsultantSaga createConsultantSaga;
  @MockitoBean private CounsellorAgencyAdminGrantService counsellorAgencyAdminGrantService;

  @MockitoBean
  private ConsultantAgencyRelationCreatorService consultantAgencyRelationCreatorService;

  @MockitoBean private IdentitySecondFactor identitySecondFactor;
  @MockitoBean private IdentityProfileLookup identityProfileLookup;
  @MockitoBean private TopicService topicService;
  @MockitoBean private UsernameTranscoder usernameTranscoder;
  @MockitoBean private AgencyCreationClient agencyCreationClient;

  @Autowired private InviteRoleChange roleChange;
  @Autowired private AccountInviteTopicPermissionService topicPermissions;
  @Autowired private javax.sql.DataSource dataSource;
  @Autowired private CounsellorOnboardingService wizard;
  @Autowired private InviteEmailTemplateRepository templateRepository;

  @Autowired
  private de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository deliveryRepository;

  private Long templateId;

  private ExecutorService executor;

  /** Counts the database sessions that wait for a row lock; each subclass knows its database. */
  abstract String lockWaitersQuery();

  @BeforeEach
  void setUp() {
    caller.revokeDecided = new CountDownLatch(1);
    caller.afterWriterRead = null;
    Tenants.actAs(
        caller,
        "tenant-admin-1",
        OWN_TENANT,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
    when(agencyIdAllocationClient.getAvailability(NEW_AGENCY))
        .thenReturn(IdAllocationStatus.RESERVED);
    when(agencyIdAllocationClient.release(anyLong())).thenReturn(true);
    when(identityEmailOwnerLookup.findByEmail(anyString())).thenReturn(java.util.Optional.empty());
    when(agencyService.getAgenciesWithoutCaching(java.util.List.of(EXISTING_AGENCY)))
        .thenReturn(
            java.util.List.of(
                new de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO()
                    .id(EXISTING_AGENCY)
                    .tenantId(OWN_TENANT)));
    when(agencyFacts.find(EXISTING_AGENCY))
        .thenReturn(
            java.util.Optional.of(
                new AgencyFacts.Agency(
                    EXISTING_AGENCY, OWN_TENANT, false, java.util.List.of(TOPIC))));
    when(acceptTimeAgencyCheck.serviceToken()).thenReturn("service-token");
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), anyString()))
        .thenReturn("https://dev.example.org/accept");
    executor =
        Executors.newFixedThreadPool(
            4,
            new java.util.concurrent.ThreadFactory() {
              private final java.util.concurrent.atomic.AtomicInteger count =
                  new java.util.concurrent.atomic.AtomicInteger();

              @Override
              public Thread newThread(Runnable work) {
                return new Thread(work, "revoke-race-worker-" + count.incrementAndGet());
              }
            });
  }

  /** Removes only this test's rows: on MariaDB the schema is shared with other contracts. */
  @AfterEach
  void cleanUp() {
    executor.shutdownNow();
    Tenants.acrossAll(
        () -> {
          adminAgencyRepository.deleteAll(
              java.util.stream.StreamSupport.stream(
                      adminAgencyRepository.findAll().spliterator(), false)
                  .filter(relation -> ADMIN_ID.equals(relation.getAdmin().getId()))
                  .toList());
          adminRepository.findById(ADMIN_ID).ifPresent(adminRepository::delete);
        });
    var ownInvites =
        accountInviteRepository.findAll().stream()
            .filter(invite -> invite.getRecipientEmail().startsWith("revoke-race"))
            .toList();
    var ownIds = ownInvites.stream().map(AccountInvite::getId).toList();
    deliveryRepository.deleteAll(
        deliveryRepository.findAll().stream()
            .filter(delivery -> ownIds.contains(delivery.getAccountInviteId()))
            .toList());
    accountInviteRepository.deleteAll(ownInvites);
    if (templateId != null) {
      templateRepository.deleteById(templateId);
    }
    releaseTaskRepository.deleteAll(
        releaseTaskRepository.findAll().stream()
            .filter(task -> Long.valueOf(NEW_AGENCY).equals(task.getReservedId()))
            .toList());
  }

  @Test
  void revokeWhileTheAccountIsBeingCreated_Should_Answer409_And_LeaveTheAcceptedInviteAlone()
      throws Exception {
    AccountInvite invite = persistedAgencyAdminInvite();
    CountDownLatch accountBeingCreated = new CountDownLatch(1);
    when(createAdminService.createNewAgencyAdminInTenant(any()))
        .thenAnswer(
            call -> {
              Admin admin = persistedAdmin(call.getArgument(0));
              accountBeingCreated.countDown();
              // Before the fix the revoke reads the invite here, unlocked; after it, it waits.
              caller.revokeDecided.await(REVOKE_READ_GRACE_MILLIS, TimeUnit.MILLISECONDS);
              return admin;
            });

    Future<Object> accept =
        submit(
            "revoke-race-invitee",
            () ->
                agencyAdminProvisioning.acceptAsAgencyAdmin(RAW_TOKEN, "race-admin", "Passw0rd!"));
    assertThat(accountBeingCreated.await(10, TimeUnit.SECONDS)).isTrue();
    Future<Object> revoke = submit(REVOKE_THREAD, () -> revokeAsAdmin(invite.getId()));

    Object accepted = accept.get(30, TimeUnit.SECONDS);
    Object revoked = revoke.get(30, TimeUnit.SECONDS);

    assertThat(accepted).isInstanceOf(AccountInvite.class);
    assertThat(revoked).isInstanceOf(CustomValidationHttpStatusException.class);
    CustomValidationHttpStatusException conflict = (CustomValidationHttpStatusException) revoked;
    assertThat(conflict.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getCustomHttpHeaders().getFirst("X-Reason"))
        .isEqualTo("INVITE_ALREADY_ACCEPTED");

    AccountInvite persisted = accountInviteRepository.findById(invite.getId()).orElseThrow();
    assertThat(persisted.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(persisted.getAcceptedByUserId()).isEqualTo(ADMIN_ID);
    assertThat(persisted.getProvisionedUserId()).isEqualTo(ADMIN_ID);
    assertThat(persisted.getProvisioningStatus())
        .isEqualTo(AccountInviteProvisioningStatus.COMPLETED);
    assertThat(persisted.getRevokedAt()).isNull();
    // The new account uses the agency number, so the losing revoke must not give it back.
    verify(agencyIdAllocationClient, never()).release(anyLong());
    assertThat(releaseTaskRepository.count()).isZero();
    verify(identityAccountRemover, never()).rollbackUser(anyString());
  }

  @Test
  void acceptWhileTheRevokeIsCommitting_Should_CreateNoAccount_And_ReleaseTheNumberOnce()
      throws Exception {
    AccountInvite invite = persistedAgencyAdminInvite();
    CountDownLatch revokeHoldsRow = new CountDownLatch(1);
    CountDownLatch inviteeArrived = new CountDownLatch(1);

    Future<Object> revoke =
        submit(
            REVOKE_THREAD,
            () ->
                new TransactionTemplate(transactionManager)
                    .execute(
                        transaction -> {
                          AccountInvite revoked = revokeAsAdmin(invite.getId());
                          revokeHoldsRow.countDown();
                          awaitQuietly(inviteeArrived);
                          // Commit only once the invitee waits for this row.
                          assertThat(awaitLockWaiter()).isTrue();
                          return revoked;
                        }));
    assertThat(revokeHoldsRow.await(10, TimeUnit.SECONDS)).isTrue();
    Future<Object> accept =
        submit(
            "revoke-race-invitee",
            () -> {
              inviteeArrived.countDown();
              return agencyAdminProvisioning.acceptAsAgencyAdmin(
                  RAW_TOKEN, "race-admin", "Passw0rd!");
            });
    assertThat(inviteeArrived.await(10, TimeUnit.SECONDS)).isTrue();

    Object revoked = revoke.get(30, TimeUnit.SECONDS);
    Object accepted = accept.get(30, TimeUnit.SECONDS);

    assertThat(revoked).isInstanceOf(AccountInvite.class);
    assertThat(accepted).isInstanceOf(AccountInviteLinkException.class);
    assertThat(((AccountInviteLinkException) accepted).getReason())
        .isEqualTo(AccountInviteLinkException.Reason.REVOKED);
    verify(createAdminService, never()).createNewAgencyAdminInTenant(any());

    AccountInvite persisted = accountInviteRepository.findById(invite.getId()).orElseThrow();
    assertThat(persisted.getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    assertThat(persisted.getAcceptedByUserId()).isNull();
    assertThat(persisted.getProvisionedUserId()).isNull();
    verify(agencyIdAllocationClient, times(1)).release(NEW_AGENCY);
  }

  @Test
  void secondRevoke_Should_ReturnTheRevokedInvite_WithoutGivingTheNumberBackAgain() {
    AccountInvite invite = persistedAgencyAdminInvite();

    service.revokeInvite(invite.getId());
    AccountInvite again = service.revokeInvite(invite.getId());

    assertThat(again.getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    verify(agencyIdAllocationClient, times(1)).release(NEW_AGENCY);
  }

  // --- admin writers: a revoke that holds the row must never be written over ---

  @Test
  void sendWhileARevokeHoldsTheRow_Should_NotMailOrReviveTheInvite() throws Exception {
    AccountInvite invite = persistedAgencyAdminInvite();
    Long template = persistedTemplate();
    CountDownLatch writerRead = new CountDownLatch(1);
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), anyString()))
        .thenAnswer(
            call -> {
              writerRead.countDown();
              return "https://dev.example.org/accept";
            });
    mailGoesOut();

    Object sent =
        writeWhileRevokeHoldsTheRow(
            invite.getId(),
            writerRead,
            () ->
                service.sendInvite(
                    new AccountInviteService.SendInviteCommand(invite.getId(), template)));

    assertThat(sent).isInstanceOf(RuntimeException.class);
    assertThat(reload(invite).getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    verify(inviteMailDispatchService, never())
        .send(anyString(), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void resendWhileARevokeHoldsTheRow_Should_CreateNoReplacement() throws Exception {
    AccountInvite invite = persistedAgencyAdminInvite();
    Long template = persistedTemplate();
    CountDownLatch writerRead = new CountDownLatch(1);
    when(identityEmailOwnerLookup.findByEmail(anyString()))
        .thenAnswer(
            call -> {
              writerRead.countDown();
              return java.util.Optional.empty();
            });
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), anyString()))
        .thenReturn("https://dev.example.org/accept");
    mailGoesOut();

    Object resent =
        writeWhileRevokeHoldsTheRow(
            invite.getId(),
            writerRead,
            () ->
                service.resendInvite(
                    new AccountInviteService.SendInviteCommand(invite.getId(), template)));

    assertThat(resent).isInstanceOf(RuntimeException.class);
    assertThat(reload(invite).getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    assertThat(
            accountInviteRepository.findAll().stream()
                .filter(each -> RECIPIENT.equals(each.getRecipientEmail())))
        .hasSize(1);
    verify(inviteMailDispatchService, never())
        .send(anyString(), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void roleChangeWhileARevokeHoldsTheRow_Should_Answer409_And_KeepTheRevoke() throws Exception {
    AccountInvite invite = persistedAgencyAdminInviteIntoExistingAgency();
    CountDownLatch writerRead = new CountDownLatch(1);
    when(agencyService.getAgenciesWithoutCaching(java.util.List.of(EXISTING_AGENCY)))
        .thenAnswer(
            call -> {
              writerRead.countDown();
              return java.util.List.of(
                  new de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO()
                      .id(EXISTING_AGENCY)
                      .tenantId(OWN_TENANT));
            });

    Object changed =
        writeWhileRevokeHoldsTheRow(
            invite.getId(),
            writerRead,
            () ->
                roleChange.change(
                    invite.getId(),
                    new InviteRoleChange.ChangeRoleCommand(
                        AccountInviteTargetRole.AGENCY_ADMIN, false)));

    assertThat(changed).isInstanceOf(CustomValidationHttpStatusException.class);
    assertThat(
            ((CustomValidationHttpStatusException) changed)
                .getCustomHttpHeaders()
                .getFirst("X-Reason"))
        .isEqualTo("INVITE_NOT_PENDING");
    AccountInvite persisted = reload(invite);
    assertThat(persisted.getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    assertThat(persisted.getAlsoCounsellor()).isTrue();
  }

  // --- reviewer round: send outside the lock, every writer locked, expiry, ledger, wizard ---

  @Test
  void manualSendWithAFailingMailServer_Should_RecordTheFailedDelivery_WithoutWaiting()
      throws Exception {
    AccountInvite invite = persistedAgencyAdminInvite();
    Long template = persistedTemplate();
    when(inviteMailDispatchService.send(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenThrow(
            new SmtpSendException(
                SmtpSendException.Category.SMTP_TRANSPORT_FAILED,
                SmtpSendException.DeliveryDisposition.CONFIRMED_NOT_SENT,
                "SMTP refused the message"));

    long started = System.nanoTime();
    Object sent =
        submit(
                WRITER_THREAD,
                () ->
                    service.sendInvite(
                        new AccountInviteService.SendInviteCommand(invite.getId(), template)))
            .get(30, TimeUnit.SECONDS);
    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(sent).isInstanceOf(SmtpSendException.class);
    // The FAILED audit row references the invite; it must not wait for the sender's own lock.
    assertThat(millis).isLessThan(2_500L);
    assertThat(deliveryRepository.findAll())
        .filteredOn(delivery -> invite.getId().equals(delivery.getAccountInviteId()))
        .extracting(de.caritas.cob.userservice.api.model.InviteEmailDelivery::getStatus)
        .containsExactly(InviteEmailDeliveryStatus.FAILED);
    AccountInvite persisted = reload(invite);
    assertThat(persisted.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    // Nothing went out, so the link the invitee already has keeps working.
    assertThat(persisted.getTokenHash()).isEqualTo(AccountInviteService.hash(RAW_TOKEN));
  }

  @Test
  void waiveWhileARevokeLands_Should_KeepTheRevoke() throws Exception {
    AccountInvite invite = persistedAgencyAdminInvite();

    Object waived =
        writeWhileAnotherWriteLands(
            () ->
                service.waiveTwoFactor(
                    invite.getId(), new AccountInviteService.WaiveTwoFactorCommand("on paper")),
            () -> revokeAsAdmin(invite.getId()));

    assertThat(waived).isInstanceOf(AccountInvite.class);
    AccountInvite persisted = reload(invite);
    assertThat(persisted.getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    assertThat(persisted.getTwoFactorStatus()).isEqualTo(TwoFactorGateStatus.WAIVED);
  }

  @Test
  void waiveWhileAnAcceptLands_Should_KeepTheAcceptance() throws Exception {
    AccountInvite invite = persistedAgencyAdminInvite();

    writeWhileAnotherWriteLands(
        () ->
            service.waiveTwoFactor(
                invite.getId(), new AccountInviteService.WaiveTwoFactorCommand("on paper")),
        () -> service.acceptInvite(RAW_TOKEN, "race-acceptor"));

    AccountInvite persisted = reload(invite);
    assertThat(persisted.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(persisted.getAcceptedByUserId()).isEqualTo("race-acceptor");
    assertThat(persisted.getTwoFactorStatus()).isEqualTo(TwoFactorGateStatus.WAIVED);
  }

  @Test
  void topicPermissionWhileARevokeLands_Should_KeepTheRevoke() throws Exception {
    AccountInvite invite = persistedCounsellorInvite();
    readTheAgencyTopicsAsTheWriterHook();

    writeWhileAnotherWriteLands(
        () -> topicPermissions.updatePermission(invite.getId(), TopicPermission.SELECT_EXISTING),
        () -> revokeAsAdmin(invite.getId()));

    AccountInvite persisted = reload(invite);
    assertThat(persisted.getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    assertThat(persisted.getTopicPermission()).isEqualTo(TopicPermission.SELECT_EXISTING);
  }

  @Test
  void topicPermissionWhileAnAcceptLands_Should_KeepTheAcceptance() throws Exception {
    AccountInvite invite = persistedCounsellorInvite();
    readTheAgencyTopicsAsTheWriterHook();

    writeWhileAnotherWriteLands(
        () -> topicPermissions.updatePermission(invite.getId(), TopicPermission.SELECT_EXISTING),
        () -> service.acceptInvite(RAW_TOKEN, "race-acceptor"));

    AccountInvite persisted = reload(invite);
    assertThat(persisted.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(persisted.getAcceptedByUserId()).isEqualTo("race-acceptor");
  }

  @Test
  void sendWhileARoleChangeLands_Should_NotWriteTheOldRoleBack() throws Exception {
    AccountInvite invite = persistedCounsellorInvite();
    Long template = persistedTemplate();
    mailGoesOut();

    Object sent =
        writeWhileAnotherWriteLands(
            () ->
                service.sendInvite(
                    new AccountInviteService.SendInviteCommand(invite.getId(), template)),
            () ->
                roleChange.change(
                    invite.getId(),
                    new InviteRoleChange.ChangeRoleCommand(
                        AccountInviteTargetRole.AGENCY_ADMIN, false)));

    assertThat(sent).isInstanceOf(AccountInviteService.InviteSendResult.class);
    assertThat(reload(invite).getTargetRole()).isEqualTo(AccountInviteTargetRole.AGENCY_ADMIN);
  }

  @Test
  void resendOfAWaitingInviteThatIsReleasedMeanwhile_Should_SendIt() throws Exception {
    AccountInvite invite = persistedCounsellorInvite();
    invite.setStatus(AccountInviteStatus.WAITING_FOR_UNIT);
    invite.setWaitingForUnit(InviteUnitType.AGENCY);
    invite.setTokenHash(null);
    invite.setExpiresAt(null);
    AccountInvite waiting = accountInviteRepository.save(invite);
    Long template = persistedTemplate();
    mailGoesOut();

    Object resent =
        writeWhileAnotherWriteLands(
            () ->
                service.resendInvite(
                    new AccountInviteService.SendInviteCommand(waiting.getId(), template)),
            () -> {
              // The unit queue releases the invite between the resend's two reads.
              AccountInvite released = reload(waiting);
              released.setStatus(AccountInviteStatus.EMAIL_SENT);
              released.setWaitingForUnit(null);
              released.setTokenHash(AccountInviteService.hash("released-token"));
              released.setExpiresAt(LocalDateTime.now().plusDays(30));
              return accountInviteRepository.save(released);
            });

    assertThat(resent).isInstanceOf(AccountInviteService.InviteSendResult.class);
    assertThat(reload(waiting).getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
  }

  @Test
  void revokeWhileAnotherRequestHoldsTheRowTooLong_Should_Answer409Busy() throws Exception {
    AccountInvite invite = persistedAgencyAdminInvite();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch letGo = new CountDownLatch(1);
    Future<Object> holder =
        submit(
            "revoke-race-slow-holder",
            () ->
                new TransactionTemplate(transactionManager)
                    .execute(
                        transaction -> {
                          accountInviteRepository.findByIdForUpdate(invite.getId());
                          holding.countDown();
                          awaitQuietly(letGo, 15_000L);
                          return null;
                        }));
    assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

    Object revoked =
        submit(REVOKE_THREAD, () -> revokeAsAdmin(invite.getId())).get(30, TimeUnit.SECONDS);
    letGo.countDown();
    holder.get(30, TimeUnit.SECONDS);

    assertThat(revoked).isInstanceOf(CustomValidationHttpStatusException.class);
    CustomValidationHttpStatusException busy = (CustomValidationHttpStatusException) revoked;
    assertThat(busy.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(busy.getCustomHttpHeaders().getFirst("X-Reason")).isEqualTo("INVITE_BUSY");
    assertThat(reload(invite).getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
  }

  @Test
  void resendWhoseMailFailsAfterTheOldInviteWasRevoked_Should_LeaveNoOpenReplacement()
      throws Exception {
    AccountInvite invite = persistedAgencyAdminInvite();
    Long template = persistedTemplate();
    when(inviteMailDispatchService.send(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenAnswer(
            call -> {
              // An admin revokes the replaced invite while the mail server refuses the resend.
              submit(REVOKE_THREAD, () -> revokeAsAdmin(invite.getId())).get(10, TimeUnit.SECONDS);
              throw new SmtpSendException(
                  SmtpSendException.Category.SMTP_TRANSPORT_FAILED,
                  SmtpSendException.DeliveryDisposition.CONFIRMED_NOT_SENT,
                  "SMTP refused the message");
            });

    Object resent =
        submit(
                WRITER_THREAD,
                () ->
                    service.resendInvite(
                        new AccountInviteService.SendInviteCommand(invite.getId(), template)))
            .get(30, TimeUnit.SECONDS);

    assertThat(resent).isInstanceOf(SmtpSendException.class);
    assertThat(reload(invite).getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    // The never-sent replacement must not keep the address or the number.
    assertThat(ownInvites())
        .extracting(AccountInvite::getStatus)
        .doesNotContain(AccountInviteStatus.EMAIL_SENT);
    verify(agencyIdAllocationClient, times(1)).release(NEW_AGENCY);
  }

  @Test
  void expirySweepWhileARevokeHoldsTheRow_Should_KeepTheRevoke() throws Exception {
    AccountInvite invite = persistedAgencyAdminInvite();
    invite.setExpiresAt(LocalDateTime.now().minusDays(1));
    accountInviteRepository.save(invite);

    Object swept =
        writeWhileRevokeHoldsTheRow(
            invite.getId(), new CountDownLatch(1), () -> service.expireElapsedInvites());

    assertThat(swept).isNotInstanceOf(Exception.class);
    assertThat(reload(invite).getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    verify(agencyIdAllocationClient, times(1)).release(NEW_AGENCY);
  }

  @Test
  void twoRevokesOfTheAdminsOfOneNewAgency_Should_ReleaseItsNumberExactlyOnce() throws Exception {
    AccountInvite first = persistedAgencyAdminInvite();
    AccountInvite second = persistedSecondAgencyAdminInvite();
    CountDownLatch firstHoldsRow = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    Future<Object>[] secondRevoke = new Future[1];

    Future<Object> firstRevoke =
        submit(
            REVOKE_THREAD,
            () ->
                new TransactionTemplate(transactionManager)
                    .execute(
                        transaction -> {
                          AccountInvite revoked = revokeAsAdmin(first.getId());
                          firstHoldsRow.countDown();
                          awaitQuietly(secondStarted);
                          // Commit once the second revoke has finished or waits for this one.
                          awaitDoneOrLockWaiter(secondRevoke);
                          return revoked;
                        }));
    assertThat(firstHoldsRow.await(10, TimeUnit.SECONDS)).isTrue();
    secondRevoke[0] =
        submit(
            REVOKE_THREAD + "-second",
            () -> {
              secondStarted.countDown();
              return revokeAsAdmin(second.getId());
            });

    assertThat(firstRevoke.get(30, TimeUnit.SECONDS)).isInstanceOf(AccountInvite.class);
    assertThat(secondRevoke[0].get(30, TimeUnit.SECONDS)).isInstanceOf(AccountInvite.class);
    verify(agencyIdAllocationClient, times(1)).release(NEW_AGENCY);
  }

  @Test
  void revokeRightBeforeTheFoundingCounsellorWizardCreatesTheAgency_Should_CreateNothing()
      throws Exception {
    AccountInvite invite = persistedAgencyAdminInvite();
    CountDownLatch wizardRead = new CountDownLatch(1);
    CountDownLatch revokeCommitted = new CountDownLatch(1);
    when(agencyService.getAgencyWithoutCaching(NEW_AGENCY))
        .thenAnswer(
            call -> {
              wizardRead.countDown();
              revokeCommitted.await(10, TimeUnit.SECONDS);
              return null;
            });
    topicsExist();

    Future<Object> registration =
        submit(
            "revoke-race-invitee",
            () -> wizard.registerCounsellor(RAW_TOKEN, counselling(true, "Neue Beratungsstelle")));
    assertThat(wizardRead.await(10, TimeUnit.SECONDS)).isTrue();
    Object revoked =
        submit(
                REVOKE_THREAD,
                () -> {
                  AccountInvite result = revokeAsAdmin(invite.getId());
                  revokeCommitted.countDown();
                  return result;
                })
            .get(30, TimeUnit.SECONDS);
    Object registered = registration.get(30, TimeUnit.SECONDS);

    assertThat(revoked).isInstanceOf(AccountInvite.class);
    assertThat(registered).isInstanceOf(AccountInviteLinkException.class);
    verify(agencyCreationClient, never()).createAgencyWithReservedId(any(), any(), any(), any());
    verify(consultantAdminFacade, never()).createNewConsultant(any());
  }

  @Test
  void wizardWhileTheRoleChangesToAgencyAdmin_Should_Answer409_And_NotConsumeTheInvite()
      throws Exception {
    AccountInvite invite = persistedCounsellorInvite();
    topicsExist();
    when(agencyService.getAgencyWithoutCaching(EXISTING_AGENCY))
        .thenAnswer(
            call -> {
              // The wizard decided "counsellor" from its first read; the admin now changes the
              // role.
              interleave(
                  () ->
                      roleChange.change(
                          invite.getId(),
                          new InviteRoleChange.ChangeRoleCommand(
                              AccountInviteTargetRole.AGENCY_ADMIN, false)));
              return new de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO()
                  .id(EXISTING_AGENCY)
                  .tenantId(OWN_TENANT)
                  .topicIds(java.util.List.of(TOPIC));
            });

    Object registered =
        submit(WRITER_THREAD, () -> wizard.registerCounsellor(RAW_TOKEN, counselling(null, null)))
            .get(30, TimeUnit.SECONDS);

    assertThat(registered).isInstanceOf(CustomValidationHttpStatusException.class);
    assertThat(
            ((CustomValidationHttpStatusException) registered)
                .getCustomHttpHeaders()
                .getFirst("X-Reason"))
        .isEqualTo("INVITE_CHANGED");
    AccountInvite persisted = reload(invite);
    assertThat(persisted.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(persisted.getTargetRole()).isEqualTo(AccountInviteTargetRole.AGENCY_ADMIN);
    verify(consultantAdminFacade, never()).createNewConsultant(any());
  }

  /**
   * Runs {@code writer} on the writer thread; right after it read the invite, {@code other} commits
   * (before the fix) or waits for the writer's row lock (after it).
   */
  private Object writeWhileAnotherWriteLands(Callable<Object> writer, Callable<Object> other)
      throws Exception {
    caller.afterWriterRead = () -> interleave(other);
    Object written = submit(WRITER_THREAD, writer).get(30, TimeUnit.SECONDS);
    Future<Object> landed = otherWrite;
    if (landed != null) {
      landed.get(30, TimeUnit.SECONDS);
    }
    return written;
  }

  private volatile Future<Object> otherWrite;

  /** Starts {@code other} and gives it up to the grace period to commit. */
  private void interleave(Callable<Object> other) {
    otherWrite =
        submit(
            "revoke-race-other",
            () -> {
              Tenants.actIn(OWN_TENANT);
              return other.call();
            });
    try {
      otherWrite.get(REVOKE_READ_GRACE_MILLIS, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException blockedByTheWriter) {
      // After the fix the other write waits for the writer's row lock.
    } catch (Exception unexpected) {
      throw new IllegalStateException(unexpected);
    }
  }

  private void readTheAgencyTopicsAsTheWriterHook() {
    when(agencyFacts.find(EXISTING_AGENCY))
        .thenAnswer(
            call -> {
              if (WRITER_THREAD.equals(Thread.currentThread().getName())) {
                Runnable hook = caller.afterWriterRead;
                caller.afterWriterRead = null;
                if (hook != null) {
                  hook.run();
                }
              }
              return java.util.Optional.of(
                  new AgencyFacts.Agency(
                      EXISTING_AGENCY, OWN_TENANT, false, java.util.List.of(TOPIC)));
            });
  }

  private void topicsExist() {
    when(topicService.getAllActiveTopicsMap())
        .thenReturn(
            java.util.Map.of(
                TOPIC,
                new de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO()
                    .id(TOPIC)
                    .name("Sucht")));
  }

  private static CounsellorOnboardingService.RegisterCounsellorCommand counselling(
      Boolean alsoCounsellor, String agencyName) {
    return new CounsellorOnboardingService.RegisterCounsellorCommand(
        "race-counsellor",
        "Passw0rd!",
        null,
        null,
        null,
        null,
        null,
        java.util.List.of(TOPIC),
        null,
        null,
        agencyName,
        alsoCounsellor);
  }

  /** MariaDB refreshes INNODB_TRX only when nobody read it for 100 ms. */
  private static final long POLL_MILLIS = 200L;

  /** True once a database session waits for a row lock, false after 5 s. */
  private boolean awaitLockWaiter() {
    var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
    long deadline = System.currentTimeMillis() + 5_000L;
    while (System.currentTimeMillis() < deadline) {
      Integer waiting = jdbc.queryForObject(lockWaitersQuery(), Integer.class);
      if (waiting != null && waiting > 0) {
        return true;
      }
      sleepQuietly(POLL_MILLIS);
    }
    return false;
  }

  private void awaitDoneOrLockWaiter(Future<Object>[] other) {
    var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
    long deadline = System.currentTimeMillis() + 5_000L;
    while (System.currentTimeMillis() < deadline) {
      if (other[0] != null && other[0].isDone()) {
        return;
      }
      Integer waiting = jdbc.queryForObject(lockWaitersQuery(), Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      sleepQuietly(POLL_MILLIS);
    }
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private java.util.List<AccountInvite> ownInvites() {
    return accountInviteRepository.findAll().stream()
        .filter(each -> each.getRecipientEmail().startsWith("revoke-race"))
        .toList();
  }

  /** A counsellor for an agency that exists; nothing reserved. */
  private AccountInvite persistedCounsellorInvite() {
    AccountInvite invite = persistedAgencyAdminInvite();
    invite.setTargetRole(AccountInviteTargetRole.COUNSELLOR);
    invite.setAgencyId(EXISTING_AGENCY);
    invite.setAgencyIdAllocationMode(null);
    invite.setAlsoCounsellor(null);
    invite.setTopicPermission(TopicPermission.CREATE);
    return accountInviteRepository.save(invite);
  }

  /** A second first admin of the same new Beratungsstelle; both share its number. */
  private AccountInvite persistedSecondAgencyAdminInvite() {
    AccountInvite invite = newAgencyAdminInvite();
    invite.setRecipientEmail(SECOND_RECIPIENT);
    invite.setActiveRecipientKey(SECOND_RECIPIENT);
    invite.setTokenHash(AccountInviteService.hash(SECOND_TOKEN));
    return accountInviteRepository.save(invite);
  }

  // --- founding wizard: the Beratungsstelle is created only by the accept that holds the row ---

  @Test
  void revokeRightBeforeTheFoundingWizardCreatesTheAgency_Should_CreateNoAgency() throws Exception {
    AccountInvite invite = persistedAgencyAdminInvite();
    CountDownLatch wizardRead = new CountDownLatch(1);
    CountDownLatch revokeCommitted = new CountDownLatch(1);
    when(agencyService.getAgencyWithoutCaching(NEW_AGENCY))
        .thenAnswer(
            call -> {
              // The wizard has read the invite as open; the admin's revoke commits right now.
              wizardRead.countDown();
              revokeCommitted.await(10, TimeUnit.SECONDS);
              return null;
            });
    when(topicService.getAllActiveTopicsMap())
        .thenReturn(
            java.util.Map.of(
                TOPIC,
                new de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO()
                    .id(TOPIC)
                    .name("Sucht")));
    when(agencyCreationClient.createAgencyWithReservedId(any(), any(), any(), any()))
        .thenReturn(NEW_AGENCY);

    Future<Object> registration =
        submit(
            "revoke-race-invitee",
            () ->
                wizard.registerCounsellor(
                    RAW_TOKEN,
                    new CounsellorOnboardingService.RegisterCounsellorCommand(
                        "race-admin",
                        "Passw0rd!",
                        null,
                        null,
                        null,
                        null,
                        null,
                        java.util.List.of(TOPIC),
                        null,
                        null,
                        "Neue Beratungsstelle",
                        false)));
    assertThat(wizardRead.await(10, TimeUnit.SECONDS)).isTrue();
    Object revoked =
        submit(
                REVOKE_THREAD,
                () -> {
                  AccountInvite result = revokeAsAdmin(invite.getId());
                  revokeCommitted.countDown();
                  return result;
                })
            .get(30, TimeUnit.SECONDS);
    Object registered = registration.get(30, TimeUnit.SECONDS);

    assertThat(revoked).isInstanceOf(AccountInvite.class);
    assertThat(registered).isInstanceOf(AccountInviteLinkException.class);
    assertThat(((AccountInviteLinkException) registered).getReason())
        .isEqualTo(AccountInviteLinkException.Reason.REVOKED);
    verify(agencyCreationClient, never()).createAgencyWithReservedId(any(), any(), any(), any());
    verify(createAdminService, never()).createNewAgencyAdminInTenant(any());
  }

  /**
   * The admin's revoke holds the row until the other writer has read the invite as open (before the
   * fix) or 1 s passed (after it, the writer waits for the row), then commits.
   */
  private Object writeWhileRevokeHoldsTheRow(
      Long inviteId, CountDownLatch writerRead, Callable<Object> writer) throws Exception {
    CountDownLatch revokeHoldsRow = new CountDownLatch(1);
    Future<Object> revoke =
        submit(
            REVOKE_THREAD,
            () ->
                new TransactionTemplate(transactionManager)
                    .execute(
                        transaction -> {
                          AccountInvite revoked = revokeAsAdmin(inviteId);
                          revokeHoldsRow.countDown();
                          awaitQuietly(writerRead);
                          return revoked;
                        }));
    assertThat(revokeHoldsRow.await(10, TimeUnit.SECONDS)).isTrue();
    Future<Object> write =
        submit(
            "revoke-race-other-admin",
            () -> {
              Tenants.actIn(OWN_TENANT);
              return writer.call();
            });
    assertThat(revoke.get(30, TimeUnit.SECONDS)).isInstanceOf(AccountInvite.class);
    return write.get(30, TimeUnit.SECONDS);
  }

  private void mailGoesOut() {
    when(inviteMailDispatchService.send(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(
            new de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt(
                RECIPIENT, java.time.Instant.now()));
  }

  private Long persistedTemplate() {
    templateId =
        templateRepository
            .save(
                InviteEmailTemplate.builder()
                    .kind(InviteEmailTemplateKind.COUNSELLOR_INVITE)
                    .name("revoke-race")
                    .language("de")
                    .subject("Einladung")
                    .body("Hallo {{firstName}}")
                    .active(true)
                    .createDate(LocalDateTime.now())
                    .build())
            .getId();
    return templateId;
  }

  private AccountInvite reload(AccountInvite invite) {
    return accountInviteRepository.findById(invite.getId()).orElseThrow();
  }

  /** An agency admin for an agency that exists, who would also counsel. */
  private AccountInvite persistedAgencyAdminInviteIntoExistingAgency() {
    AccountInvite invite = persistedAgencyAdminInvite();
    invite.setAgencyId(EXISTING_AGENCY);
    invite.setAgencyIdAllocationMode(null);
    invite.setAlsoCounsellor(true);
    return accountInviteRepository.save(invite);
  }

  private AccountInvite revokeAsAdmin(Long inviteId) {
    Tenants.actIn(OWN_TENANT);
    return service.revokeInvite(inviteId);
  }

  private Future<Object> submit(String threadName, Callable<Object> work) {
    return executor.submit(
        () -> {
          Thread.currentThread().setName(threadName);
          try {
            return work.call();
          } catch (Exception exception) {
            return exception;
          }
        });
  }

  private static void awaitQuietly(CountDownLatch latch) {
    awaitQuietly(latch, REVOKE_READ_GRACE_MILLIS);
  }

  private static void awaitQuietly(CountDownLatch latch, long millis) {
    try {
      latch.await(millis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private Admin persistedAdmin(CreateAdminDTO dto) {
    LocalDateTime now = LocalDateTime.now();
    return adminRepository.save(
        Admin.builder()
            .id(ADMIN_ID)
            .tenantId(OWN_TENANT)
            .username(dto.getUsername())
            .firstName(dto.getFirstname())
            .lastName(dto.getLastname())
            .email(dto.getEmail())
            .type(Admin.AdminType.AGENCY)
            .createDate(now)
            .updateDate(now)
            .build());
  }

  /** The first admin of a new Beratungsstelle; the invite holds its reserved number. */
  private AccountInvite persistedAgencyAdminInvite() {
    return accountInviteRepository.save(newAgencyAdminInvite());
  }

  private static AccountInvite newAgencyAdminInvite() {
    LocalDateTime now = LocalDateTime.now();
    return AccountInvite.builder()
        .targetRole(AccountInviteTargetRole.AGENCY_ADMIN)
        .tenantId(OWN_TENANT)
        .agencyId(NEW_AGENCY)
        .agencyIdAllocationMode(IdAllocationMode.MANUAL)
        .alsoCounsellor(false)
        .recipientEmail(RECIPIENT)
        .activeRecipientKey(RECIPIENT)
        .tokenHash(AccountInviteService.hash(RAW_TOKEN))
        .status(AccountInviteStatus.EMAIL_SENT)
        .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
        .emailVerificationStatus(EmailVerificationStatus.PENDING)
        .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
        .expiresAt(now.plusDays(30))
        .createdByUserId("tenant-admin-1")
        .createdByUsername("tenant-admin-1")
        .createDate(now)
        .updateDate(now)
        .build();
  }
}
