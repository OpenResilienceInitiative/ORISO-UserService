package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class WizardAccountPolicyClientTest {
  @Test
  void HeldInviteProducesTenantBoundSignedPolicyRead() throws Exception {
    var invite = new AccountInvite();
    invite.setId(41L);
    invite.setTenantId(7L);
    invite.setPurpose(AccountInvitePurpose.INVITE);
    invite.setTargetRole(AccountInviteTargetRole.COUNSELLOR);
    invite.setStatus(AccountInviteStatus.EMAIL_SENT);
    invite.setExpiresAt(LocalDateTime.now().plusHours(1));
    var origin =
        IdentityCreationOrigin.heldInvitation(
            invite, IdentityCreationOrigin.Kind.CONSULTANT, List.of("consultant"));
    var http = new RestTemplate();
    var server = MockRestServiceServer.bindTo(http).build();
    var grants = mock(TaskIdentityGrant.class);
    var jwt =
        Jwt.withTokenValue("wizard-token")
            .header("alg", "RS256")
            .subject("wizard-sub")
            .issuer("https://identity.example/realms/oriso")
            .claim("azp", "backend-config-wizard")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    when(grants.verified(TaskIdentity.CONFIG_WIZARD))
        .thenReturn(new TaskIdentityGrant.VerifiedGrant("wizard-token", jwt));
    byte[] key = "test-only-wizard-policy-key-32byte".getBytes(StandardCharsets.UTF_8);
    var adapter =
        new WizardAccountPolicyClient(
            http,
            grants,
            "https://tenant.example",
            Base64.getEncoder().encodeToString(key),
            Clock.systemUTC());
    server
        .expect(requestTo("https://tenant.example/internal/tenants/7/account-provisioning-policy"))
        .andExpect(header("Authorization", "Bearer wizard-token"))
        .andExpect(
            request -> {
              try {
                String[] parts =
                    request.getHeaders().getFirst("X-ORISO-Wizard-Policy-Context").split("\\.");
                var mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
                assertThat(Base64.getUrlDecoder().decode(parts[1]))
                    .isEqualTo(mac.doFinal(parts[0].getBytes(StandardCharsets.US_ASCII)));
                var claims =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(Base64.getUrlDecoder().decode(parts[0]));
                assertThat(claims.size()).isEqualTo(11);
                assertThat(claims.get("tenantId").asLong()).isEqualTo(7);
                assertThat(claims.get("tokenIssuer").asText())
                    .isEqualTo(jwt.getIssuer().toString());
                assertThat(claims.get("sub").asText()).isEqualTo("wizard-sub");
                assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(60);
              } catch (Exception error) {
                throw new AssertionError(error);
              }
            })
        .andRespond(
            withSuccess("{\"id\":7,\"allowedNumberOfUsers\":12}", MediaType.APPLICATION_JSON));
    assertThat(adapter.read(origin).allowedNumberOfUsers()).isEqualTo(12);
    server.verify();
  }
}
