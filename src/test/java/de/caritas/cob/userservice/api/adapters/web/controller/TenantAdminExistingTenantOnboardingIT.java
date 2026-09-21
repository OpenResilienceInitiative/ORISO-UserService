package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.identity.IdentityOtpType;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.OperatorDpaContentClient;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantCreationClient;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The accept flow of a Träger-admin invite into an EXISTING Träger (ORISO-Admin#1026, slice 4),
 * through the real public onboarding endpoints: the invitee joins the Träger — the account is
 * attached to it, no Träger is created, no tenant-ID reservation is consumed and no DPA is signed
 * (the Träger has its own agreement already).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class TenantAdminExistingTenantOnboardingIT {

  private static final long EXISTING_TENANT = 42L;
  private static final String NEW_ADMIN_ID = "b7f0f1a0-1026-4c4a-9d1e-000000000042";
  private static final String CSRF = "it-csrf-token";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF);

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private AdminRepository adminRepository;

  @MockitoBean private KeycloakService keycloakService;
  @MockitoBean private TenantCreationClient tenantCreationClient;
  @MockitoBean private OperatorDpaContentClient operatorDpaContentClient;

  private String email;

  @BeforeEach
  void identityProvider() {
    email = "joins-" + UUID.randomUUID() + "@example.org";
    when(keycloakService.createUser(any(UserDTO.class), anyString(), anyString()))
        .thenReturn(new CreatedIdentity(NEW_ADMIN_ID));
    when(keycloakService.getOtpCredential(anyString()))
        .thenReturn(
            new IdentityOtpCredential(false, "JOINTOTPSECRET", "QRBASE64", IdentityOtpType.APP));
  }

  @AfterEach
  void cleanUp() {
    accountInviteRepository.deleteAll();
    adminRepository.findById(NEW_ADMIN_ID).ifPresent(adminRepository::delete);
  }

  @Test
  void resolve_Should_TellTheWizardThatTheInviteJoinsAnExistingTenant() throws Exception {
    String token = seedExistingTenantInvite();

    mockMvc
        .perform(get("/users/account-invites/{token}/onboarding", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetRole").value("TENANT_ADMIN"))
        .andExpect(jsonPath("$.joinsExistingTenant").value(true))
        .andExpect(jsonPath("$.tenantId").value(EXISTING_TENANT))
        .andExpect(jsonPath("$.reservedTenantId").doesNotExist())
        .andExpect(jsonPath("$.tenantIdReservationToken").doesNotExist());

    verifyNoInteractions(operatorDpaContentClient);
  }

  @Test
  void register_Should_AttachTheAdminToTheExistingTenant_WithoutCreatingOne() throws Exception {
    String token = seedExistingTenantInvite();

    mockMvc
        .perform(
            post("/users/account-invites/{token}/onboarding/register", token)
                .header("X-CSRF-Token", CSRF)
                .cookie(CSRF_COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "account": { "password": "Valid-Test-Password-2026!" } }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(EXISTING_TENANT))
        .andExpect(jsonPath("$.twoFactor.secret").value("JOINTOTPSECRET"));

    verify(tenantCreationClient, never()).createTenant(any());
    verifyNoInteractions(operatorDpaContentClient);
    Admin admin = adminRepository.findById(NEW_ADMIN_ID).orElseThrow();
    assertThat(admin.getType()).isEqualTo(Admin.AdminType.TENANT);
    assertThat(admin.getTenantId()).isEqualTo(EXISTING_TENANT);
    verify(keycloakService).updatePassword(eq(NEW_ADMIN_ID), eq("Valid-Test-Password-2026!"));
    AccountInvite invite = accountInviteRepository.findAll().get(0);
    assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(invite.getAcceptedByUserId()).isEqualTo(NEW_ADMIN_ID);
    assertThat(invite.getDpaSignedAt()).isNull();
  }

  @Test
  void forwardDpa_Should_Refuse400_When_TheTenantAlreadyExists() throws Exception {
    String token = seedExistingTenantInvite();

    mockMvc
        .perform(
            post("/users/account-invites/{token}/onboarding/dpa-forward", token)
                .header("X-CSRF-Token", CSRF)
                .cookie(CSRF_COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  private String seedExistingTenantInvite() {
    String token = "join-existing-tenant-" + UUID.randomUUID();
    accountInviteRepository.save(
        AccountInvite.builder()
            .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
            .tenantId(EXISTING_TENANT)
            .tenantIdAllocationMode(IdAllocationMode.EXISTING)
            .recipientEmail(email)
            .firstName("Grace")
            .lastName("Hopper")
            .tokenHash(AccountInviteService.hash(token))
            .expiresAt(LocalDateTime.now().plusDays(1))
            .status(AccountInviteStatus.EMAIL_SENT)
            .emailVerificationStatus(EmailVerificationStatus.PENDING)
            .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
            .createDate(LocalDateTime.now())
            .build());
    return token;
  }
}
