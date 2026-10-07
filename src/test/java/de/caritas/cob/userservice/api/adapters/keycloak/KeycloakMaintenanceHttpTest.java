package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.MACVerifier;
import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.*;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.*;
import de.caritas.cob.userservice.api.port.out.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class KeycloakMaintenanceHttpTest {
  private HttpServer server;

  @AfterEach
  void cleanup() {
    if (server != null) server.stop(0);
    SecurityContextHolder.clearContext();
  }

  @Test
  void verifiedSelfServicePasswordUsesSignedBoundedMaintenanceHttpInsteadOfNativeAdmin()
      throws Exception {
    byte[] maintainKey = "different-test-only-origin-key-32".getBytes(StandardCharsets.UTF_8);
    var received = new AtomicReference<String>();
    var problem = new AtomicReference<Throwable>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/realms/oriso/oriso-commands/v1/accounts/own-account/password",
        exchange -> {
          try {
            assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                .isEqualTo("Bearer maintenance-only-token");
            var proof =
                JWSObject.parse(
                    exchange.getRequestHeaders().getFirst("X-ORISO-Origin-Authorization"));
            assertThat(proof.verify(new MACVerifier(maintainKey))).isTrue();
            assertThat(proof.getPayload().toJSONObject())
                .containsEntry("target", "own-account")
                .containsEntry("operation", "account.password")
                .containsEntry("originKind", "SELF_SERVICE")
                .containsEntry("taskClient", "backend-account-maintenance");
            received.set(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
          } catch (Throwable failure) {
            problem.set(failure);
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
    var config = new TaskIdentityConfiguration();
    config
        .getTasks()
        .put(
            "account-maintenance",
            new TaskIdentityCredentials(
                "backend-account-maintenance",
                "synthetic-maintenance-secret",
                "maintenance-subject"));
    var grant = mock(TaskIdentityGrant.class);
    when(grant.token(TaskIdentity.ACCOUNT_MAINTENANCE)).thenReturn("maintenance-only-token");
    var commands =
        new KeycloakTaskCommands(
            new RestTemplate(new org.springframework.http.client.JdkClientHttpRequestFactory()),
            config,
            grant,
            new IdentityOriginProof(
                Base64.getEncoder().encodeToString(new byte[32]),
                Base64.getEncoder().encodeToString(maintainKey),
                Clock.systemUTC()),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "oriso");
    var nativeClient = mock(KeycloakClient.class);
    when(nativeClient.getUsersResource())
        .thenThrow(new AssertionError("Native Admin API must not be used"));
    var service =
        new KeycloakService(
            mock(AuthenticatedUser.class),
            mock(UserAccountInputValidator.class),
            mock(IdentityClientConfig.class),
            nativeClient,
            mock(KeycloakMapper.class),
            mock(UserHelper.class),
            mock(KeycloakAuthClient.class),
            mock(TaskIdentityTokenVerifier.class));
    ReflectionTestUtils.setField(service, "taskCommands", commands);
    ReflectionTestUtils.setField(
        service,
        "commandOrigins",
        new IdentityMaintenanceOrigins(
            mock(AdminScope.class), ownedAdmins(), mock(ConsultantRepository.class), ownedUsers()));
    var jwt =
        Jwt.withTokenValue("verified-human-session")
            .header("alg", "RS256")
            .subject("own-account")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                jwt,
                List.of(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "AUTHORIZATION_USER_DEFAULT"))));

    service.updatePassword("own-account", "chosen-password");

    assertThat(problem.get()).isNull();
    assertThat(received.get())
        .isEqualTo("{\"password\":\"chosen-password\",\"passwordTemporary\":false}");
    verify(nativeClient, never()).getUsersResource();
    verify(grant).token(TaskIdentity.ACCOUNT_MAINTENANCE);
  }

  @Test
  void ownPlatformAdministratorReadAndProfileRemainBoundedAndForeignOrMissingCallerIsDenied()
      throws Exception {
    var requests = new ArrayList<String>();
    var failures = new AtomicReference<Throwable>();
    byte[] key = "different-test-only-origin-key-32".getBytes(StandardCharsets.UTF_8);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/realms/oriso/oriso-commands/v1/accounts/own-platform-admin",
        exchange -> {
          try {
            var proof =
                JWSObject.parse(
                    exchange.getRequestHeaders().getFirst("X-ORISO-Origin-Authorization"));
            assertThat(proof.verify(new MACVerifier(key))).isTrue();
            assertThat(proof.getPayload().toJSONObject())
                .containsEntry("target", "own-platform-admin")
                .containsEntry("tenantId", "0")
                .containsEntry("originKind", "SELF_SERVICE");
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(
                exchange.getRequestMethod()
                    + " "
                    + exchange.getRequestURI().getPath()
                    + " "
                    + body);
            if (exchange.getRequestMethod().equals("GET")) {
              byte[] projection =
                  "{\"id\":\"own-platform-admin\",\"username\":\"platform-admin\",\"tenantId\":0,\"preferredLanguage\":\"en\",\"enabled\":false,\"emailVerified\":false,\"roles\":[\"tenant-admin\"],\"passwordChangeRequired\":true}"
                      .getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, projection.length);
              exchange.getResponseBody().write(projection);
            } else {
              assertThat(body).isEqualTo("{\"preferredLanguage\":\"de\"}");
              exchange.sendResponseHeaders(204, -1);
            }
          } catch (Throwable failure) {
            failures.set(failure);
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
    var config = new TaskIdentityConfiguration();
    config
        .getTasks()
        .put(
            "account-maintenance",
            new TaskIdentityCredentials(
                "backend-account-maintenance",
                "synthetic-maintenance-secret",
                "maintenance-subject"));
    var grant = mock(TaskIdentityGrant.class);
    when(grant.token(TaskIdentity.ACCOUNT_MAINTENANCE)).thenReturn("maintenance-only-token");
    var commands =
        new KeycloakTaskCommands(
            new RestTemplate(new org.springframework.http.client.JdkClientHttpRequestFactory()),
            config,
            grant,
            new IdentityOriginProof(
                Base64.getEncoder().encodeToString(new byte[32]),
                Base64.getEncoder().encodeToString(key),
                Clock.systemUTC()),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "oriso");
    var nativeClient = mock(KeycloakClient.class);
    var service =
        new KeycloakService(
            mock(AuthenticatedUser.class),
            mock(UserAccountInputValidator.class),
            mock(IdentityClientConfig.class),
            nativeClient,
            mock(KeycloakMapper.class),
            mock(UserHelper.class),
            mock(KeycloakAuthClient.class),
            mock(TaskIdentityTokenVerifier.class));
    ReflectionTestUtils.setField(service, "taskCommands", commands);
    ReflectionTestUtils.setField(
        service,
        "commandOrigins",
        new IdentityMaintenanceOrigins(
            mock(AdminScope.class), ownedAdmins(), mock(ConsultantRepository.class), ownedUsers()));
    var jwt =
        Jwt.withTokenValue("verified-platform-human-session")
            .header("alg", "RS256")
            .subject("own-platform-admin")
            .claim("tenantId", "0")
            .claim("realm_access", Map.of("roles", List.of("tenant-admin")))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                jwt,
                List.of(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "AUTHORIZATION_TENANT_ADMIN"))));
    assertThat(service.findEnabledById("own-platform-admin")).contains(false);
    assertThat(service.requiresPasswordChange("own-platform-admin")).isTrue();
    service.changeLanguage("own-platform-admin", "de");
    assertThatThrownBy(() -> service.deleteUser("own-platform-admin"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> service.updateRole("own-platform-admin", "user-admin"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> service.updatePassword("foreign-account", "chosen-password"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> service.updatePassword("own-platform-admin", "chosen-password"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(requests).hasSize(4);
    assertThat(failures.get()).isNull();
    verify(nativeClient, never()).getUsersResource();
  }

  @Test
  void consumedPlatformResetLinkChangesOnlyItsPersistedOwnerAndReplayOrEscalationMakesNoHttpCall()
      throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var failures = new AtomicReference<Throwable>();
    byte[] key = "different-test-only-origin-key-32".getBytes(StandardCharsets.UTF_8);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/realms/oriso/oriso-commands/v1/accounts/platform-reset-owner/password",
        exchange -> {
          try {
            calls.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
            var proof =
                JWSObject.parse(
                    exchange.getRequestHeaders().getFirst("X-ORISO-Origin-Authorization"));
            assertThat(proof.verify(new MACVerifier(key))).isTrue();
            assertThat(proof.getPayload().toJSONObject())
                .containsEntry("target", "platform-reset-owner")
                .containsEntry("tenantId", "0")
                .containsEntry("originKind", "PASSWORD_RESET")
                .containsEntry("operation", "account.password")
                .containsEntry("roles", List.of());
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("{\"password\":\"chosen-reset-password\",\"passwordTemporary\":false}");
            exchange.sendResponseHeaders(204, -1);
          } catch (Throwable failure) {
            failures.set(failure);
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
    var config = new TaskIdentityConfiguration();
    config
        .getTasks()
        .put(
            "account-maintenance",
            new TaskIdentityCredentials(
                "backend-account-maintenance",
                "synthetic-maintenance-secret",
                "maintenance-subject"));
    var grant = mock(TaskIdentityGrant.class);
    when(grant.token(TaskIdentity.ACCOUNT_MAINTENANCE)).thenReturn("maintenance-only-token");
    var commands =
        new KeycloakTaskCommands(
            new RestTemplate(new org.springframework.http.client.JdkClientHttpRequestFactory()),
            config,
            grant,
            new IdentityOriginProof(
                Base64.getEncoder().encodeToString(new byte[32]),
                Base64.getEncoder().encodeToString(key),
                Clock.systemUTC()),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "oriso");
    var service =
        new KeycloakService(
            mock(AuthenticatedUser.class),
            mock(UserAccountInputValidator.class),
            mock(IdentityClientConfig.class),
            mock(KeycloakClient.class),
            mock(KeycloakMapper.class),
            mock(UserHelper.class),
            mock(KeycloakAuthClient.class),
            mock(TaskIdentityTokenVerifier.class));
    ReflectionTestUtils.setField(service, "taskCommands", commands);
    var admins = mock(AdminRepository.class);
    var owner =
        de.caritas.cob.userservice.api.model.Admin.builder()
            .username("synthetic-account")
            .firstName("Test")
            .lastName("Owner")
            .email("synthetic@example.org")
            .id("platform-reset-owner")
            .tenantId(0L)
            .type(de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT)
            .build();
    when(admins.findById(owner.getId())).thenReturn(Optional.of(owner));
    var tokens = mock(de.caritas.cob.userservice.api.service.auth.OneTimeTokenStore.class);
    var claim =
        new de.caritas.cob.userservice.api.service.auth.OneTimeTokenStore.TokenClaim(
            owner.getId(), Instant.now().plusSeconds(300));
    when(tokens.claim("password-reset", "verified-mailed-test-token"))
        .thenReturn(Optional.of(claim), Optional.empty());
    var reset =
        new de.caritas.cob.userservice.api.service.auth.PasswordResetService(
            mock(de.caritas.cob.userservice.api.service.user.UserService.class),
            mock(de.caritas.cob.userservice.api.service.ConsultantService.class),
            admins,
            service,
            tokens,
            mock(
                de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService
                    .class),
            mock(de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer.class),
            mock(de.caritas.cob.userservice.api.service.email.OrisoEmailBrand.class));
    assertThat(reset.confirmPasswordReset("verified-mailed-test-token", "chosen-reset-password"))
        .isTrue();
    assertThat(reset.confirmPasswordReset("verified-mailed-test-token", "different-password"))
        .isFalse();
    var origin = IdentityCommandAuthorization.claimedPasswordReset(claim, owner);
    assertThatThrownBy(() -> commands.roles(owner.getId(), List.of("user-admin"), origin))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> commands.profile(owner.getId(), Map.of("tenantId", "9"), origin))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> commands.password("foreign-owner", "password", false, origin))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(failures.get()).isNull();
    ReflectionTestUtils.invokeMethod(reset, "shutdownPasswordResetExecutor");
  }

  private AdminRepository ownedAdmins() {
    var repo = mock(AdminRepository.class);
    when(repo.findById("own-platform-admin"))
        .thenReturn(
            Optional.of(
                de.caritas.cob.userservice.api.model.Admin.builder()
                    .id("own-platform-admin")
                    .username("synthetic-account")
                    .firstName("Test")
                    .lastName("Owner")
                    .email("synthetic@example.org")
                    .tenantId(0L)
                    .type(de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT)
                    .build()));
    return repo;
  }

  private UserRepository ownedUsers() {
    var repo = mock(UserRepository.class);
    when(repo.findById("own-account"))
        .thenReturn(
            Optional.of(
                de.caritas.cob.userservice.api.model.User.builder()
                    .userId("own-account")
                    .username("synthetic-account")
                    .email("synthetic@example.org")
                    .build()));
    return repo;
  }
}
