package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic authenticated principal tests; signature/native current-grant proof is separate. */
class OrdinaryCreationAuthorizationTest {
  private static final String ISSUER = "https://identity.example/realms/oriso";
  private final AuthenticatedUser actual = mock(AuthenticatedUser.class);
  private final AdminScope reach = mock(AdminScope.class);
  private final OrdinaryAccountCreation flow =
      new OrdinaryAccountCreation(
          mock(AccountLifecycleCoordinator.class),
          mock(AccountLifecycleJournal.class),
          mock(IdentityProvisioningCommands.class),
          mock(JdbcOwnedLifecycleEffects.class),
          new AccountLifecycleCodec(new byte[32]),
          mock(MatrixSynapseService.class),
          mock(MatrixConfig.class),
          mock(AppointmentService.class),
          actual,
          ISSUER,
          Set.of("human-admin"),
          Map.of(
              "/human-roles/consultant",
              Set.of("consultant"),
              "/human-roles/centre-admin",
              Set.of("restricted-agency-admin", "user-admin"),
              "/human-roles/consultant-centre-admin",
              Set.of("consultant", "restricted-agency-admin", "user-admin")),
          mock(OrdinaryCreationTenantResolver.class));

  @AfterEach
  void reset() {
    SecurityContextHolder.clearContext();
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  private void principal(String client, Set<String> realmRoles, Set<String> clientRoles) {
    Jwt jwt =
        Jwt.withTokenValue("synthetic-not-a-real-bearer")
            .header("alg", "RS256")
            .issuer(ISSUER)
            .subject("human-subject")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(120))
            .claim("azp", client)
            .claim("realm_access", Map.of("roles", realmRoles))
            .claim("resource_access", Map.of("foreign-client", Map.of("roles", clientRoles)))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    lenient().when(actual.getUserId()).thenReturn("human-subject");
    lenient().when(reach.current()).thenReturn(new AdminScope.Tenant(7L));
  }

  @Test
  void existingRealmHumanAuthorityProducesExactTenantAgencyAndSubjectOrigin() {
    principal("human-admin", Set.of("user-admin"), Set.of());
    var scope = flow.humanScope(reach, 7L, List.of(12L), "CONSULTANT", Set.of("consultant"));
    assertEquals("7", scope.tenantId());
    assertEquals("human-subject", scope.origin().humanSubject());
    assertEquals(ISSUER, scope.origin().humanIssuer());
    assertEquals(List.of("12"), scope.origin().agencyIds());
    verify(reach).assertMay(any(AdminScope.Target.class));
  }

  @Test
  void clientRoleNamedUserAdminCannotManufactureRequiredRealmHumanAuthority() {
    principal("human-admin", Set.of(), Set.of("user-admin"));
    assertThrows(
        AccessDeniedException.class,
        () -> flow.humanScope(reach, 7L, List.of(12L), "CONSULTANT", Set.of("consultant")));
    verify(reach, never()).assertMay(any());
  }

  @Test
  void actorClientCannotBecomeHumanOriginEvenWithForgedHumanRole() {
    principal("backend-account-create", Set.of("user-admin"), Set.of());
    assertThrows(
        AccessDeniedException.class,
        () -> flow.humanScope(reach, 7L, List.of(12L), "CONSULTANT", Set.of("consultant")));
  }

  @Test
  void humanOriginCannotChooseAnotherTenantOrDropAnOptionalRole() {
    principal("human-admin", Set.of("user-admin"), Set.of());
    assertThrows(
        AccessDeniedException.class,
        () -> flow.humanScope(reach, 8L, List.of(12L), "CONSULTANT", Set.of("consultant")));
    assertThrows(
        AccessDeniedException.class,
        () ->
            flow.humanScope(
                reach,
                7L,
                List.of(12L),
                "CONSULTANT",
                Set.of("consultant", "group-chat-consultant")));
  }

  private AccountInvite invitation() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    var invite = new AccountInvite();
    invite.setId(41L);
    invite.setTenantId(7L);
    invite.setAgencyId(12L);
    invite.setPurpose(AccountInvitePurpose.INVITE);
    invite.setStatus(AccountInviteStatus.EMAIL_SENT);
    invite.setExpiresAt(LocalDateTime.now().plusHours(1));
    invite.setTargetRole(AccountInviteTargetRole.COUNSELLOR);
    invite.setAgencyIdAllocationMode(IdAllocationMode.AUTO);
    return invite;
  }

  @Test
  void heldNewCentreInvitationRetainsExactCompoundOrdinaryGroup() {
    var scope =
        flow.heldInvitationScope(
            invitation(),
            "CONSULTANT_AGENCY_ADMIN",
            Set.of("consultant", "restricted-agency-admin", "user-admin"));
    assertEquals("invite:41", scope.origin().workflowId());
    assertEquals("/human-roles/consultant-centre-admin", scope.initialGroup());
    assertNull(scope.origin().humanSubject());
  }

  @Test
  void ordinaryInvitationCannotBecomeProtectedOrPromoteExistingCentreCounsellor() {
    var invite = invitation();
    invite.setAgencyIdAllocationMode(IdAllocationMode.EXISTING);
    assertThrows(
        AccessDeniedException.class,
        () ->
            flow.heldInvitationScope(
                invite,
                "CONSULTANT_AGENCY_ADMIN",
                Set.of("consultant", "restricted-agency-admin", "user-admin")));
    invite.setTargetRole(AccountInviteTargetRole.TENANT_ADMIN);
    assertThrows(
        AccessDeniedException.class,
        () -> flow.heldInvitationScope(invite, "CONSULTANT", Set.of("consultant")));
  }

  @Test
  void expiredOrExistingAccountSetupLinkCannotAuthorizeNewCreation() {
    var invite = invitation();
    invite.setExpiresAt(LocalDateTime.now().minusSeconds(1));
    assertThrows(
        AccessDeniedException.class,
        () -> flow.heldInvitationScope(invite, "CONSULTANT", Set.of("consultant")));
    invite.setExpiresAt(LocalDateTime.now().plusHours(1));
    invite.setPurpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP);
    assertThrows(
        AccessDeniedException.class,
        () -> flow.heldInvitationScope(invite, "CONSULTANT", Set.of("consultant")));
  }
}
