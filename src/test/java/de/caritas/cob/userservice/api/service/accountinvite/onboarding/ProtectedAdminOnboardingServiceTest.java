package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedAccountActivation.Phase;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.*;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.identity.*;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Group;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.RegisterTenantAdminCommand;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Public service sequencing only; private-handle doubles are not SQL or native evidence. */
class ProtectedAdminOnboardingServiceTest {
  private final AccountInviteService invites = mock(AccountInviteService.class);
  private final TaskActorConfiguration actors = mock(TaskActorConfiguration.class);
  private final JdbcProtectedInvitationAcceptance acceptance =
      mock(JdbcProtectedInvitationAcceptance.class);
  private final ProtectedAccountCodec codec = mock(ProtectedAccountCodec.class);
  private final JdbcProtectedAccountCreation creation = mock(JdbcProtectedAccountCreation.class);
  private final KeycloakProtectedAccounts accounts = mock(KeycloakProtectedAccounts.class);
  private final ProtectedOtpBootstrap otp = mock(ProtectedOtpBootstrap.class);
  private final JdbcProtectedAccountActivation activation =
      mock(JdbcProtectedAccountActivation.class);
  private final AccountInactivityEnrollmentService inactivity =
      mock(AccountInactivityEnrollmentService.class);
  private final OperatorDpaContentClient dpa = mock(OperatorDpaContentClient.class);
  private final TenantCreationClient tenants = mock(TenantCreationClient.class);
  private final AcceptanceClaim claim = mock(AcceptanceClaim.class);
  private final PreparedCreation prepared = mock(PreparedCreation.class);
  private final VerifiedCreated created = mock(VerifiedCreated.class);
  private final KeycloakProtectedAccounts.PendingOwnedAccount pending =
      mock(KeycloakProtectedAccounts.PendingOwnedAccount.class);
  private final ProtectedOtpBootstrap.PreparedBootstrap bootstrap =
      mock(ProtectedOtpBootstrap.PreparedBootstrap.class);
  private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
  private final AccountInactivityEnrollmentService.Policy policy =
      new AccountInactivityEnrollmentService.Policy(12, 3, clock.instant());
  private final IdentityOtpCredential secret =
      new IdentityOtpCredential(true, "synthetic-secret", "synthetic-qr", IdentityOtpType.APP);
  private final RegisterTenantAdminCommand command =
      new RegisterTenantAdminCommand(
          null, null, null, false, null, null, null, null, "synthetic password", null, null);
  private final ObjectMapper nodes = new ObjectMapper();
  private JsonNode original;
  private AccountInvite invite;
  private ProtectedAdminOnboardingService service;

