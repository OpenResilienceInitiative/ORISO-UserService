package de.caritas.cob.userservice.api.service.accountpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInvitePurpose;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.AgencyFacts;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

class CheckedAccountPolicyReadsTest {
  private final AccountInviteRepository repository = mock(AccountInviteRepository.class);
  private final AgencyFacts agencies = mock(AgencyFacts.class);
  private final TaskActorTokenBroker broker = mock(TaskActorTokenBroker.class);
  private final JwtDecoder decoder = mock(JwtDecoder.class);
  private final EntityManager manager = mock(EntityManager.class);
  private final EntityManagerFactory factory = mock(EntityManagerFactory.class);
  private CheckedAccountPolicyReads policies;
  private MockRestServiceServer server;
  private AccountInvite invite;

  @BeforeEach
  void fixture() {
    var http = new RestTemplate();
    server = MockRestServiceServer.bindTo(http).build();
    policies =
        new CheckedAccountPolicyReads(
            repository,
            agencies,
            broker,
            decoder,
            http,
            new MockEnvironment()
                .withProperty(
                    "ORISO_TASK_CONTEXT_KEY", Base64.getEncoder().encodeToString(new byte[32])),
            "https://synthetic.tenant");
    ReflectionTestUtils.setField(policies, "entityManager", manager);
    when(manager.getEntityManagerFactory()).thenReturn(factory);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.bindResource(factory, new Object());
    invite =
        AccountInvite.builder()
            .id(9L)
            .tenantId(42L)
            .agencyId(7L)
            .purpose(AccountInvitePurpose.INVITE)
            .status(AccountInviteStatus.EMAIL_SENT)
            .targetRole(AccountInviteTargetRole.COUNSELLOR)
            .expiresAt(LocalDateTime.now().plusHours(1))
            .tokenHash(AccountInviteService.hash("synthetic-raw-link"))
            .build();
    when(repository.findByTokenHash(invite.getTokenHash())).thenReturn(Optional.of(invite));
    when(repository.findByIdForUpdate(9L)).thenReturn(Optional.of(invite));
    when(manager.contains(invite)).thenReturn(true);
    when(agencies.find(7))
        .thenReturn(Optional.of(new AgencyFacts.Agency(7L, 42L, false, List.of())));
    when(broker.issue(TaskPurpose.INVITE_RESERVATIONS))
        .thenReturn(
            new TaskActorTokenBroker.TaskToken(TaskActor.SETUP, "synthetic-setup-grant", 120));
    when(decoder.decode("synthetic-setup-grant"))
        .thenReturn(
            Jwt.withTokenValue("synthetic-setup-grant")
                .header("alg", "RS256")
                .issuer("https://synthetic.identity/realms/context")
                .subject("setup-subject")
                .claim("azp", "backend-setup")
                .expiresAt(Instant.now().plusSeconds(120))
                .build());
  }

  @AfterEach
  void release() {
    TransactionSynchronizationManager.unbindResourceIfPossible(factory);
    TransactionSynchronizationManager.clear();
    server.verify();
  }

