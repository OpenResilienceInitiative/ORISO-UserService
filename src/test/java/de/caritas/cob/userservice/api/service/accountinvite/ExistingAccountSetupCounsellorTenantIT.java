package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.tenant.TenantFixtures;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The mailed setup link clears a counsellor's forced password change in the counsellor's own
 * Träger, whichever Träger the public request resolves to (#1058). Real tenant filter and
 * repositories; only Keycloak is faked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
@Import(TenantFixtures.class)
// The public route carries no tenant: a single-domain deployment resolves it to the main tenant.
@WithTenant(ExistingAccountSetupCounsellorTenantIT.MAIN_TENANT)
class ExistingAccountSetupCounsellorTenantIT {

  static final long MAIN_TENANT = 1L;
  private static final long OTHER_TENANT = 26L;
  private static final String INITIAL_PASSWORD = "initial-secret";

  @MockitoBean private TenantResolverService tenantResolverService;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private KeycloakService keycloak;

  @Autowired private MockMvc mockMvc;
  @Autowired private TenantFixtures fixtures;
  @Autowired private ConsultantRepository consultants;
  @Autowired private AccountInviteRepository invites;

  @AfterEach
  void removeSeededRows() {
    invites.deleteAll();
    fixtures.removeAll();
  }

  @Test
  void setupClearsTheForcedPasswordChangeOfACounsellorInAnotherTenant() throws Exception {
    var counsellor = counsellorAwaitingSetup(OTHER_TENANT);
    var invite = setupLinkFor(counsellor);

    completeSetup(invite.token());

    assertThat(statusOf(invite.id())).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(passwordChangeRequiredOf(counsellor))
        .as("forced password change of a tenant-%d counsellor after setup", OTHER_TENANT)
        .isFalse();
  }

  @Test
  void setupClearsTheForcedPasswordChangeOfACounsellorInTheMainTenant() throws Exception {
    var counsellor = counsellorAwaitingSetup(MAIN_TENANT);
    var invite = setupLinkFor(counsellor);

    completeSetup(invite.token());

    assertThat(statusOf(invite.id())).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(passwordChangeRequiredOf(counsellor))
        .as("forced password change of a tenant-%d counsellor after setup", MAIN_TENANT)
        .isFalse();
  }

  private Consultant counsellorAwaitingSetup(long tenantId) {
    var created = fixtures.consultant(tenantId);
    var counsellor =
        Tenants.in(
            tenantId,
            () -> {
              var row = consultants.findById(created.getId()).orElseThrow();
              row.setPasswordChangeRequired(true);
              return consultants.save(row);
            });
    // Keycloak still holds the temporary credential with its required action.
    when(keycloak.findById(counsellor.getId()))
        .thenReturn(
            Optional.of(
                new IdentityProfile(
                    counsellor.getId(),
                    counsellor.getUsername(),
                    null,
                    null,
                    counsellor.getEmail())));
    when(keycloak.findAllByUserId(counsellor.getId())).thenReturn(List.of("consultant"));
    when(keycloak.requiresPasswordChange(counsellor.getId())).thenReturn(true);
    return counsellor;
  }

  private IssuedLink setupLinkFor(Consultant counsellor) {
    var token = "setup-" + UUID.randomUUID();
    var saved =
        invites.saveAndFlush(
            AccountInvite.builder()
                .purpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP)
                .targetRole(AccountInviteTargetRole.COUNSELLOR)
                .tenantId(counsellor.getTenantId())
                .recipientEmail(counsellor.getEmail())
                .setupBoundUsername(counsellor.getUsername())
                .provisionedUserId(counsellor.getId())
                .activeSetupIdentityKey(counsellor.getId())
                .initialPasswordVerifier(new InitialPasswordVerifier().encode(INITIAL_PASSWORD))
                .tokenHash(AccountInviteService.hash(token))
                .status(AccountInviteStatus.EMAIL_SENT)
                .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .createDate(LocalDateTime.now())
                .build());
    return new IssuedLink(saved.getId(), token);
  }

  private void completeSetup(String token) throws Exception {
    mockMvc
        .perform(
            post("/users/account-invites/{token}/setup", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"chosen-permanent-password\"}"))
        .andExpect(status().isOk());
  }

  private AccountInviteStatus statusOf(Long inviteId) {
    return invites.findById(inviteId).orElseThrow().getStatus();
  }

  /** What the app reads for the counsellor, in the counsellor's own Träger. */
  private Boolean passwordChangeRequiredOf(Consultant counsellor) {
    return Tenants.in(
        counsellor.getTenantId(),
        () -> consultants.findById(counsellor.getId()).orElseThrow().getPasswordChangeRequired());
  }

  private record IssuedLink(Long id, String token) {}
}
