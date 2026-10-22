package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedInvitations;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdReservation;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/**
 * Real JPA public release/email reuse; independent final native proof is the explicit port double.
 */
@TestPropertySource(properties = "TASK_IDENTITY_MODEL=FIVE_ACTORS")
class ProtectedInvitationRecipientKeyReleaseJpaIT extends ProtectedInvitationIssuanceJpaFixture {
  @MockitoBean ProtectedInvitationCancellation cancellation;
  @Autowired ProtectedInvitationIssuance issuance;
  @Autowired JdbcProtectedInvitationJournal journal;
  @Autowired ProtectedInvitationCodec approvalCodec;
  @Autowired AccountLifecycleCodec json;

  @Test
  void restartedActualRecoverySweepReleasesANeverCreatedDraftOnlyAfterItsSavedNativeAck()
      throws Exception {
    distinctReservationForRejectedNewInvite();
    when(tenantIds.release(17L)).thenReturn(true);
    when(tenantIds.reserve(null))
        .thenReturn(
            new TenantIdReservation(17L, "9eb4c868-16d4-4be5-8c0b-5fb9d2b866b3"),
            new TenantIdReservation(18L, "59ba921a-849d-4f32-a492-98d03ff582b0"),
            new TenantIdReservation(17L, "99d529dd-ef5c-46c2-92ed-56c005c8c026"));
    String[] migrations = {
      "20261022_protected_invitation_cancel_367",
      "20261022_protected_invitation_acceptance_367",
      "20261022_protected_account_activation_367",
      "20261022_protected_account_creation_367",
      "20261022_protected_invitation_cleanup_status_367"
    };
    for (String migration : migrations)
      new ResourceDatabasePopulator(
              new ClassPathResource("db/changelog/changeset/" + migration + "/migrate.sql"))
          .execute(source);
    try {
      var clock = Clock.systemUTC();
      var restrictions =
          new ProtectedInvitationRestrictionCodec(
              approvalCodec,
              json,
              KEY,
              clock,
              "native-synthetic-realm",
              "https://auth.example.test/realms/oriso");
      var accounts =
          new ProtectedAccountCodec(
              KEY,
              clock,
              approvalCodec,
              "oriso",
              "native-synthetic-realm",
              "https://auth.example.test/realms/oriso");
      var finalCodec = new ProtectedCleanupStatusCodec(json, restrictions, accounts, KEY);
      var acceptance =
          new JdbcProtectedInvitationAcceptance(
              jdbc, transactions, journal, approvalCodec, json, clock);
      var creation =
          new JdbcProtectedAccountCreation(jdbc, transactions, journal, accounts, json, clock);
      var receiver = mock(KeycloakProtectedRestrictions.class);
      var rpc = new AtomicInteger();
      doAnswer(
              call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                if (rpc.incrementAndGet() == 1)
                  throw new KeycloakProtectedInvitations.RemoteFailure(0);
                return absentAck(call.getArgument(0), restrictions);
              })
          .when(receiver)
          .cancel(any());
      java.util.function.Supplier<JdbcProtectedInvitationCancellation> restart =
          () ->
              new JdbcProtectedInvitationCancellation(
                  jdbc,
                  transactions,
                  journal,
                  restrictions,
                  json,
                  receiver,
                  id -> issuance.restrictionProposal(invites.findById(id).orElseThrow()),
                  clock,
                  finalCodec,
                  accounts,
                  acceptance,
                  creation);
      var original = service.createInvite(command());
      assertThat(original.getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
      service.configureProtectedCancellation(restart.get());
      assertThrows(
          KeycloakProtectedInvitations.RemoteFailure.class,
          () -> service.revokeInvite(original.getId()));
      assertThat(invites.findById(original.getId()).orElseThrow().getActiveRecipientKey())
          .isEqualTo(RECIPIENT);
      assertThatThrownBy(() -> service.createInvite(command()))
          .isInstanceOf(RuntimeException.class);
      verify(tenantIds, never()).release(17L);
      // Reconcile the same reducing intent, then lose the foreground after its durable ACK.
      restart
          .get()
          .cancel(
              original.getId(),
              ProtectedInvitationCancellation.Reason.REVOKED,
              () -> {
                throw new AssertionError("Saved invalidation must not run twice");
              });
      String firstAck =
          jdbc.queryForObject(
              "SELECT response_json FROM protected_invitation_cancellation WHERE invite_id=?",
              String.class,
              original.getId());
      assertThat(firstAck).isNotBlank();
      assertThat(
              jdbc.queryForObject("SELECT COUNT(*) FROM protected_account_creation", Integer.class))
          .isZero();
      var restarted = restart.get();
      service.configureProtectedCancellation(restarted);
      var nativeAccounts = mock(KeycloakProtectedAccounts.class);
      var otp = mock(ProtectedOtpBootstrap.class);
      var activation = mock(JdbcProtectedAccountActivation.class);
      var recovery = mock(ProtectedAccountRecovery.class);
      var coordinator =
          spy(
              new ProtectedAdminOnboardingService(
                  service,
                  actors,
                  acceptance,
                  accounts,
                  creation,
                  nativeAccounts,
                  otp,
                  activation,
                  mock(
                      de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService
                          .class),
                  mock(de.caritas.cob.userservice.api.helper.UsernameTranscoder.class),
                  new ProtectedInvitationProfiles(
                      "native-synthetic-realm",
                      Map.of(
                          AccountInviteTargetRole.TENANT_ADMIN, "tenant-owner",
                          AccountInviteTargetRole.PLATFORM_ADMIN, "platform-v1")),
                  mock(OperatorDpaContentClient.class),
                  mock(TenantCreationClient.class),
                  clock,
                  recovery,
                  restarted));
      var claims =
          mock(de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService.class);
      var lease =
          new de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService
              .ClaimLease(
              "protected-invitation-terminal-recovery", LocalDateTime.now().plusMinutes(5));
      when(claims.tryClaimLease(anyString(), any())).thenReturn(Optional.of(lease));
      var identity = mock(de.caritas.cob.userservice.api.port.out.IdentityClientConfig.class);
      var authentication =
          mock(de.caritas.cob.userservice.api.port.out.IdentityAuthentication.class);
      var tenant = mock(de.caritas.cob.userservice.api.tenant.TenantContextProvider.class);
      when(identity.isFiveActorMode()).thenReturn(true);
      when(identity.taskAccessToken(
              de.caritas.cob.userservice.api.config.auth.TaskPurpose.INVITE_RESERVATIONS))
          .thenReturn("synthetic-current-setup-memory-only");
      doAnswer(
              call -> {
                de.caritas.cob.userservice.api.tenant.TenantContext.setCurrentTenant(0L);
                return null;
              })
          .when(tenant)
          .setTechnicalContextIfMultiTenancyIsEnabled();
      var sweep =
          new ProtectedInvitationRecoverySweep(
              actors,
              claims,
              restarted,
              coordinator,
              service,
              clock,
              identity,
              authentication,
              tenant);
      sweep.retryPending();
      verify(coordinator).reconcileTerminal(original.getId());
      var released = invites.findById(original.getId()).orElseThrow();
      assertThat(released.getActiveRecipientKey()).isNull();
      assertThat(released.getTenantIdReservationToken()).isNull();
      assertThat(released.getTokenHash()).isNull();
      assertThat(
              jdbc.queryForObject(
                  "SELECT response_json FROM protected_invitation_cancellation WHERE invite_id=?",
                  String.class,
                  original.getId()))
          .isEqualTo(firstAck);
      assertThat(restarted.pendingInviteIds(100)).doesNotContain(original.getId());
      sweep.retryPending();
      verify(tenantIds).release(17L);
      assertThat(service.createInvite(command()).getActiveRecipientKey()).isEqualTo(RECIPIENT);
      assertThat(rpc).hasValue(2);
      verifyNoInteractions(
          nativeAccounts, otp, activation, recovery, sender, approvals, authentication);
    } finally {
      service.configureProtectedCancellation(cancellation);
      for (String table :
          new String[] {
            "protected_invitation_cleanup_status", "protected_account_recovery",
            "protected_account_creation", "protected_account_activation",
            "protected_invitation_acceptance", "protected_invitation_cancellation"
          }) jdbc.execute("DROP TABLE IF EXISTS " + table);
    }
  }