  @Test
  void actualLockedLookupSuppliesExactPurposeAndSignedProjectionThenExpiresLocally() {
    server
        .expect(
            requestTo("https://synthetic.tenant/internal/tenants/42/account-provisioning-policy"))
        .andExpect(
            request -> {
              assertThat(request.getHeaders().getFirst("Authorization"))
                  .isEqualTo("Bearer synthetic-setup-grant");
              String[] parts =
                  request.getHeaders().getFirst("X-ORISO-Wizard-Policy-Context").split("\\.");
              var claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[0]));
              assertThat(claims.size()).isEqualTo(17);
              assertThat(claims.path("purpose").asText()).isEqualTo("INVITE_RESERVATIONS");
              assertThat(claims.path("origin").path("reference").asText()).isEqualTo("invite:9");
              assertThat(claims.path("origin").size()).isEqualTo(6);
              try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
                assertThat(
                        MessageDigest.isEqual(
                            mac.doFinal(("proof\n" + parts[0]).getBytes(StandardCharsets.US_ASCII)),
                            Base64.getUrlDecoder().decode(parts[1])))
                    .isTrue();
              } catch (java.security.GeneralSecurityException invalid) {
                throw new AssertionError(invalid);
              }
            })
        .andRespond(
            withSuccess("{\"id\":42,\"allowedNumberOfUsers\":2}", MediaType.APPLICATION_JSON));
    var value =
        policies.withInvitation("synthetic-raw-link", "CONSULTANT", () -> policies.read(42));
    assertThat(value.allowedNumberOfUsers()).isEqualTo(2);
    verify(repository).findByTokenHash(AccountInviteService.hash("synthetic-raw-link"));
    assertThatThrownBy(() -> policies.read(42)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void unheldInvocationCannotResolveAnOriginOrAuthenticate() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    assertThatThrownBy(
            () ->
                policies.withInvitation(
                    "synthetic-raw-link", "CONSULTANT", () -> policies.read(42)))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(repository, broker, decoder);
  }

  @Test
  void detachedOrUnknownInviteCannotAuthenticate() {
    when(manager.contains(invite)).thenReturn(false);
    assertThatThrownBy(
            () ->
                policies.withInvitation(
                    "synthetic-raw-link", "CONSULTANT", () -> policies.read(42)))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(broker, decoder);
  }

  @Test
  void revokedOrExpiredInvitationCannotAuthenticate() {
    invite.setStatus(AccountInviteStatus.REVOKED);
    assertThatThrownBy(
            () ->
                policies.withInvitation(
                    "synthetic-raw-link", "CONSULTANT", () -> policies.read(42)))
        .isInstanceOf(AccessDeniedException.class);
    invite.setStatus(AccountInviteStatus.EMAIL_SENT);
    invite.setExpiresAt(LocalDateTime.now().minusSeconds(1));
    assertThatThrownBy(
            () ->
                policies.withInvitation(
                    "synthetic-raw-link", "CONSULTANT", () -> policies.read(42)))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(broker, decoder);
  }

  @Test
  void changedHeldInvitationOrForeignTenantCannotReachThePolicyEndpoint() {
    assertThatThrownBy(
            () ->
                policies.withInvitation(
                    "synthetic-raw-link",
                    "CONSULTANT",
                    () -> {
                      invite.setAgencyId(8L);
                      return policies.read(42);
                    }))
        .isInstanceOf(AccessDeniedException.class);
    invite.setAgencyId(7L);
    assertThatThrownBy(
            () ->
                policies.withInvitation(
                    "synthetic-raw-link", "CONSULTANT", () -> policies.read(43)))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(broker, decoder);
  }

  @Test
  void currentForeignOrDeletedAgencyCannotReachThePolicyEndpoint() {
    when(agencies.find(anyLong()))
        .thenReturn(Optional.of(new AgencyFacts.Agency(7L, 43L, false, List.of())));
    assertThatThrownBy(
            () ->
                policies.withInvitation(
                    "synthetic-raw-link", "CONSULTANT", () -> policies.read(42)))
        .isInstanceOf(AccessDeniedException.class);
    when(agencies.find(anyLong()))
        .thenReturn(Optional.of(new AgencyFacts.Agency(7L, 42L, true, List.of())));
    assertThatThrownBy(
            () ->
                policies.withInvitation(
                    "synthetic-raw-link", "CONSULTANT", () -> policies.read(42)))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(decoder);
  }

  @Test
  void currentAgencyOutageIsSanitizedAndCannotReachThePolicyEndpoint() {
    when(agencies.find(7))
        .thenThrow(
            new org.springframework.web.client.RestClientException(
                "Synthetic private provider diagnostic"));
    assertThatThrownBy(
            () ->
                policies.withInvitation(
                    "synthetic-raw-link", "CONSULTANT", () -> policies.read(42)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account provisioning policy is unavailable")
        .hasNoCause();
    verifyNoInteractions(decoder);
  }
}
