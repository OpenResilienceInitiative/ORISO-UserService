package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.PublicDpaForwardClient;
import de.caritas.cob.userservice.tenantservice.generated.web.model.DpaSignInviteDTO;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The public DPA forward endpoint through the REAL web stack: handler mapping on both path
 * prefixes, SecurityConfig's permitAll entry and the stateless double-submit CSRF filter — the
 * layers a standalone Mockito controller test cannot exercise (#1065 review).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class TenantAdminDpaForwardWizardIT {

  private static final Long RESERVED_TENANT_ID = 83L;

  /**
   * The public onboarding POSTs are protected by the stateless double-submit CSRF filter (the SPA
   * sends a self-issued cookie/header pair); the IT does the same instead of poking holes into the
   * production CSRF configuration.
   */
  private static final String CSRF = "it-csrf-token";

  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF);

  @Autowired private MockMvc mockMvc;

  @Autowired private AccountInviteRepository accountInviteRepository;

  /** The mint is TenantService's business; this IT pins UserService's own web/security layers. */
  @MockitoBean private PublicDpaForwardClient publicDpaForwardClient;

  @Test
  void forwardDpa_isReachableAnonymously_OnBothPathPrefixes() throws Exception {
    for (String prefix : new String[] {"", "/service"}) {
      String token = "emailed-tenant-admin-forward-token-" + UUID.randomUUID();
      String reservation = UUID.randomUUID().toString();
      seedInvite(token, reservation);
      when(publicDpaForwardClient.createForwardSignLink(eq(RESERVED_TENANT_ID), eq(reservation)))
          .thenReturn(
              new DpaSignInviteDTO()
                  .token("RAWSIGNTOKEN")
                  .signLink("https://app.oriso.org/dpa-sign/RAWSIGNTOKEN")
                  .expiresAt("2026-08-29T14:31:07"));

      // no Authorization header at all: the invite token in the path is the only credential
      mockMvc
          .perform(
              post(prefix + "/users/account-invites/{token}/onboarding/dpa-forward", token)
                  .header("X-CSRF-Token", CSRF)
                  .cookie(CSRF_COOKIE))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.signUrl").value("https://app.oriso.org/dpa-sign/RAWSIGNTOKEN"))
          .andExpect(jsonPath("$.expiresAt").value("2026-08-29T14:31:07"))
          .andExpect(jsonPath("$.mailSent").value(false));
    }
  }

  /** The permitAll entry must not exempt the route from the double-submit CSRF protection. */
  @Test
  void forwardDpa_stillRequiresTheCsrfPair() throws Exception {
    String token = "emailed-tenant-admin-forward-token-" + UUID.randomUUID();
    seedInvite(token, UUID.randomUUID().toString());

    mockMvc
        .perform(post("/users/account-invites/{token}/onboarding/dpa-forward", token))
        .andExpect(status().isForbidden());
  }

  private void seedInvite(String rawToken, String reservationToken) throws Exception {
    accountInviteRepository.save(
        AccountInvite.builder()
            .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
            .tenantId(RESERVED_TENANT_ID)
            .tenantIdReservationToken(reservationToken)
            .recipientEmail("tenant.admin@oriso.org")
            .firstName("Erika")
            .lastName("Beispiel")
            .tokenHash(sha256(rawToken))
            .expiresAt(LocalDateTime.now().plusDays(1))
            .status(AccountInviteStatus.EMAIL_SENT)
            .emailVerificationStatus(EmailVerificationStatus.PENDING)
            .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
            .createDate(LocalDateTime.now())
            .build());
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