  private ProtectedInvitationRestrictionCodec.VerifiedRestriction absentAck(
      ProtectedInvitationRestrictionCodec.FrozenCancellation frozen,
      ProtectedInvitationRestrictionCodec restrictions)
      throws Exception {
    var approval = frozen.approval();
    var scope =
        json.read(approval.requestJson(), com.fasterxml.jackson.databind.JsonNode.class)
            .path("scope");
    var restriction = new LinkedHashMap<String, Object>();
    restriction.put("scope", scope);
    restriction.put("realmId", approval.issuerRealm());
    restriction.put("originalIssuerRealm", approval.issuerRealm());
    restriction.put("originalIssuerSubject", approval.issuerSubject());
    restriction.put("restrictionOnly", true);
    String digest = json.digest("protected-approval-scope/v1", restriction);
    var transition = new LinkedHashMap<String, Object>();
    transition.put("realmId", approval.issuerRealm());
    transition.put("approvalId", approval.approvalId().toString());
    transition.put("workflowId", scope.path("workflowId").asText());
    transition.put("inviteId", scope.path("inviteId").asText());
    transition.put("authorizationRevision", scope.path("authorizationRevision").longValue());
    transition.put("scopeDigest", digest);
    transition.put("approvalRequestDigest", approval.requestDigest());
    transition.put("operation", "protected-invitation.cancel");
    transition.put("reason", frozen.reason().name());
    transition.put("state", "CANCELLED");
    transition.put("actor", "SETUP");
    transition.put("cancellationId", frozen.cancellationId().toString());
    transition.put("actorSubject", scope.path("setupSubject").asText());
    transition.put("actorIssuer", "https://auth.example.test/realms/oriso");
    transition.put("actorClient", scope.path("setupClient").asText());
    transition.put("actorBindingVersion", null);
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.canonical(transition).getBytes(StandardCharsets.UTF_8));
    var mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(new javax.crypto.spec.SecretKeySpec(KEY, "HmacSHA256"));
    transition.put(
        "receipt",
        encoded
            + "."
            + HexFormat.of()
                .formatHex(
                    mac.doFinal(
                        ("protected-approval-cancel/v1\n" + encoded)
                            .getBytes(StandardCharsets.UTF_8))));
    var response = new LinkedHashMap<String, Object>();
    response.put("approvalId", approval.approvalId().toString());
    response.put("scopeDigest", digest);
    response.put("state", "CANCELLED");
    response.put("version", 0);
    response.put("boundAttemptId", null);
    response.put("fencedExecution", null);
    response.put("approvalReceipt", null);
    response.put("transitionReceipt", transition);
    response.put("approvalRequestDigest", approval.requestDigest());
    return restrictions.verify(
        frozen, json.canonical(response).getBytes(StandardCharsets.UTF_8), false);
  }

  @Test
  void expiredAcknowledgedPlatformSupersessionReleasesItsEmailKeyWithoutANumberCapability() {
    var platform =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.PLATFORM_ADMIN,
            null,
            RECIPIENT,
            "Ada",
            "Lovelace",
            null,
            null,
            30L,
            null,
            null);
    var retired = service.createInvite(platform);
    retired.setStatus(AccountInviteStatus.SUPERSEDED);
    retired.setExpiresAt(LocalDateTime.now().minusDays(1));
    retired.setTokenHash(null);
    invites.saveAndFlush(retired);
    assertThat(retired.getTenantId()).isZero();
    assertThat(retired.getTenantIdReservationToken()).isNull();
    assertThat(retired.getActiveRecipientKey()).isEqualTo(RECIPIENT);
    when(cancellation.pendingInviteIds(100)).thenReturn(List.of(retired.getId()));
    doAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              transaction().executeWithoutResult(tx -> ((Runnable) call.getArgument(2)).run());
              return null;
            })
        .when(cancellation)
        .cancel(eq(retired.getId()), eq(ProtectedInvitationCancellation.Reason.SUPERSEDED), any());
    when(cancellation.withAcknowledged(eq(retired.getId()), any()))
        .thenAnswer(call -> transaction().execute(tx -> ((Supplier<?>) call.getArgument(1)).get()));
    assertDoesNotThrow(service::expireElapsedInvites);
    assertDoesNotThrow(service::expireElapsedInvites);
    assertThat(invites.findById(retired.getId()).orElseThrow().getActiveRecipientKey()).isNull();
    assertThat(service.createInvite(platform).getActiveRecipientKey()).isEqualTo(RECIPIENT);
    verifyNoInteractions(tenantIds, sender, approvals);
  }

  @Test
  void pendingAcceptedEmailKeyStaysHeldUntilVerifiedReleaseThenActualEmailCanBeInvitedAgain() {
    when(tenantIds.reserve(null))
        .thenReturn(
            new TenantIdReservation(17L, "9eb4c868-16d4-4be5-8c0b-5fb9d2b866b3"),
            new TenantIdReservation(18L, "59ba921a-849d-4f32-a492-98d03ff582b0"),
            new TenantIdReservation(17L, "99d529dd-ef5c-46c2-92ed-56c005c8c026"));
    when(tenantIds.getAvailability(18L)).thenReturn(IdAllocationStatus.RESERVED);
    var original = service.createInvite(command());
    String held = original.getActiveRecipientKey();
    original.setStatus(AccountInviteStatus.EXPIRED);
    original.setExpiresAt(LocalDateTime.now().minusDays(1).withNano(0));
    original.setProvisioningStatus(AccountInviteProvisioningStatus.IN_PROGRESS);
    original.setAcceptedByUserId("bf75ecf1-e612-414a-9e20-fd0858957f68");
    original.setTokenHash(null);
    invites.saveAndFlush(original);
    assertThat(held).isNotBlank();
    var finalProof = new AtomicBoolean();
    var insideAck = new AtomicBoolean();
    when(cancellation.pendingInviteIds(100)).thenReturn(List.of(original.getId()));
    doAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              transaction().executeWithoutResult(tx -> ((Runnable) call.getArgument(2)).run());
              return null;
            })
        .when(cancellation)
        .cancel(
            eq(original.getId()),
            eq(ProtectedInvitationCancellation.Reason.ISSUANCE_ABORTED),
            any());
    when(cancellation.withAcknowledged(eq(original.getId()), any()))
        .thenAnswer(
            call -> {
              if (!finalProof.get()) throw new KeycloakProtectedInvitations.RemoteFailure(0);
              insideAck.set(true);
              try {
                return transaction().execute(tx -> ((Supplier<?>) call.getArgument(1)).get());
              } finally {
                insideAck.set(false);
              }
            });
    when(tenantIds.release(17L))
        .thenAnswer(
            call -> {
              assertTrue(insideAck.get());
              return true;
            });
    assertThrows(KeycloakProtectedInvitations.RemoteFailure.class, service::expireElapsedInvites);
    assertThat(invites.findById(original.getId()).orElseThrow().getActiveRecipientKey())
        .isEqualTo(held);
    assertThatThrownBy(() -> service.createInvite(command())).isInstanceOf(RuntimeException.class);
    verify(tenantIds, never()).release(17L);
    assertThat(invites.count()).isEqualTo(1);

    finalProof.set(true);
    assertDoesNotThrow(service::expireElapsedInvites);
    var released = invites.findById(original.getId()).orElseThrow();
    assertThat(released.getTenantIdReservationToken()).isNull();
    assertThat(released.getActiveRecipientKey()).isNull();
    assertThat(released.getTokenHash()).isNull();
    var next = assertDoesNotThrow(() -> service.createInvite(command()));
    assertThat(next.getId()).isNotEqualTo(original.getId());
    assertThat(next.getActiveRecipientKey()).isEqualTo(held);
    assertThat(invites.count()).isEqualTo(2);
    verify(tenantIds).release(17L);
    verifyNoInteractions(sender, approvals);
  }

  @Test
  void unknownRevokeRetainsTheOriginalEmailKeyUntilIndependentReleaseProof() {
    when(tenantIds.reserve(null))
        .thenReturn(
            new TenantIdReservation(17L, "9eb4c868-16d4-4be5-8c0b-5fb9d2b866b3"),
            new TenantIdReservation(18L, "59ba921a-849d-4f32-a492-98d03ff582b0"));
    when(tenantIds.getAvailability(18L)).thenReturn(IdAllocationStatus.RESERVED);
    var original = service.createInvite(command());
    String held = original.getActiveRecipientKey();
    doAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              transaction().executeWithoutResult(tx -> ((Runnable) call.getArgument(2)).run());
              throw new KeycloakProtectedInvitations.RemoteFailure(0);
            })
        .when(cancellation)
        .cancel(eq(original.getId()), eq(ProtectedInvitationCancellation.Reason.REVOKED), any());
    assertThrows(
        KeycloakProtectedInvitations.RemoteFailure.class,
        () -> service.revokeInvite(original.getId()));
    var invalid = invites.findById(original.getId()).orElseThrow();
    assertThat(invalid.getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    assertThat(invalid.getTokenHash()).isNull();
    assertThat(invalid.getTenantIdReservationToken())
        .isEqualTo(original.getTenantIdReservationToken());
    assertThat(invalid.getActiveRecipientKey()).isEqualTo(held);
    assertThatThrownBy(() -> service.createInvite(command())).isInstanceOf(RuntimeException.class);
    verify(cancellation, never()).withAcknowledged(anyLong(), any());
    verify(tenantIds, never()).release(17L);
    verifyNoInteractions(sender, approvals);
  }

  @Test
  void unknownResendCannotFreeTheOriginalEmailKeyForASecondInvitation() {
    distinctReservationForRejectedNewInvite();
    var original = service.createInvite(command());
    String held = original.getActiveRecipientKey();
    failAfterCommittedInvalidation(
        original.getId(), ProtectedInvitationCancellation.Reason.SUPERSEDED);
    assertThrows(
        KeycloakProtectedInvitations.RemoteFailure.class,
        () ->
            service.resendInvite(
                new AccountInviteService.SendInviteCommand(original.getId(), template.getId())));
    var retired = invites.findById(original.getId()).orElseThrow();
    assertThat(retired.getStatus()).isEqualTo(AccountInviteStatus.SUPERSEDED);
    assertThat(retired.getTokenHash()).isNull();
    assertThat(retired.getActiveRecipientKey()).isEqualTo(held);
    assertThatThrownBy(() -> service.createInvite(command())).isInstanceOf(RuntimeException.class);
    verify(cancellation, never()).withAcknowledged(anyLong(), any());
    verify(tenantIds, never()).release(17L);
    verifyNoInteractions(sender, approvals);
  }

  @Test
  void actualElapsedDraftKeepsItsEmailKeyWhileNativeCancellationIsUnknown() {
    distinctReservationForRejectedNewInvite();
    var original = service.createInvite(command());
    String held = original.getActiveRecipientKey();
    original.setExpiresAt(LocalDateTime.now().minusDays(1));
    invites.saveAndFlush(original);
    failAfterCommittedInvalidation(
        original.getId(), ProtectedInvitationCancellation.Reason.ISSUANCE_ABORTED);
    assertThrows(KeycloakProtectedInvitations.RemoteFailure.class, service::expireElapsedInvites);
    var expired = invites.findById(original.getId()).orElseThrow();
    assertThat(expired.getStatus()).isEqualTo(AccountInviteStatus.EXPIRED);
    assertThat(expired.getTokenHash()).isNull();
    assertThat(expired.getActiveRecipientKey()).isEqualTo(held);
    assertThat(expired.getTenantIdReservationToken())
        .isEqualTo(original.getTenantIdReservationToken());
    assertThatThrownBy(() -> service.createInvite(command())).isInstanceOf(RuntimeException.class);
    verify(cancellation, never()).withAcknowledged(anyLong(), any());
    verify(tenantIds, never()).release(17L);
    verifyNoInteractions(sender, approvals);
  }

  private void distinctReservationForRejectedNewInvite() {
    when(tenantIds.reserve(null))
        .thenReturn(
            new TenantIdReservation(17L, "9eb4c868-16d4-4be5-8c0b-5fb9d2b866b3"),
            new TenantIdReservation(18L, "59ba921a-849d-4f32-a492-98d03ff582b0"));
    when(tenantIds.getAvailability(18L)).thenReturn(IdAllocationStatus.RESERVED);
  }

  private void failAfterCommittedInvalidation(
      long id, ProtectedInvitationCancellation.Reason reason) {
    doAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              transaction().executeWithoutResult(tx -> ((Runnable) call.getArgument(2)).run());
              throw new KeycloakProtectedInvitations.RemoteFailure(0);
            })
        .when(cancellation)
        .cancel(eq(id), eq(reason), any());
  }

  @Test
  void stalePendingDiscoveryNeverReleasesTheEmailKeyOfAnUnexpiredProtectedInvite() {
    var live = service.createInvite(command());
    when(cancellation.pendingInviteIds(100)).thenReturn(List.of(live.getId()));
    assertDoesNotThrow(service::expireElapsedInvites);
    assertThat(invites.findById(live.getId()).orElseThrow().getActiveRecipientKey())
        .isEqualTo(live.getActiveRecipientKey());
    assertThatThrownBy(() -> service.createInvite(command())).isInstanceOf(RuntimeException.class);
    verify(cancellation, never()).withAcknowledged(anyLong(), any());
    verify(tenantIds, never()).release(17L);
  }

  private TransactionTemplate transaction() {
    var tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tx;
  }
}
