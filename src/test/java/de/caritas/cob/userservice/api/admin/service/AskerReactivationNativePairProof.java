package de.caritas.cob.userservice.api.admin.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ScopedAskerReactivation;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerReactivationRequestDTO;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;

/** Real JPA/facade/saga/AdminScope/ACCOUNT_ADMIN and native password effects; no signed double. */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AskerReactivationNativePairProof {
  @Autowired DataSource source;
  @Autowired PlatformTransactionManager transactions;
  @Autowired UserRepository users;
  @Autowired AdminRepository admins;
  @Autowired AdminAgencyRepository adminAgencies;
  @Autowired ConsultantRepository consultants;
  @Autowired ConsultantAgencyRepository consultantAgencies;
  @Autowired SessionRepository sessions;
  @Autowired UserAgencyRepository userAgencies;

  @Test
  void actualChosenPasswordPolicyReleaseAndCurrentGrantReplayPreserveOneJpaCallback()
      throws Exception {
    assertEquals("Linux", System.getProperty("os.name"));
    assertEquals("true", required("GITHUB_ACTIONS"));
    var facts = new TreeMap<String, Object>();
    for (String flag :
        List.of(
            "actualJpa",
            "actualCurrentPlatform",
            "canonicalAdmin",
            "actualNativeRead200",
            "nativeProjectionBoundExceptMailboxCase",
            "nativeEmailCaseDifference",
            "signedPolicy400",
            "nativePolicyRefusalStateUnchanged",
            "rejectedClaimReleased",
            "differentPasswordNewOperation",
            "firstNative200",
            "newPasswordLogin",
            "oldPasswordLoginDenied",
            "savedReplay200",
            "readAfterWithdrawal200",
            "savedAfterWithdrawal403",
            "sqlAcknowledgementUnchanged",
            "restoredReplay200",
            "originalReceiptRestored",
            "sameFrozenCommands",
            "oneLocalCallback",
            "actualOriginalMachineDeadlineBound",
            "lost200FrozenUntilRenewedAfterOriginalDeadline")) facts.put(flag, false);
    var originalFacts = Set.copyOf(facts.keySet());
    for (String flag :
        List.of(
            "laterAvatarAndBirthSqlBound",
            "laterDeletionAndNativeDisableBound",
            "laterCycleSamePasswordNewOperation",
            "laterCycleFreshDeadline",
            "laterCycleSavedReceiptReplay",
            "twoCyclesTwoCallbacks",
            "nativeForeignHumanDenied",
            "sameUuidNewNativeBirthDenied",
            "secondCycleSqlAckUnchangedOnNativeDenials")) facts.put(flag, false);
    var codec =
        new AccountLifecycleCodec(Base64.getDecoder().decode(required("PAIR_CONTEXT_KEY_B64")));
    var jdbc = new JdbcTemplate(source);
    var base = new AtomicReference<>(required("PAIR_NATIVE_BASE"));
    String realm = required("PAIR_REALM"),
        realmId = required("PAIR_REALM_ID"),
        account = required("PAIR_ACCOUNT_ID"),
        username = required("PAIR_USERNAME");
    String logical = "http://protected-fixture.invalid", issuer = logical + "/realms/" + realm;
    String oldPassword = required("PAIR_OLD_PASSWORD"),
        chosen = "Chosen-reactivation-\n\t " + UUID.randomUUID(),
        weak = "weak";
    String sqlEmail = required("PAIR_SQL_EMAIL"), nativeEmail = required("PAIR_EMAIL");
    try {
      assertInstanceOf(JpaTransactionManager.class, transactions);
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertNotEquals(sqlEmail, nativeEmail);
      assertTrue(sqlEmail.equalsIgnoreCase(nativeEmail));
      facts.put("nativeEmailCaseDifference", true);
      new ResourceDatabasePopulator(
              new ClassPathResource(
                  "db/changelog/changeset/20261023_identity_asker_reactivation_367/migrate.sql"))
          .execute(source);
      TenantContext.setCurrentTenant(0L);
      var user = new User(account, null, username, sqlEmail, false);
      user.setTenantId(11L);
      user.setLanguageCode(LanguageCode.en);
      user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC).minusDays(3));
      user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
      user.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
      users.save(user);
      facts.put("actualJpa", true);
      var http = new RestTemplate();
      http.setRequestFactory(new BufferingClientHttpRequestFactory(http.getRequestFactory()));
      var firstSuccessDelivery = new AtomicBoolean(true);
      var originalEntryExpiry = new AtomicLong();
      var verifiedNativeDecoder = new AtomicReference<JwtDecoder>();
      var lastStatus = new AtomicInteger();
      var bodies = new TreeMap<String, String>();
      var receipts = new TreeMap<String, String>();
      var lastOperation = new AtomicReference<String>();
      http.getInterceptors()
          .add(
              (request, bytes, execution) -> {
                assertEquals("protected-fixture.invalid", request.getURI().getHost());
                var physical =
                    URI.create(
                        base.get()
                            + request.getURI().getRawPath()
                            + (request.getURI().getRawQuery() == null
                                ? ""
                                : "?" + request.getURI().getRawQuery()));
                var mapped =
                    new HttpRequestWrapper(request) {
                      @Override
                      public URI getURI() {
                        return physical;
                      }
                    };
                boolean mutation = request.getURI().getPath().endsWith("/reactivate"),
                    read = request.getURI().getPath().endsWith("/read");
                String operation = null;
                if (mutation || read)
                  assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                if (mutation) {
                  String body = new String(bytes, StandardCharsets.UTF_8);
                  var node = codec.read(body, JsonNode.class);
                  operation = node.path("operationId").textValue();
                  lastOperation.set(operation);
                  if (bodies.containsKey(operation))
                    assertTrue(
                        bodies.get(operation).equals(body), "Original command bytes changed");
                  else bodies.put(operation, body);
                  assertEquals(nativeEmail, node.path("change").path("expectedEmail").textValue());
                  var raw =
                      jdbc.queryForObject(
                          "select reactivation_scoped_json from user where user_id=?",
                          String.class,
                          account);
                  assertNotNull(raw);
                  assertFalse(raw.contains(chosen));
                  assertFalse(raw.contains(weak));
                  assertFalse(raw.contains(oldPassword));
                  assertTrue(
                      Set.of("FROZEN", "ACKNOWLEDGED", "NO_EFFECT")
                          .contains(codec.read(raw, JsonNode.class).path("phase").textValue()));
                }
                var response = execution.execute(mapped, bytes);
                if (request.getURI().getPath().endsWith("/token")
                    && response.getStatusCode().value() == 200
                    && verifiedNativeDecoder.get() != null) {
                  var tokenReply =
                      codec.read(
                          new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8),
                          JsonNode.class);
                  var verified =
                      verifiedNativeDecoder
                          .get()
                          .decode(tokenReply.path("access_token").textValue());
                  if (required("PAIR_ACCOUNT_ADMIN_CLIENT")
                      .equals(verified.getClaimAsString("azp"))) {
                    assertEquals(
                        15,
                        Objects.requireNonNull(verified.getExpiresAt()).getEpochSecond()
                            - Objects.requireNonNull(verified.getIssuedAt()).getEpochSecond());
                    originalEntryExpiry.compareAndSet(0, verified.getExpiresAt().getEpochSecond());
                  }
                }
                if (read && response.getStatusCode().value() == 200) {
                  var projection =
                      codec.read(
                          new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8),
                          JsonNode.class);
                  assertEquals(account, projection.path("id").textValue());
                  assertTrue(username.equalsIgnoreCase(projection.path("username").textValue()));
                  assertEquals(nativeEmail, projection.path("email").textValue());
                  assertEquals("11", projection.path("tenantId").textValue());
                  assertEquals(
                      codec.read("[\"user\"]", JsonNode.class), projection.path("effectiveRoles"));
                  assertTrue(projection.path("state").textValue().matches("[0-9a-f]{64}"));
                  if (bodies.isEmpty()) assertFalse(projection.path("enabled").booleanValue());
                  facts.put("actualNativeRead200", true);
                  facts.put("nativeProjectionBoundExceptMailboxCase", true);
                }
                if (mutation) {
                  lastStatus.set(response.getStatusCode().value());
                  if (response.getStatusCode().value() == 200) {
                    var saved =
                        codec.canonical(
                            codec.read(
                                new String(
                                    response.getBody().readAllBytes(), StandardCharsets.UTF_8),
                                JsonNode.class));
                    if (receipts.containsKey(operation))
                      assertTrue(receipts.get(operation).equals(saved), "Original receipt changed");
                    else receipts.put(operation, saved);
                    if (firstSuccessDelivery.compareAndSet(true, false)) {
                      var frozen = capsule(jdbc, account, codec);
                      assertEquals("FROZEN", frozen.path("phase").textValue());
                      assertEquals(
                          originalEntryExpiry.get(),
                          frozen.path("metadata").path("authorityValidUntil").longValue());
                      facts.put("actualOriginalMachineDeadlineBound", true);
                      response.close();
                      throw new ResourceAccessException(
                          "Deliberate original native success delivery interruption");
                    }
                  }
                }
                return response;
              });
      var identity = new IdentityConfig();
      identity.setOpenidConnectUrl(issuer + "/protocol/openid-connect");
      var environment = new MockEnvironment().withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS");
      for (var actor : TaskActor.values()) {
        String prefix = "IDENTITY_" + actor.name();
        environment.setProperty(
            prefix + "_CLIENT_ID", required("PAIR_" + actor.name() + "_CLIENT"));
        environment.setProperty(
            prefix + "_CLIENT_SECRET", required("PAIR_" + actor.name() + "_SECRET"));
        environment.setProperty(
            prefix + "_SERVICE_SUBJECT", required("PAIR_" + actor.name() + "_SUBJECT"));
      }
      var actors = new TaskActorConfiguration(environment, new KeycloakCustomConfig(), identity);
      var keycloak = new KeycloakConfig();
      keycloak.setAuthServerUrl(logical);
      keycloak.setRealm(realm);
      var decoder =
          NimbusJwtDecoder.withJwkSetUri(
                  base.get() + "/realms/" + realm + "/protocol/openid-connect/certs")
              .build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
      verifiedNativeDecoder.set(decoder);
      var mode = mock(IdentityClientConfig.class);
      when(mode.isFiveActorMode()).thenReturn(true);
      var lifecycle = spy(new DeletionLifecycleService());
      var generic = mock(IdentityReactivator.class);
      var repair = mock(IdentityReactivationRepairService.class);
      var saga =
          new IdentityReactivationSagaStore(users, new UsernameTranscoder(), generic, lifecycle);
      String initial =
          login(
              http,
              logical,
              realm,
              required("PAIR_PLATFORM_USERNAME"),
              required("PAIR_PLATFORM_PASSWORD"));
      var current =
          context(
              initial, decoder, http, identity, actors, keycloak, mode, codec, jdbc, lifecycle,
              saga, repair, generic, logical, issuer, realm, realmId);
      facts.put("actualCurrentPlatform", true);
      var checked = current.broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
      assertEquals(
          actors.require(TaskPurpose.ACCOUNT_MAINTENANCE).serviceSubject(), checked.subject());
      assertEquals(
          Set.of("userservice", "oriso-account-lifecycle", "oriso-otp"),
          Set.copyOf(decoder.decode(checked.accessToken()).getAudience()));
      try {
        http.exchange(
            logical + "/admin/realms/" + realm + "/users/" + account,
            HttpMethod.GET,
            new HttpEntity<>(bearer(checked.accessToken())),
            String.class);
        fail("ADMIN unexpectedly has stock user powers");
      } catch (HttpClientErrorException denied) {
        assertEquals(403, denied.getStatusCode().value());
      }
      facts.put("canonicalAdmin", true);
      var rejected = input(username, sqlEmail, weak);
      var first = current;
      assertThrows(BadRequestException.class, () -> first.facade.reactivateAsker(rejected));
      assertEquals(400, lastStatus.get());
      facts.put("signedPolicy400", true);
      String refusedOperation = lastOperation.get();
      var refused = capsule(jdbc, account, codec);
      assertEquals("NO_EFFECT", refused.path("phase").textValue());
      assertNull(
          jdbc.queryForObject(
              "select reactivation_operation_id from user where user_id=?", String.class, account));
      facts.put("rejectedClaimReleased", true);
      assertThrows(BadRequestException.class, () -> first.facade.reactivateAsker(rejected));
      assertEquals(refused, capsule(jdbc, account, codec));
      var refusedProjection =
          readSaved(
              http,
              logical,
              realm,
              realmId,
              account,
              actors,
              current.broker,
              codec,
              bodies.get(refusedOperation),
              initial);
      assertEquals(
          codec.read(bodies.get(refusedOperation), JsonNode.class).path("expectedState"),
          refusedProjection.path("state"));
      assertFalse(refusedProjection.path("enabled").booleanValue());
      verify(lifecycle, never()).cancelUserDeletion(any(User.class));
      facts.put("nativePolicyRefusalStateUnchanged", true);
      originalEntryExpiry.set(0);
      var interrupted = current;
      assertThrows(
          ServiceUnavailableException.class,
          () -> interrupted.facade.reactivateAsker(input(username, sqlEmail, chosen)));
      assertEquals(200, lastStatus.get());
      facts.put("firstNative200", true);
      assertNotEquals(refusedOperation, lastOperation.get());
      facts.put("differentPasswordNewOperation", true);
      var frozen = capsule(jdbc, account, codec);
      assertEquals("FROZEN", frozen.path("phase").textValue());
      assertNotNull(
          jdbc.queryForObject(
              "select delete_date from user where user_id=?", java.sql.Timestamp.class, account));
      verify(lifecycle, never()).cancelUserDeletion(any(User.class));
      long originalDeadline = frozen.path("metadata").path("authorityValidUntil").longValue();
      long waitMillis = Math.max(0, (originalDeadline + 1) * 1000 - System.currentTimeMillis());
      assertTrue(waitMillis <= 20000);
      Thread.sleep(waitMillis);
      assertTrue(Instant.now().getEpochSecond() > originalDeadline);
      String renewed =
          login(
              http,
              logical,
              realm,
              required("PAIR_PLATFORM_USERNAME"),
              required("PAIR_PLATFORM_PASSWORD"));
      assertNotEquals(initial, renewed);
      current =
          context(
              renewed, decoder, http, identity, actors, keycloak, mode, codec, jdbc, lifecycle,
              saga, repair, generic, logical, issuer, realm, realmId);
      current.facade.reactivateAsker(input(username, sqlEmail, chosen));
      assertEquals(200, lastStatus.get());
      var recovered = capsule(jdbc, account, codec);
      assertEquals(frozen.path("metadata"), recovered.path("metadata"));
      assertEquals(frozen.path("envelope"), recovered.path("envelope"));
      facts.put("lost200FrozenUntilRenewedAfterOriginalDeadline", true);
      initial = renewed;
      var acknowledged = capsule(jdbc, account, codec);
      assertEquals("ACKNOWLEDGED", acknowledged.path("phase").textValue());
      var post = jdbc.queryForMap("select * from user where user_id=?", account);
      assertNull(post.get("delete_date"));
      assertEquals("ACTIVE", post.get("deletion_lifecycle_state"));
      login(http, logical, realm, username, chosen);
      facts.put("newPasswordLogin", true);
      try {
        login(http, logical, realm, username, oldPassword);
        fail("Original password remained valid");
      } catch (HttpClientErrorException denied) {
        assertEquals(400, denied.getStatusCode().value());
      }
      facts.put("oldPasswordLoginDenied", true);
      current.facade.reactivateAsker(input(username, sqlEmail, chosen));
      assertEquals(200, lastStatus.get());
      facts.put("savedReplay200", true);
      control(base, "withdraw");
      readSaved(
          http,
          logical,
          realm,
          realmId,
          account,
          actors,
          current.broker,
          codec,
          bodies.get(lastOperation.get()),
          initial);
      facts.put("readAfterWithdrawal200", true);
      var withdrawn = current;
      assertThrows(
          ServiceUnavailableException.class,
          () -> withdrawn.facade.reactivateAsker(input(username, sqlEmail, chosen)));
      assertEquals(403, lastStatus.get());
      facts.put("savedAfterWithdrawal403", true);
      assertEquals(acknowledged, capsule(jdbc, account, codec));
      assertEquals(post, jdbc.queryForMap("select * from user where user_id=?", account));
      facts.put("sqlAcknowledgementUnchanged", true);
      control(base, "restore");
      String fresh =
          login(
              http,
              logical,
              realm,
              required("PAIR_PLATFORM_USERNAME"),
              required("PAIR_PLATFORM_PASSWORD"));
      assertNotEquals(initial, fresh);
      current =
          context(
              fresh, decoder, http, identity, actors, keycloak, mode, codec, jdbc, lifecycle, saga,
              repair, generic, logical, issuer, realm, realmId);
      current.facade.reactivateAsker(input(username, sqlEmail, chosen));
      assertEquals(200, lastStatus.get());
      facts.put("restoredReplay200", true);
      assertEquals(acknowledged, capsule(jdbc, account, codec));
      facts.put("originalReceiptRestored", true);
      assertEquals(2, bodies.size());
      assertEquals(1, receipts.size());
      facts.put("sameFrozenCommands", true);
      verify(lifecycle, times(1)).cancelUserDeletion(any(User.class));
      verifyNoInteractions(generic, repair);
      facts.put("oneLocalCallback", true);
      for (String original : originalFacts) assertEquals(true, facts.get(original), original);
      assertNativeEventCheckpoint(1, true);

      // Real retained avatar callback and deletion lifecycle. The native deactivation below is
      // the existing explicit HUMAN_ADMIN fixture boundary, not a public pre-deletion claim.
      var originalBirth =
          jdbc.queryForObject(
              "select create_date from user where user_id=?", java.sql.Timestamp.class, account);
      String originalAcknowledgement = codec.canonical(acknowledged);
      String actualSelfToken = login(http, logical, realm, username, chosen);
      var actualSelfJwt = decoder.decode(actualSelfToken);
      assertEquals(account, actualSelfJwt.getSubject());
      assertEquals("11", actualSelfJwt.getClaimAsString("tenantId"));
      var actualSelfRoles =
          new TreeSet<String>(
              (List<String>)
                  ((Map<?, ?>) actualSelfJwt.getClaims().get("realm_access")).get("roles"));
      assertEquals(Set.of("user"), actualSelfRoles);
      var self =
          new AuthenticatedUser(
              account, username, actualSelfRoles, actualSelfToken, Set.of("ROLE_USER"));
      self.setTenantId(11L);
      var savedAuthentication = SecurityContextHolder.getContext().getAuthentication();
      try {
        TenantContext.setCurrentTenant(11L);
        SecurityContextHolder.getContext()
            .setAuthentication(
                new JwtAuthenticationToken(
                    actualSelfJwt, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        var transcoder = new UsernameTranscoder();
        var patch =
            new de.caritas.cob.userservice.api.adapters.web.dto.PatchUserDTO().avatarId("magpie");
        var map =
            new de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper(identity)
                .mapOf(patch, self)
                .orElseThrow();
        var accounts =
            new de.caritas.cob.userservice.api.AccountManager(
                consultants,
                admins,
                users,
                new de.caritas.cob.userservice.api.UserServiceMapper(transcoder),
                transcoder,
                mock(AgencyService.class),
                mock(de.caritas.cob.userservice.api.admin.service.tenant.TenantService.class),
                consultantAgencies,
                self,
                sessions,
                mock(de.caritas.cob.userservice.api.service.appointment.AppointmentService.class),
                mock(de.caritas.cob.userservice.api.PatchConsultantSaga.class));
        assertTrue(accounts.patchUser(map).isPresent());
      } finally {
        TenantContext.setCurrentTenant(0L);
        SecurityContextHolder.getContext().setAuthentication(savedAuthentication);
      }
      assertEquals("magpie", users.findById(account).orElseThrow().getAvatarId());
      assertEquals(
          originalBirth,
          jdbc.queryForObject(
              "select create_date from user where user_id=?", java.sql.Timestamp.class, account));
      facts.put("laterAvatarAndBirthSqlBound", true);
      var deleting = users.findById(account).orElseThrow();
      lifecycle.beginUserDeletion(deleting, required("PAIR_PLATFORM_SUBJECT"));
      users.save(deleting);
      assertEquals(originalAcknowledgement, codec.canonical(capsule(jdbc, account, codec)));
      String nextHuman =
          login(
              http,
              logical,
              realm,
              required("PAIR_PLATFORM_USERNAME"),
              required("PAIR_PLATFORM_PASSWORD"));
      current =
          context(
              nextHuman, decoder, http, identity, actors, keycloak, mode, codec, jdbc, lifecycle,
              saga, repair, generic, logical, issuer, realm, realmId);
      String firstCommand = bodies.get(lastOperation.get());
      deactivateForNextCycle(
          http,
          logical,
          realm,
          realmId,
          account,
          actors,
          current.broker,
          codec,
          firstCommand,
          nextHuman);
      assertNativeEventCheckpoint(2, false);
      assertEquals(
          "READ_ONLY_SAFEGUARD",
          jdbc.queryForObject(
              "select deletion_lifecycle_state from user where user_id=?", String.class, account));
      assertNotNull(
          jdbc.queryForObject(
              "select delete_date from user where user_id=?", java.sql.Timestamp.class, account));
      facts.put("laterDeletionAndNativeDisableBound", true);
      current.facade.reactivateAsker(input(username, sqlEmail, chosen));
      assertEquals(200, lastStatus.get());
      var secondAcknowledged = capsule(jdbc, account, codec);
      assertEquals("ACKNOWLEDGED", secondAcknowledged.path("phase").textValue());
      assertNotEquals(
          acknowledged.path("metadata").path("operationId"),
          secondAcknowledged.path("metadata").path("operationId"));
      String secondCommand = bodies.get(lastOperation.get());
      assertEquals(
          codec.read(firstCommand, JsonNode.class).path("change").path("password"),
          codec.read(secondCommand, JsonNode.class).path("change").path("password"));
      assertEquals(3, bodies.size());
      assertEquals(2, receipts.size());
      facts.put("laterCycleSamePasswordNewOperation", true);
      assertTrue(
          secondAcknowledged.path("metadata").path("authorityValidUntil").longValue()
              > acknowledged.path("metadata").path("authorityValidUntil").longValue());
      facts.put("laterCycleFreshDeadline", true);
      assertEquals("magpie", users.findById(account).orElseThrow().getAvatarId());
      assertEquals(
          originalBirth,
          jdbc.queryForObject(
              "select create_date from user where user_id=?", java.sql.Timestamp.class, account));
      var secondSql = jdbc.queryForMap("select * from user where user_id=?", account);
      current.facade.reactivateAsker(input(username, sqlEmail, chosen));
      assertEquals(secondAcknowledged, capsule(jdbc, account, codec));
      assertEquals(secondSql, jdbc.queryForMap("select * from user where user_id=?", account));
      facts.put("laterCycleSavedReceiptReplay", true);
      verify(lifecycle, times(2)).cancelUserDeletion(any(User.class));
      facts.put("twoCyclesTwoCallbacks", true);
      assertNativeEventCheckpoint(3, true);

      String ordinaryHuman = login(http, logical, realm, username, chosen);
      assertEquals(account, decoder.decode(ordinaryHuman).getSubject());
      assertSavedDeniedForForeignHuman(
          http,
          logical,
          realm,
          realmId,
          account,
          actors,
          current.broker,
          codec,
          secondCommand,
          ordinaryHuman);
      facts.put("nativeForeignHumanDenied", true);
      recreateNativeIncarnation(base);
      lastStatus.set(0);
      lastOperation.set(null);
      var recreated = current;
      assertThrows(
          ServiceUnavailableException.class,
          () -> recreated.facade.reactivateAsker(input(username, sqlEmail, chosen)));
      assertEquals(403, lastStatus.get());
      assertEquals(
          secondAcknowledged.path("metadata").path("operationId").textValue(), lastOperation.get());
      facts.put("sameUuidNewNativeBirthDenied", true);
      assertEquals(secondAcknowledged, capsule(jdbc, account, codec));
      assertEquals(secondSql, jdbc.queryForMap("select * from user where user_id=?", account));
      verify(lifecycle, times(2)).cancelUserDeletion(any(User.class));
      verifyNoInteractions(generic, repair);
      facts.put("secondCycleSqlAckUnchangedOnNativeDenials", true);
    } finally {
      SecurityContextHolder.clearContext();
      TenantContext.clear();
      Files.writeString(Path.of(required("PAIR_WITNESS")), codec.canonical(facts));
    }
  }

  private Pair context(
      String token,
      JwtDecoder decoder,
      RestTemplate http,
      IdentityConfig identity,
      TaskActorConfiguration actors,
      KeycloakConfig keycloak,
      IdentityClientConfig mode,
      AccountLifecycleCodec codec,
      JdbcTemplate jdbc,
      DeletionLifecycleService lifecycle,
      IdentityReactivationSagaStore saga,
      IdentityReactivationRepairService repair,
      IdentityReactivator generic,
      String logical,
      String issuer,
      String realm,
      String realmId) {
    var jwt = decoder.decode(token);
    assertEquals(required("PAIR_PLATFORM_SUBJECT"), jwt.getSubject());
    assertEquals("protected-human-web", jwt.getClaimAsString("azp"));
    assertEquals("0", jwt.getClaimAsString("tenantId"));
    var roles =
        new TreeSet<String>(
            (List<String>) ((Map<?, ?>) jwt.getClaims().get("realm_access")).get("roles"));
    var human =
        new AuthenticatedUser(
            jwt.getSubject(),
            required("PAIR_PLATFORM_USERNAME"),
            roles,
            token,
            Set.of("ROLE_USER_ADMIN"));
    human.setTenantId(0L);
    assertTrue(human.isPlatformAdmin());
    var scope =
        new AdminScope(
            human,
            admins,
            adminAgencies,
            consultants,
            consultantAgencies,
            mock(AgencyService.class),
            users,
            sessions,
            userAgencies);
    ReflectionTestUtils.setField(scope, "multitenancyEnabled", true);
    assertInstanceOf(AdminScope.Platform.class, scope.current());
    var request =
        new MockHttpServletRequest("POST", "/service/useradmin/askers/deletion/reactivate");
    var auth =
        new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER_ADMIN")));
    request.setUserPrincipal(auth);
    request.addHeader("Authorization", "Bearer " + token);
    SecurityContextHolder.getContext().setAuthentication(auth);
    var broker =
        new TaskActorTokenBroker(
            actors, new KeycloakAuthClient(http, human, identity), decoder, keycloak);
    var facade =
        new AskerUserAdminFacade(
            mock(IdentityDeactivator.class),
            saga,
            repair,
            mock(UserService.class),
            new UsernameTranscoder(),
            lifecycle,
            scope);
    facade.setIdentityMode(mode);
    facade.setScopedReactivation(
        new ScopedAskerReactivation(
            mode,
            human,
            scope,
            request,
            jdbc,
            transactions,
            actors,
            broker,
            codec,
            http,
            Clock.systemUTC(),
            logical,
            issuer,
            realm,
            realmId,
            saga));
    return new Pair(facade, broker);
  }

  private record Pair(AskerUserAdminFacade facade, TaskActorTokenBroker broker) {}

  private static AskerReactivationRequestDTO input(String username, String email, String password) {
    var input = new AskerReactivationRequestDTO();
    input.setUsername(username);
    input.setEmail(email);
    input.setTenantId(11L);
    input.setPassword(password);
    return input;
  }

  private static JsonNode capsule(JdbcTemplate jdbc, String account, AccountLifecycleCodec codec) {
    return codec.read(
        jdbc.queryForObject(
            "select reactivation_scoped_json from user where user_id=?", String.class, account),
        JsonNode.class);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    assertTrue(value != null && !value.isBlank(), "Private fixture input missing");
    return value;
  }

  private static HttpHeaders bearer(String token) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return headers;
  }

  private static String login(
      RestTemplate http, String logical, String realm, String username, String password) {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("client_id", "protected-human-web");
    form.add("grant_type", "password");
    form.add("username", username);
    form.add("password", password);
    form.add("scope", "openid");
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    return (String)
        Objects.requireNonNull(
                http.postForEntity(
                        logical + "/realms/" + realm + "/protocol/openid-connect/token",
                        new HttpEntity<>(form, headers),
                        Map.class)
                    .getBody())
            .get("access_token");
  }

  private static void control(AtomicReference<String> base, String action) {
    String url = required("PAIR_CONTROL_BASE");
    assertTrue(url.matches("http://127\\.0\\.0\\.1:[0-9]+"));
    var reply = new RestTemplate().postForEntity(url + "/" + action, HttpEntity.EMPTY, Map.class);
    assertEquals(200, reply.getStatusCode().value());
    var value = Objects.requireNonNull(reply.getBody());
    assertEquals(Set.of("nativeBase", "stage"), value.keySet());
    assertEquals(action.equals("withdraw") ? "WITHDRAWN" : "RESTORED", value.get("stage"));
    String next = (String) value.get("nativeBase");
    assertTrue(next.matches("http://127\\.0\\.0\\.1:[0-9]+"));
    base.set(next);
  }

  private static void assertNativeEventCheckpoint(int expected, boolean enabled) {
    var reply =
        new RestTemplate()
            .postForEntity(required("PAIR_CONTROL_BASE") + "/events", HttpEntity.EMPTY, Map.class);
    assertEquals(200, reply.getStatusCode().value());
    var value = Objects.requireNonNull(reply.getBody());
    assertEquals(
        Set.of("count", "sameNativeBirth", "exclusiveUserProfile", "enabled"), value.keySet());
    assertEquals(expected, ((Number) value.get("count")).intValue());
    assertEquals(true, value.get("sameNativeBirth"));
    assertEquals(true, value.get("exclusiveUserProfile"));
    assertEquals(enabled, value.get("enabled"));
  }

  private static void recreateNativeIncarnation(AtomicReference<String> base) {
    var reply =
        new RestTemplate()
            .postForEntity(
                required("PAIR_CONTROL_BASE") + "/recreate-incarnation",
                HttpEntity.EMPTY,
                Map.class);
    assertEquals(200, reply.getStatusCode().value());
    var value = Objects.requireNonNull(reply.getBody());
    assertEquals(Set.of("nativeBase", "stage"), value.keySet());
    assertEquals("RECREATED", value.get("stage"));
    assertEquals(base.get(), value.get("nativeBase"));
  }

  private static void deactivateForNextCycle(
      RestTemplate http,
      String logical,
      String realm,
      String realmId,
      String account,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      String actualOriginalCommand,
      String human) {
    var target = codec.read(actualOriginalCommand, JsonNode.class).path("target").deepCopy();
    String operation = UUID.randomUUID().toString();
    ((com.fasterxml.jackson.databind.node.ObjectNode) target.path("origin"))
        .put("workflowId", operation);
    String readBody = codec.canonical(Map.of("target", target));
    var current =
        readSaved(http, logical, realm, realmId, account, actors, broker, codec, readBody, human);
    assertTrue(current.path("enabled").booleanValue());
    var body =
        codec.read(
            codec.canonical(
                Map.of(
                    "target",
                    target,
                    "operationId",
                    operation,
                    "expectedState",
                    current.path("state").textValue(),
                    "change",
                    Map.of())),
            JsonNode.class);
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    var headers =
        bearer(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE).accessToken());
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-ORISO-Human-Authorization", "Bearer " + human);
    headers.set(
        "X-ORISO-Task-Context",
        codec.maintenanceProof("deactivate", realm, realmId, account, actor, body));
    var reply =
        http.postForEntity(
            logical
                + "/realms/"
                + realm
                + "/oriso-account-maintenance/v1/accounts/"
                + account
                + "/deactivate",
            new HttpEntity<>(codec.canonical(body), headers),
            String.class);
    assertEquals(200, reply.getStatusCode().value());
    // Existing fixed deactivate receipt encoding; the helper issues no INACTIVITY authority.
    assertNotNull(
        codec.verifyMaintenanceInactivitySuspendReceipt(
            Objects.requireNonNull(reply.getBody()), realmId, actor, body));
    assertFalse(
        readSaved(http, logical, realm, realmId, account, actors, broker, codec, readBody, human)
            .path("enabled")
            .booleanValue());
  }

  private static void assertSavedDeniedForForeignHuman(
      RestTemplate http,
      String logical,
      String realm,
      String realmId,
      String account,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      String actualCommand,
      String actualForeignHuman) {
    var body = codec.read(actualCommand, JsonNode.class);
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    var headers =
        bearer(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE).accessToken());
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-ORISO-Human-Authorization", "Bearer " + actualForeignHuman);
    headers.set(
        "X-ORISO-Task-Context",
        codec.maintenanceProof("reactivate", realm, realmId, account, actor, body));
    var denied =
        assertThrows(
            HttpClientErrorException.class,
            () ->
                http.postForEntity(
                    logical
                        + "/realms/"
                        + realm
                        + "/oriso-account-maintenance/v1/accounts/"
                        + account
                        + "/reactivate",
                    new HttpEntity<>(actualCommand, headers),
                    String.class));
    assertEquals(403, denied.getStatusCode().value());
  }

  private static JsonNode readSaved(
      RestTemplate http,
      String logical,
      String realm,
      String realmId,
      String account,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      String saved,
      String human) {
    var body = codec.read(saved, JsonNode.class);
    String raw = codec.canonical(Map.of("target", body.path("target")));
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    var headers =
        bearer(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE).accessToken());
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-ORISO-Human-Authorization", "Bearer " + human);
    headers.set(
        "X-ORISO-Task-Context",
        codec.maintenanceProof(
            "read", realm, realmId, account, actor, codec.read(raw, JsonNode.class)));
    var reply =
        http.postForEntity(
            logical
                + "/realms/"
                + realm
                + "/oriso-account-maintenance/v1/accounts/"
                + account
                + "/read",
            new HttpEntity<>(raw, headers),
            String.class);
    assertEquals(200, reply.getStatusCode().value());
    var projection = codec.read(Objects.requireNonNull(reply.getBody()), JsonNode.class);
    assertEquals(account, projection.path("id").textValue());
    return projection;
  }
}