  @BeforeEach
  void checkedOwnersAreExplicitTestDoubles() throws Exception {
    original =
        nodes.readTree(
            "{\"method\":\"POST\",\"path\":\"/users/account-invites/synthetic/onboarding/register\",\"key\":\"3fef0358-08c0-43ac-a737-c7d0d48064c9\",\"body\":\"synthetic exact raw JSON\"}");
    invite =
        AccountInvite.builder()
            .id(73L)
            .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
            .tenantId(7L)
            .recipientEmail("ada@example.test")
            .firstName("Ada")
            .lastName("Admin")
            .purpose(AccountInvitePurpose.INVITE)
            .status(AccountInviteStatus.EMAIL_SENT)
            .expiresAt(LocalDateTime.ofInstant(clock.instant().plusSeconds(600), ZoneOffset.UTC))
            .tokenHash(AccountInviteService.hash("synthetic"))
            .build();
    when(invites.findInviteByToken("synthetic")).thenAnswer(c -> invite);
    when(actors.isEnabled()).thenReturn(true);
    when(acceptance.claim(eq(73L), eq(AccountInviteService.hash("synthetic")), eq(original)))
        .thenReturn(claim);
    when(acceptance.resume(73L, AccountInviteService.hash("synthetic"))).thenReturn(claim);
    when(activation.phase(claim)).thenReturn(Phase.UNSTAGED);
    when(codec.prepare(eq(claim), eq(original), any())).thenReturn(prepared);
    when(accounts.create(prepared)).thenReturn(created);
    when(created.accountId()).thenReturn("f6e71c6d-93f8-4a4e-8be4-e3c10994e9d1");
    when(created.email()).thenReturn("ada@example.test");
    when(created.firstName()).thenReturn("Ada");
    when(created.lastName()).thenReturn("Admin");
    when(accounts.ownedRead(claim, created)).thenReturn(pending);
    when(otp.prepare(claim, pending)).thenReturn(bootstrap);
    when(inactivity.capture(anyLong(), eq(Group.OTHER))).thenReturn(policy);
    when(activation.stage(claim, pending, bootstrap, policy)).thenReturn(secret);
    var frozen = mock(ProtectedInvitationCodec.FrozenApproval.class);
    when(claim.frozen()).thenReturn(frozen);
    when(frozen.issuerRealm()).thenReturn("synthetic-realm");
    when(frozen.requestJson())
        .thenAnswer(
            c ->
                "{\"scope\":{\"protectedKind\":\""
                    + invite.getTargetRole()
                    + "\",\"targetTenantIds\":["
                    + invite.getTenantId()
                    + "],\"tenantMode\":\"EXISTING\",\"protectedProfileId\":\""
                    + (invite.getTenantId() == 0 ? "reviewed-platform" : "reviewed-tenant")
                    + "\"}}");
    service =
        new ProtectedAdminOnboardingService(
            invites,
            actors,
            acceptance,
            codec,
            creation,
            accounts,
            otp,
            activation,
            inactivity,
            new UsernameTranscoder(),
            new ProtectedInvitationProfiles(
                "synthetic-realm",
                Map.of(
                    AccountInviteTargetRole.TENANT_ADMIN,
                    "reviewed-tenant",
                    AccountInviteTargetRole.PLATFORM_ADMIN,
                    "reviewed-platform")),
            dpa,
            tenants,
            clock);
    clearInvocations(
        invites,
        actors,
        acceptance,
        codec,
        creation,
        accounts,
        otp,
        activation,
        inactivity,
        dpa,
        tenants);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"UTC", "America/New_York", "Europe/Prague"})
  @org.junit.jupiter.api.parallel.ResourceLock("java.util.TimeZone.default")
  void actualFactoryKeepsThePendingInvitationWallTimeAcrossPublicPhases(String zoneName) {
    var previous = java.util.TimeZone.getDefault();
    var zone = ZoneId.of(zoneName);
    var now = Instant.parse("2026-10-28T14:00:00Z");
    var expiry = LocalDateTime.ofInstant(now.plusSeconds(7200), zone);
    try {
      java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));
      invite.setExpiresAt(expiry);
      var configured = configuredAt(now, zone);
      assertSame(invite, configured.resolve("synthetic").invite());
      var registered = configured.register("synthetic", command, original);
      assertEquals(secret.secret(), registered.totpSecret());
      verify(accounts).create(prepared);
      invite.setStatus(AccountInviteStatus.ACCEPTED);
      when(activation.currentSetup(claim)).thenReturn(secret);
      for (Phase phase : new Phase[] {Phase.WAITING_OTP, Phase.READY, Phase.COMMITTED}) {
        when(activation.phase(claim)).thenReturn(phase);
        assertSame(invite, configured.resolve("synthetic").invite());
      }
      assertEquals("COMPLETED", configured.register("synthetic", command, original).phase());
      assertEquals(expiry, invite.getExpiresAt());
      assertEquals(
          now.plusSeconds(7200), java.sql.Timestamp.valueOf(invite.getExpiresAt()).toInstant());
      var expired = configuredAt(now.plusSeconds(7200), zone);
      clearInvocations(acceptance, creation, accounts, activation, otp);
      assertEquals(
          AccountInviteLinkException.Reason.EXPIRED,
          assertThrows(AccountInviteLinkException.class, () -> expired.resolve("synthetic"))
              .getReason());
      assertEquals(
          AccountInviteLinkException.Reason.EXPIRED,
          assertThrows(
                  AccountInviteLinkException.class,
                  () -> expired.register("synthetic", command, original))
              .getReason());
      verifyNoInteractions(acceptance, creation, accounts, activation, otp);
      assertEquals(expiry, invite.getExpiresAt());
    } finally {
      java.util.TimeZone.setDefault(previous);
    }
  }

  private ProtectedAdminOnboardingService configuredAt(Instant now, ZoneId zone) {
    var utc = Clock.fixed(now, ZoneOffset.UTC);
    var local = Clock.fixed(now, zone);
    try (var clocks = mockStatic(Clock.class, CALLS_REAL_METHODS)) {
      clocks.when(Clock::systemUTC).thenReturn(utc);
      clocks.when(Clock::systemDefaultZone).thenReturn(local);
      return org.springframework.test.util.ReflectionTestUtils.invokeMethod(
          new ProtectedInvitationIssuanceConfiguration(
              new org.springframework.mock.env.MockEnvironment()),
          "protectedAdminOnboardingService",
          invites,
          actors,
          acceptance,
          codec,
          creation,
          accounts,
          otp,
          activation,
          inactivity,
          new UsernameTranscoder(),
          new ProtectedInvitationProfiles(
              "synthetic-realm",
              Map.of(
                  AccountInviteTargetRole.TENANT_ADMIN,
                  "reviewed-tenant",
                  AccountInviteTargetRole.PLATFORM_ADMIN,
                  "reviewed-platform")),
          dpa,
          tenants,
          mock(ProtectedAccountRecovery.class),
          mock(JdbcProtectedInvitationCancellation.class));
    }
  }

  @Test
  void actualExistingTenantRegistrationCommitsOriginalCustodyBeforeDisabledCreateAndLocalStage() {
    var result = assertDoesNotThrow(() -> service.register("synthetic", command, original));
    assertEquals(7L, result.tenantId());
    assertEquals(secret.secret(), result.totpSecret());
    var order = inOrder(acceptance, creation, accounts, otp, inactivity, activation);
    order.verify(acceptance).claim(73L, AccountInviteService.hash("synthetic"), original);
    order.verify(creation).prepare(claim, prepared);
    order.verify(accounts).create(prepared);
    order.verify(creation).recordCreated(claim, created);
    order.verify(accounts).ownedRead(claim, created);
    order.verify(otp).prepare(claim, pending);
    order.verify(inactivity).capture(7L, Group.OTHER);
    order.verify(activation).stage(claim, pending, bootstrap, policy);
    verifyNoInteractions(tenants, dpa);
  }

  @Test
  void platformAdministratorUsesAuthoritativePlatformInactivityWithoutCreatingATenantOrDpa() {
    invite.setTargetRole(AccountInviteTargetRole.PLATFORM_ADMIN);
    invite.setTenantId(0L);
    var result = assertDoesNotThrow(() -> service.register("synthetic", command, original));
    assertEquals(0L, result.tenantId());
    verify(inactivity).capture(0L, Group.OTHER);
    verifyNoInteractions(tenants, dpa);
  }

  @Test
  void unknownCreateHasDurableOriginalAttemptAndNoOtpOrLocalSuccessThenRetriesTheSameIntent() {
    when(accounts.create(prepared)).thenThrow(new KeycloakProtectedAccounts.RemoteFailure(0));
    assertThrows(
        KeycloakProtectedAccounts.RemoteFailure.class,
        () -> service.register("synthetic", command, original));
    var order = inOrder(creation, accounts);
    order.verify(creation).prepare(claim, prepared);
    order.verify(accounts).create(prepared);
    verifyNoInteractions(otp, inactivity);
    verify(activation, never()).stage(any(), any(), any(), any());
    doReturn(created).when(accounts).create(prepared);
    assertNotNull(assertDoesNotThrow(() -> service.register("synthetic", command, original)));
    verify(creation, times(2)).prepare(claim, prepared);
    verify(accounts, times(2)).create(prepared);
  }

  @Test
  void deadSetupAndWrongRoleLinksRefuseBeforeAnyNativeOrCustodyEffect() {
    invite.setPurpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP);
    assertThrows(RuntimeException.class, () -> service.register("synthetic", command, original));
    invite.setPurpose(AccountInvitePurpose.INVITE);
    invite.setTargetRole(AccountInviteTargetRole.COUNSELLOR);
    assertThrows(RuntimeException.class, () -> service.register("synthetic", command, original));
    invite.setTargetRole(AccountInviteTargetRole.TENANT_ADMIN);
    invite.setExpiresAt(LocalDateTime.ofInstant(clock.instant().minusSeconds(1), ZoneOffset.UTC));
    assertThrows(RuntimeException.class, () -> service.register("synthetic", command, original));
    verifyNoInteractions(acceptance, codec, creation, accounts, otp, inactivity, tenants, dpa);
  }

  @Test
  void acceptedResolveRestoresOnlyTheOwnedEncryptedSetupWithoutRegisteringAgain() {
    invite.setStatus(AccountInviteStatus.ACCEPTED);
    when(activation.phase(claim)).thenReturn(Phase.WAITING_OTP);
    when(activation.currentSetup(claim)).thenReturn(secret);
    var result = assertDoesNotThrow(() -> service.resolve("synthetic"));
    assertEquals(secret, result.twoFactor());
    assertSame(invite, result.invite());
    verify(acceptance).resume(73L, AccountInviteService.hash("synthetic"));
    verifyNoInteractions(creation, accounts, tenants, dpa);
  }

  @Test
  void appFinishCommitsOnlyThePrivateReadyThenAcknowledgesTheExactNativeOutcome() {
    complete(false);
  }

  @Test
  void emailFinishCommitsOnlyThePrivateReadyThenAcknowledgesTheExactNativeOutcome() {
    complete(true);
  }

  @Test
  void unresolvedEmailDeliveryCannotPretendSuccessOrActivateTheAccount() {
    invite.setStatus(AccountInviteStatus.ACCEPTED);
    var attempt = mock(ProtectedOtpBootstrap.BootstrapAttempt.class);
    when(otp.startEmail(claim)).thenReturn(attempt);
    when(attempt.state()).thenReturn(ProtectedOtpBootstrap.AttemptState.PENDING);
    assertThrows(RuntimeException.class, () -> service.startEmail("synthetic"));
    verify(otp).startEmail(claim);
    verify(accounts, never()).commit(any());
    verify(activation, never()).ready(any(), any());
  }

  @Test
  void reservedTenantUsesFrozenShownDpaAndActualVerifiedTenantBeforeReservedAtomicStage() {
    invite.setTenantIdReservationToken("9d5ba63c-cc3c-46b5-8f48-6a63de8100f4");
    when(claim.frozen().requestJson())
        .thenReturn(
            "{\"scope\":{\"protectedKind\":\"TENANT_ADMIN\",\"targetTenantIds\":[7],\"tenantMode\":\"RESERVED_NEW\",\"protectedProfileId\":\"reviewed-tenant\"}}");
    var cmd =
        new RegisterTenantAdminCommand(
            "Original tenant",
            null,
            null,
            true,
            null,
            null,
            null,
            null,
            "synthetic password",
            7L,
            invite.getTenantIdReservationToken(),
            null,
            null,
            null,
            "2030-01-01T00:00:00Z");
    when(dpa.fetchPublishedDpa())
        .thenReturn(
            new OperatorDpaContentClient.OperatorDpa(
                "actual displayed content", "2030-01-01T00:00:00Z"));
    var saved =
        new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                .MultilingualTenantDTO()
            .id(7L)
            .name("Original tenant")
            .tenantIdReservationToken(invite.getTenantIdReservationToken());
    when(creation.findPrepared(claim)).thenReturn(java.util.Optional.empty());
    when(creation.tenantRequest(claim)).thenReturn(java.util.Optional.empty());
    when(creation.freezeTenantRequest(eq(claim), any())).thenReturn(saved);
    var tenant = mock(TenantCreationClient.VerifiedTenant.class);
    when(tenants.createReservedTenant(claim, saved)).thenReturn(tenant);
    when(activation.stageReserved(claim, pending, bootstrap, policy, tenant)).thenReturn(secret);
    when(created.accountId()).thenReturn("f6e71c6d-93f8-4a4e-8be4-e3c10994e9d1");
    when(created.firstName()).thenReturn("Ada");
    when(created.lastName()).thenReturn("Admin");
    when(created.email()).thenReturn("ada@example.test");
    var result = assertDoesNotThrow(() -> service.register("synthetic", cmd, original));
    assertEquals(7L, result.tenantId());
    var order = inOrder(creation, tenants, activation);
    order.verify(creation).freezeTenantRequest(eq(claim), any());
    order.verify(tenants).createReservedTenant(claim, saved);
    order.verify(activation).stageReserved(claim, pending, bootstrap, policy, tenant);
    verify(activation, never()).stage(any(), any(), any(), any());
  }

  @Test
  void reservedTenantForwardedConsentIsServerOwnedAndNeedsNoInventedOwnVersion() {
    invite.setTenantIdReservationToken("9d5ba63c-cc3c-46b5-8f48-6a63de8100f4");
    invite.setDpaForwardedAt(
        LocalDateTime.ofInstant(clock.instant().minusSeconds(60), ZoneOffset.UTC));
    when(claim.frozen().requestJson())
        .thenReturn(
            "{\"scope\":{\"protectedKind\":\"TENANT_ADMIN\",\"targetTenantIds\":[7],\"tenantMode\":\"RESERVED_NEW\",\"protectedProfileId\":\"reviewed-tenant\"}}");
    var cmd =
        new RegisterTenantAdminCommand(
            "Original tenant",
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            "synthetic password",
            7L,
            invite.getTenantIdReservationToken());
    var saved =
        new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                .MultilingualTenantDTO()
            .id(7L)
            .name("Original tenant")
            .tenantIdReservationToken(invite.getTenantIdReservationToken());
    when(creation.findPrepared(claim)).thenReturn(java.util.Optional.empty());
    when(creation.tenantRequest(claim)).thenReturn(java.util.Optional.empty());
    when(creation.freezeTenantRequest(eq(claim), any())).thenReturn(saved);
    var tenant = mock(TenantCreationClient.VerifiedTenant.class);
    when(tenants.createReservedTenant(claim, saved)).thenReturn(tenant);
    when(activation.stageReserved(claim, pending, bootstrap, policy, tenant)).thenReturn(secret);
    assertNotNull(assertDoesNotThrow(() -> service.register("synthetic", cmd, original)));
    var dto =
        org.mockito.ArgumentCaptor.forClass(
            de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualTenantDTO
                .class);
    verify(creation).freezeTenantRequest(eq(claim), dto.capture());
    assertNull(dto.getValue().getOnboardingDpaAcceptance());
    verifyNoInteractions(dpa);
  }

  private void complete(boolean email) {
    invite.setStatus(AccountInviteStatus.ACCEPTED);
    var attempt = mock(ProtectedOtpBootstrap.BootstrapAttempt.class);
    var completion = mock(ProtectedOtpBootstrap.Completion.class);
    when(attempt.state()).thenReturn(ProtectedOtpBootstrap.AttemptState.COMPLETE);
    when(attempt.completion()).thenReturn(completion);
    if (email) when(otp.finishEmail(claim, "123456")).thenReturn(attempt);
    else when(otp.setupApp(claim, "123456")).thenReturn(attempt);
    var ready = mock(JdbcProtectedAccountActivation.ReadyActivation.class);
    var committed = mock(VerifiedCommitted.class);
    when(activation.ready(claim, completion)).thenReturn(ready);
    when(accounts.commit(ready)).thenReturn(committed);
    assertDoesNotThrow(
        () -> {
          if (email) service.finishEmail("synthetic", "123456");
          else service.setupApp("synthetic", "123456");
        });
    var order = inOrder(activation, accounts);
    order.verify(activation).ready(claim, completion);
    order.verify(accounts).commit(ready);
    order.verify(activation).acknowledge(ready, committed);
  }
}
