package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import de.caritas.cob.userservice.api.service.auth.OneTimeTokenStore;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class IdentityMaintenanceAuthorizationTest {
  private JwtAuthenticationToken human(String subject, String authority, String... roles) {
    var jwt =
        Jwt.withTokenValue("verified-test-human")
            .header("alg", "RS256")
            .subject(subject)
            .claim("tenantId", "0")
            .claim("realm_access", Map.of("roles", List.of(roles)))
            .build();
    return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(authority)));
  }

  @Test
  void selfServiceOwnPlatformAccountPermitsOnlyReadProfileAndPassword() {
    var caller = human("platform", "AUTHORIZATION_TENANT_ADMIN", "tenant-admin").getToken();
    for (String operation : List.of("account.read", "account.profile", "account.password"))
      assertThat(IdentityCommandAuthorization.selfService(caller, "platform", operation).tenantId())
          .isEqualTo("0");
    for (String operation :
        List.of("account.roles", "account.deactivate", "account.delete", "account.search"))
      assertThatThrownBy(
              () -> IdentityCommandAuthorization.selfService(caller, "platform", operation))
          .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> IdentityCommandAuthorization.selfService(caller, "foreign", "account.password"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void mixedTechnicalRolesCannotMasqueradeAsHumanSelfServiceOrAdministrator() {
    var caller =
        human("platform", "AUTHORIZATION_TENANT_ADMIN", "tenant-admin", "account-maintenance");
    assertThatThrownBy(
            () ->
                IdentityCommandAuthorization.selfService(
                    caller.getToken(), "platform", "account.read"))
        .isInstanceOf(AccessDeniedException.class);
    var scope = mock(AdminScope.class);
    assertThatThrownBy(
            () ->
                IdentityCommandAuthorization.checkedHuman(
                    caller,
                    scope,
                    Admin.builder()
                        .username("synthetic-account")
                        .firstName("Test")
                        .lastName("Owner")
                        .email("synthetic@example.org")
                        .id("foreign")
                        .type(Admin.AdminType.TENANT)
                        .tenantId(9L)
                        .build(),
                    "account.password",
                    List.of()))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(scope);
  }

  @Test
  void administratorNeedsOriginalPermissionAndActualTargetScope() {
    var scope = mock(AdminScope.class);
    var target =
        User.builder()
            .username("synthetic-account")
            .email("synthetic@example.org")
            .userId("asker")
            .tenantId(9L)
            .build();
    assertThatThrownBy(
            () ->
                IdentityCommandAuthorization.checkedHuman(
                    human("operator", "AUTHORIZATION_USER_DEFAULT", "user"),
                    scope,
                    target,
                    "account.delete",
                    List.of()))
        .isInstanceOf(AccessDeniedException.class);
    doThrow(new AccessDeniedException("Out of operator scope")).when(scope).assertMay(any());
    assertThatThrownBy(
            () ->
                IdentityCommandAuthorization.checkedHuman(
                    human("operator", "AUTHORIZATION_USER_ADMIN", "user-admin"),
                    scope,
                    target,
                    "account.delete",
                    List.of()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void originalUserAdminListingReadDoesNotGrantTenantWritesOrProtectedNativeReads() {
    var caller = human("operator", "AUTHORIZATION_USER_ADMIN", "user-admin");
    var scope = mock(AdminScope.class);
    var target =
        Admin.builder()
            .id("tenant-admin")
            .type(Admin.AdminType.TENANT)
            .tenantId(42L)
            .username("ordinary")
            .email("ordinary@example.org")
            .firstName("First")
            .lastName("Last")
            .build();
    assertThat(
            IdentityCommandAuthorization.checkedHuman(
                    caller, scope, target, "account.read", List.of())
                .target())
        .isEqualTo(target.getId());
    for (var operation :
        List.of("account.password", "account.profile", "account.roles", "account.delete"))
      assertThatThrownBy(
              () ->
                  IdentityCommandAuthorization.checkedHuman(
                      caller, scope, target, operation, List.of()))
          .isInstanceOf(AccessDeniedException.class);
    target.setTenantId(0L);
    assertThatThrownBy(
            () ->
                IdentityCommandAuthorization.checkedHuman(
                    caller, scope, target, "account.read", List.of()))
        .isInstanceOf(AccessDeniedException.class);
    target.setTenantId(42L);
    doThrow(new AccessDeniedException("Foreign target")).when(scope).assertMay(any());
    assertThatThrownBy(
            () ->
                IdentityCommandAuthorization.checkedHuman(
                    caller, scope, target, "account.read", List.of()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void deletionLifecycleNeedsActualDeletionMarkerAndCannotChangeRoles() {
    var target =
        User.builder()
            .username("synthetic-account")
            .email("synthetic@example.org")
            .userId("asker")
            .tenantId(9L)
            .build();
    assertThatThrownBy(
            () -> IdentityCommandAuthorization.persistedDeletion(target, "account.delete"))
        .isInstanceOf(AccessDeniedException.class);
    target.setDeleteDate(LocalDateTime.now());
    assertThat(IdentityCommandAuthorization.persistedDeletion(target, "account.delete").target())
        .isEqualTo("asker");
    assertThatThrownBy(
            () -> IdentityCommandAuthorization.persistedDeletion(target, "account.roles"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void scheduledAnonymousDeactivationCannotReachRecentOrRegisteredSessions() {
    var target =
        User.builder()
            .username("synthetic-account")
            .email("synthetic@example.org")
            .userId("anonymous")
            .tenantId(9L)
            .build();
    var session =
        Session.builder()
            .postcode("00000")
            .id(12L)
            .user(target)
            .registrationType(Session.RegistrationType.ANONYMOUS)
            .status(Session.SessionStatus.NEW)
            .updateDate(LocalDateTime.now().minusHours(7))
            .build();
    var cutoff = LocalDateTime.now().minusHours(6);
    assertThat(IdentityCommandAuthorization.staleAnonymousSession(session, cutoff).target())
        .isEqualTo("anonymous");
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    assertThatThrownBy(() -> IdentityCommandAuthorization.staleAnonymousSession(session, cutoff))
        .isInstanceOf(AccessDeniedException.class);
    session.setRegistrationType(Session.RegistrationType.ANONYMOUS);
    session.setUpdateDate(LocalDateTime.now());
    assertThatThrownBy(() -> IdentityCommandAuthorization.staleAnonymousSession(session, cutoff))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void consumedResetClaimBindsActualPlatformOwnerAndCannotNameForeignAccount() {
    var claim = new OneTimeTokenStore.TokenClaim("platform", Instant.now().plusSeconds(300));
    var target =
        Admin.builder()
            .username("synthetic-account")
            .firstName("Test")
            .lastName("Owner")
            .email("synthetic@example.org")
            .id("platform")
            .tenantId(0L)
            .type(Admin.AdminType.TENANT)
            .build();
    var origin = IdentityCommandAuthorization.claimedPasswordReset(claim, target);
    assertThat(origin.originKind()).isEqualTo("PASSWORD_RESET");
    assertThat(origin.operation()).isEqualTo("account.password");
    assertThat(origin.tenantId()).isEqualTo("0");
    assertThat(origin.roles()).isEmpty();
    target.setId("foreign");
    assertThatThrownBy(() -> IdentityCommandAuthorization.claimedPasswordReset(claim, target))
        .isInstanceOf(AccessDeniedException.class);
    target.setId("platform");
    assertThatThrownBy(
            () ->
                IdentityCommandAuthorization.claimedPasswordReset(
                    new OneTimeTokenStore.TokenClaim("platform", Instant.now().minusSeconds(1)),
                    target))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void setupRequiresHeldClaimAndDifferentVerifiedInitialPassword() {
    var invite =
        AccountInvite.builder()
            .id(12L)
            .purpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP)
            .status(AccountInviteStatus.EMAIL_SENT)
            .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
            .provisionedUserId("existing")
            .tenantId(9L)
            .initialPasswordVerifier("salted-test-verifier")
            .expiresAt(LocalDateTime.now().plusDays(1))
            .build();
    assertThatThrownBy(() -> IdentityCommandAuthorization.claimedSetupRead(invite))
        .isInstanceOf(AccessDeniedException.class);
    invite.setProvisioningStatus(AccountInviteProvisioningStatus.IN_PROGRESS);
    assertThat(IdentityCommandAuthorization.claimedSetupRead(invite).operation())
        .isEqualTo("account.read");
    var verifier = new InitialPasswordVerifier();
    invite.setInitialPasswordVerifier(verifier.encode("initial-test-password"));
    assertThatThrownBy(
            () ->
                IdentityCommandAuthorization.checkedSetupPassword(
                    invite, "initial-test-password", verifier))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(
            IdentityCommandAuthorization.checkedSetupPassword(
                    invite, "chosen-test-password", verifier)
                .operation())
        .isEqualTo("account.password");
  }
}
