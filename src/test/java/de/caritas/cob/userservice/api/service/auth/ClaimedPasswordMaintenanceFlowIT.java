package de.caritas.cob.userservice.api.service.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.adapters.keycloak.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.*;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.*;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.email.*;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsSmtpCredentialsDTO;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/**
 * Actual service/private claim/Redis/owning SQL/HTTP adapter; receiver is an explicit signed
 * double.
 */
@Testcontainers
class ClaimedPasswordMaintenanceFlowIT {
  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static final String ACCOUNT = "00000000-0000-4000-8000-000000000011";
  private static final String SUBJECT = "00000000-0000-4000-8000-000000000099";
  private static final String ISSUER = "https://public.identity.test/realms/fixture";

  @Test
  void actualMailedTokenTraversesPrivatePortSqlAndVerifiedHttpBeforeLocalCompletion()
      throws Exception {
    try (var f = new Fixture()) {
      String token = f.requestLink();
      assertThat(f.service.confirmPasswordReset(token, "fixture-new-password!42")).isTrue();
      assertThat(f.phase()).isEqualTo("ACKNOWLEDGED");
      assertThat(f.required()).isFalse();
      assertThat(f.effects).isEqualTo(1);
      assertThat(f.envelope()).doesNotContain("fixture-new-password!42");
      assertThat(
              f.jdbc.queryForObject(
                  "select count(*) from identity_claimed_password_intent", Integer.class))
          .isEqualTo(1);
      verifyNoInteractions(f.legacy);
      verify(f.consultants, never()).clearPasswordChangeRequirement(anyString());
    }
  }

  @Test
  void unknownHttpOutcomeSurvivesRestartAndChangedSubmissionCannotRebindIt() throws Exception {
    try (var f = new Fixture()) {
      String token = f.requestLink();
      f.loseReply = true;
      assertThatThrownBy(() -> f.service.confirmPasswordReset(token, "fixture-new-password!42"))
          .isInstanceOf(RuntimeException.class);
      String envelope = f.envelope();
      assertThat(f.phase()).isEqualTo("FROZEN");
      assertThat(f.required()).isTrue();
      assertThat(f.service.confirmPasswordReset(token, "fixture-different!42")).isFalse();
      assertThat(f.effects).isEqualTo(1);
      f.restartAdapter();
      assertThat(f.service.confirmPasswordReset(token, "fixture-new-password!42")).isTrue();
      assertThat(f.effects).isEqualTo(1);
      assertThat(f.envelope()).isEqualTo(envelope);
      assertThat(f.phase()).isEqualTo("ACKNOWLEDGED");
      assertThat(f.required()).isFalse();
    }
  }

  @Test
  void unsignedOrChangedReceiptNeverCompletesTheOwningLocalClaim() throws Exception {
    try (var f = new Fixture()) {
      String token = f.requestLink();
      f.corruptReceipt = true;
      assertThatThrownBy(() -> f.service.confirmPasswordReset(token, "fixture-new-password!42"))
          .isInstanceOf(RuntimeException.class);
      assertThat(f.phase()).isEqualTo("FROZEN");
      assertThat(f.required()).isTrue();
      f.corruptReceipt = false;
      assertThat(f.service.confirmPasswordReset(token, "fixture-new-password!42")).isTrue();
      assertThat(f.effects).isEqualTo(1);
      assertThat(f.required()).isFalse();
    }
  }

  @Test
  void savedSuccessIsReadBeforeMutableSqlAndCannotClearANewerIncarnation() throws Exception {
    try (var f = new Fixture()) {
      String token = f.requestLink();
      f.loseReply = true;
      assertThatThrownBy(() -> f.service.confirmPasswordReset(token, "fixture-new-password!42"))
          .isInstanceOf(RuntimeException.class);
      f.jdbc.update(
          "update consultant set update_date=?,password_change_required=true where consultant_id=?",
          Timestamp.valueOf(LocalDateTime.of(2026, 10, 23, 18, 0)),
          ACCOUNT);
      assertThat(f.service.confirmPasswordReset(token, "fixture-new-password!42")).isTrue();
      assertThat(f.effects).isEqualTo(1);
      assertThat(f.required()).isTrue();
      assertThat(f.phase()).isEqualTo("ACKNOWLEDGED");
    }
  }

  @Test
  void existingSetupPrivateSqlClaimUsesTheSameVerifiedNativeWireBeforeInviteCompletion()
      throws Exception {
    try (var f = new Fixture()) {
      var setup = f.setupService();
      assertThatCode(() -> setup.confirm("fixture-original-setup-token", "fixture-new-password!42"))
          .doesNotThrowAnyException();
      assertThat(f.effects).isEqualTo(1);
      assertThat(
              f.jdbc.queryForObject(
                  "select status from identity_setup_password_claim", String.class))
          .isEqualTo("ACKNOWLEDGED");
      assertThat(
              f.jdbc.queryForObject("select provisioning_status from account_invite", String.class))
          .isEqualTo("COMPLETED");
      assertThat(f.required()).isFalse();
      verifyNoInteractions(f.legacy);
    }
  }

  @Test
  void existingSetupLocalRollbackRecoversTheSameSavedNativeEffect() throws Exception {
    try (var f = new Fixture()) {
      var setup = f.setupService();
      f.failSetupCompletion = true;
      assertThatThrownBy(
              () -> setup.confirm("fixture-original-setup-token", "fixture-new-password!42"))
          .isInstanceOf(RuntimeException.class);
      assertThat(f.effects).isEqualTo(1);
      String frozen = f.envelope();
      assertThat(f.required()).isTrue();
      assertThatCode(() -> setup.confirm("fixture-original-setup-token", "fixture-new-password!42"))
          .doesNotThrowAnyException();
      assertThat(f.effects).isEqualTo(1);
      assertThat(f.envelope()).isEqualTo(frozen);
      assertThat(
              f.jdbc.queryForObject(
                  "select status from identity_setup_password_claim", String.class))
          .isEqualTo("ACKNOWLEDGED");
      assertThat(f.required()).isFalse();
    }
  }

  @Test
  void expiredUnknownResetRecoversOnlyItsExactSavedOutcome() throws Exception {
    try (var f = new Fixture()) {
      String token = f.requestLink();
      f.loseReply = true;
      assertThatThrownBy(() -> f.service.confirmPasswordReset(token, "fixture-new-password!42"))
          .isInstanceOf(RuntimeException.class);
      f.now.set(f.now.get().plusSeconds(3 * 3600));
      assertThat(f.service.confirmPasswordReset(token, "fixture-new-password!42")).isTrue();
      assertThat(f.effects).isEqualTo(1);
      assertThat(f.phase()).isEqualTo("ACKNOWLEDGED");
    }
  }

  @Test
  void expiredAbsentAndForeignUsernameCannotDispatchAClaimedPassword() throws Exception {
    try (var f = new Fixture()) {
      String token = f.requestLink();
      f.foreignUsername = true;
      assertThatThrownBy(() -> f.service.confirmPasswordReset(token, "fixture-new-password!42"))
          .isInstanceOf(RuntimeException.class);
      assertThat(f.effects).isZero();
      assertThat(f.required()).isTrue();
      f.foreignUsername = false;
      f.expiredAbsent = true;
      f.now.set(f.now.get().plusSeconds(3 * 3600));
      assertThatThrownBy(() -> f.service.confirmPasswordReset(token, "fixture-new-password!42"))
          .isInstanceOf(RuntimeException.class);
      assertThat(f.effects).isZero();
      assertThat(f.required()).isTrue();
    }
  }

  @Test
  void expiredUnknownSetupRecoversOriginalSavedReceiptWithoutFreshPasswordEffect()
      throws Exception {
    try (var f = new Fixture()) {
      var setup = f.setupService();
      f.loseReply = true;
      assertThatThrownBy(
              () -> setup.confirm("fixture-original-setup-token", "fixture-new-password!42"))
          .isInstanceOf(RuntimeException.class);
      assertThat(f.effects).isEqualTo(1);
      String envelope = f.envelope();
      f.now.set(f.now.get().plusSeconds(3 * 3600));
      assertThatCode(() -> setup.confirm("fixture-original-setup-token", "fixture-new-password!42"))
          .doesNotThrowAnyException();
      assertThat(f.effects).isEqualTo(1);
      assertThat(f.envelope()).isEqualTo(envelope);
      assertThat(
              f.jdbc.queryForObject(
                  "select status from identity_setup_password_claim", String.class))
          .isEqualTo("ACKNOWLEDGED");
    }
  }

  @Test
  void reviewedCatalogReadsEveryCandidateAndFreezesOnlyTheUniqueMatch() throws Exception {
    try (var f = new Fixture()) {
      f.candidateCatalog = true;
      f.restartAdapter();
      String token = f.requestLink();
      assertThatCode(
              () ->
                  assertThat(f.service.confirmPasswordReset(token, "fixture-new-password!42"))
                      .isTrue())
          .doesNotThrowAnyException();
      assertThat(f.effects).isEqualTo(1);
      assertThat(f.readCandidates)
          .containsExactlyInAnyOrder("reviewed-wrong", "reviewed-consultant");
      assertThat(
              f.jdbc.queryForObject(
                  "select workflow_json from identity_claimed_password_intent", String.class))
          .contains("reviewed-consultant")
          .doesNotContain("reviewed-wrong");
    }
  }

  @Test
  void ambiguousCatalogRefusesBeforeAnyNativePasswordMutation() throws Exception {
    try (var f = new Fixture()) {
      f.candidateCatalog = true;
      f.ambiguousCatalog = true;
      f.restartAdapter();
      String token = f.requestLink();
      assertThatThrownBy(() -> f.service.confirmPasswordReset(token, "fixture-new-password!42"))
          .isInstanceOf(RuntimeException.class);
      assertThat(f.effects).isZero();
      assertThat(f.required()).isTrue();
      assertThat(f.phase()).isEqualTo("PREPARED");
    }
  }

  @Test
  void unknownFrozenCatalogIntentNeverReselectsWhenCandidateOrderChanges() throws Exception {
    try (var f = new Fixture()) {
      f.candidateCatalog = true;
      f.restartAdapter();
      String token = f.requestLink();
      f.loseReply = true;
      assertThatThrownBy(() -> f.service.confirmPasswordReset(token, "fixture-new-password!42"))
          .isInstanceOf(RuntimeException.class);
      assertThat(f.effects).isEqualTo(1);
      String envelope = f.envelope();
      f.reverseCatalog = true;
      f.restartAdapter();
      f.readCandidates.clear();
      assertThatCode(
              () ->
                  assertThat(f.service.confirmPasswordReset(token, "fixture-new-password!42"))
                      .isTrue())
          .doesNotThrowAnyException();
      assertThat(f.effects).isEqualTo(1);
      assertThat(f.envelope()).isEqualTo(envelope);
      assertThat(f.readCandidates).containsExactly("reviewed-consultant");
    }
  }

  @Test
  void signedPasswordPolicyRefusalIsRecordedBeforeTheActualResetClaimIsReleased() throws Exception {
    try (var f = new Fixture()) {
      f.policyRefusal = true;
      String token = f.requestLink();
      assertThatThrownBy(() -> f.service.confirmPasswordReset(token, "fixture-new-password!42"))
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses
                  .CustomValidationHttpStatusException.class);
      assertThat(f.effects).isZero();
      assertThat(f.required()).isTrue();
      assertThat(f.phase()).isEqualTo("REJECTED");
      assertThat(
              f.jdbc.queryForObject(
                  "select receipt from identity_claimed_password_intent", String.class))
          .contains("NO_EFFECT");
      assertThatThrownBy(() -> f.service.confirmPasswordReset(token, "fixture-new-password!42"))
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses
                  .CustomValidationHttpStatusException.class);
      assertThat(
              f.jdbc.queryForObject(
                  "select count(*) from identity_claimed_password_intent", Integer.class))
          .isEqualTo(1);
    }
  }

  @Test
  void unsignedPolicy400CannotReleaseOrRebindTheActualResetClaim() throws Exception {
    try (var f = new Fixture()) {
      f.barePolicy = true;
      String token = f.requestLink();
      assertThatThrownBy(() -> f.service.confirmPasswordReset(token, "fixture-new-password!42"))
          .isInstanceOf(RuntimeException.class);
      assertThat(f.effects).isZero();
      assertThat(f.phase()).isEqualTo("FROZEN");
      assertThat(f.service.confirmPasswordReset(token, "fixture-other-password!42")).isFalse();
      assertThat(f.required()).isTrue();
    }
  }

  @Test
  void setupPolicyReceiptIsReplayedWithoutAdvancingTheSamePasswordClaim() throws Exception {
    try (var f = new Fixture()) {
      var setup = f.setupService();
      f.policyRefusal = true;
      assertThatThrownBy(
              () -> setup.confirm("fixture-original-setup-token", "fixture-new-password!42"))
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses
                  .CustomValidationHttpStatusException.class);
      String claim =
          f.jdbc.queryForObject("select claim_id from identity_setup_password_claim", String.class);
      assertThatThrownBy(
              () -> setup.confirm("fixture-original-setup-token", "fixture-new-password!42"))
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses
                  .CustomValidationHttpStatusException.class);
      assertThat(
              f.jdbc.queryForObject(
                  "select claim_id from identity_setup_password_claim", String.class))
          .isEqualTo(claim);
      assertThat(
              f.jdbc.queryForObject(
                  "select count(*) from identity_claimed_password_intent", Integer.class))
          .isEqualTo(1);
      assertThat(f.effects).isZero();
      assertThat(f.required()).isTrue();
    }
  }

  @Test
  void signedForbiddenResetStaysBoundAndReplaysItsOriginalPublicRefusal() throws Exception {
    try (var f = new Fixture()) {
      f.policyRefusal = true;
      f.policyStatus = 403;
      String token = f.requestLink();
      assertThatThrownBy(() -> f.service.confirmPasswordReset(token, "fixture-new-password!42"))
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
      assertThat(f.phase()).isEqualTo("REFUSED");
      var store = new RedisOneTimeTokenStore(f.redis, f.prefix);
      var issued = store.passwordResetIssuance(token).orElseThrow();
      String digest = f.updater.passwordResetRequestDigest(issued, "fixture-new-password!42");
      assertThat(store.claimPasswordReset(token, digest).orElseThrow().status())
          .isEqualTo("REFUSED");
      assertThatThrownBy(() -> f.service.confirmPasswordReset(token, "fixture-new-password!42"))
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
      assertThat(f.service.confirmPasswordReset(token, "fixture-different!42")).isFalse();
      assertThat(f.effects).isZero();
      assertThat(f.required()).isTrue();
    }
  }

  @Test
  void signedConflictSetupIsTerminalBoundCustodyWithoutUnknownOrNewVersion() throws Exception {
    try (var f = new Fixture()) {
      var setup = f.setupService();
      f.policyRefusal = true;
      f.policyStatus = 409;
      assertThatThrownBy(
              () -> setup.confirm("fixture-original-setup-token", "fixture-new-password!42"))
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
      assertThat(f.phase()).isEqualTo("REFUSED");
      assertThat(
              f.jdbc.queryForObject(
                  "select status from identity_setup_password_claim", String.class))
          .isEqualTo("REFUSED");
      assertThatThrownBy(
              () -> setup.confirm("fixture-original-setup-token", "fixture-new-password!42"))
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
      assertThat(
              f.jdbc.queryForObject(
                  "select claim_version from identity_setup_password_claim", Long.class))
          .isEqualTo(1L);
      assertThat(f.effects).isZero();
      assertThat(f.required()).isTrue();
    }
  }

  @Test
  void boundedSecretSweepErasesOnlyExpiredCiphertextAndCannotRemintAnOldClaim() throws Exception {
    try (var f = new Fixture()) {
      String token = f.requestLink();
      f.loseReply = true;
      assertThatThrownBy(() -> f.service.confirmPasswordReset(token, "fixture-new-password!42"))
          .isInstanceOf(RuntimeException.class);
      String metadata =
          f.jdbc.queryForObject(
              "select metadata_json from identity_claimed_password_intent", String.class);
      String originalId =
          f.jdbc.queryForObject(
              "select claim_id from identity_claimed_password_intent", String.class);
      String digest =
          f.jdbc.queryForObject(
              "select intent_digest from identity_claimed_password_intent", String.class);
      assertThat(digest).matches("[0-9a-f]{64}");
      for (int i = 0; i < 101; i++) {
        String id = UUID.randomUUID().toString();
        f.jdbc.update(
            "insert into identity_claimed_password_intent select ?,claim_version,?,workflow_kind,realm_id,account_id,tenant_id,owner_client,owner_subject,request_digest,authority_valid_until,target_json,workflow_json,local_state_json,metadata_json,intent_envelope,intent_digest,status,receipt,updated_at from identity_claimed_password_intent where claim_id=?",
            id,
            UUID.randomUUID().toString(),
            originalId);
        if (i == 100)
          f.jdbc.update(
              "update identity_claimed_password_intent set authority_valid_until=? where claim_id=?",
              f.clock.instant().plusSeconds(30 * 86400).getEpochSecond(),
              id);
      }
      assertThat(f.adapter.expireSecretCustody()).isZero();
      var store = new RedisOneTimeTokenStore(f.redis, f.prefix);
      var held =
          store
              .claimPasswordReset(
                  token,
                  f.jdbc.queryForObject(
                      "select request_digest from identity_claimed_password_intent where claim_id=?",
                      String.class,
                      originalId))
              .orElseThrow();
      f.now.set(f.now.get().plusSeconds(9 * 86400));
      assertThat(f.adapter.expireSecretCustody()).isEqualTo(100);
      assertThat(f.adapter.expireSecretCustody()).isEqualTo(1);
      assertThat(f.adapter.expireSecretCustody()).isZero();
      assertThat(
              f.jdbc.queryForObject(
                  "select count(*) from identity_claimed_password_intent where intent_envelope is not null",
                  Integer.class))
          .isEqualTo(1);
      assertThat(
              f.jdbc.queryForObject(
                  "select status from identity_claimed_password_intent where claim_id=?",
                  String.class,
                  originalId))
          .isEqualTo("EXPIRED_UNKNOWN");
      assertThat(
              f.jdbc.queryForObject(
                  "select metadata_json from identity_claimed_password_intent where claim_id=?",
                  String.class,
                  originalId))
          .isEqualTo(metadata);
      assertThat(
              f.jdbc.queryForObject(
                  "select intent_digest from identity_claimed_password_intent where claim_id=?",
                  String.class,
                  originalId))
          .isEqualTo(digest);
      store.finishPasswordResetClaim(
          held, true); // Late custody ACK is data and cannot rebind the expired SQL command.
      assertThatThrownBy(() -> f.service.confirmPasswordReset(token, "fixture-new-password!42"))
          .isInstanceOf(RuntimeException.class);
      assertThat(f.effects).isEqualTo(1);
      assertThat(
              f.jdbc.queryForObject(
                  "select count(*) from identity_claimed_password_intent", Integer.class))
          .isEqualTo(102);
    }
  }

  @Test
  void actualResetSupportsEveryOwnedKindAndLegitimateAdministratorConsultantPair()
      throws Exception {
    for (String kind :
        List.of("USER", "CONSULTANT", "AGENCY_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN")) {
      for (boolean dual : kind.endsWith("ADMIN") ? List.of(false, true) : List.of(false)) {
        try (var f = new Fixture(kind, dual)) {
          String token = f.requestLink();
          assertThat(f.service.confirmPasswordReset(token, "fixture-new-password!42")).isTrue();
          assertThat(f.effects).isEqualTo(1);
          assertThat(f.phase()).isEqualTo("ACKNOWLEDGED");
          if (dual || "CONSULTANT".equals(kind)) assertThat(f.required()).isFalse();
          assertThat(f.readCandidates).containsExactly("reviewed-" + kind.toLowerCase(Locale.ROOT));
          verifyNoInteractions(f.legacy);
        }
      }
    }
  }

  @Test
  void actualAppConsultantResetUsesItsCoherentAdministratorPrimaryWithoutCallerKind() throws Exception {
    for (String kind : List.of("AGENCY_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN")) {
      try (var f = new Fixture(kind, true)) {
        f.service.requestPasswordReset("testuser", "en");
        assertThat(f.resetUrl).isNotNull();
        String token = f.resetUrl.substring(f.resetUrl.indexOf("token=") + 6);
        assertThat(f.service.confirmPasswordReset(token, "fixture-new-password!42")).isTrue();
        assertThat(f.effects).isEqualTo(1);
        assertThat(f.required()).isFalse();
        assertThat(f.phase()).isEqualTo("ACKNOWLEDGED");
        assertThat(f.readCandidates).containsExactly("reviewed-" + kind.toLowerCase(Locale.ROOT));
        verifyNoInteractions(f.legacy);
      }
    }
  }

  private static final class Fixture implements AutoCloseable {
    final AccountLifecycleCodec codec = new AccountLifecycleCodec(new byte[32]);
    final DriverManagerDataSource source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:causal"
                + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
            "sa",
            "");
    final JdbcTemplate jdbc = new JdbcTemplate(source);
    final DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
    final IdentityClientConfig mode = mock(IdentityClientConfig.class);
    final TaskActorConfiguration actors = mock(TaskActorConfiguration.class);
    final TaskActorTokenBroker broker = mock(TaskActorTokenBroker.class);
    final TaskActorCredentials actor =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_ADMIN, "ORISO_ACCOUNT_ADMIN", "unused-fixture", SUBJECT);
    final KeycloakClient legacy = mock(KeycloakClient.class);
    final KeycloakService updater =
        new KeycloakService(
            mock(AuthenticatedUser.class),
            mock(UserAccountInputValidator.class),
            mode,
            legacy,
            mock(KeycloakMapper.class),
            mock(UserHelper.class),
            mock(KeycloakAuthClient.class));
    final ConsultantService consultants = mock(ConsultantService.class);
    final UserService users = mock(UserService.class);
    final AdminRepository admins = mock(AdminRepository.class);
    String ownedKind = "CONSULTANT";
    long ownedTenant = 42;
    boolean ownsConsultant = true;
    final LettuceConnectionFactory connection =
        new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    final String prefix = "test:causal-flow:" + UUID.randomUUID() + ":";
    final StringRedisTemplate redis;
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final PasswordResetService service;
    ClaimedPasswordMaintenance adapter;
    final String sqlUsername = new UsernameTranscoder().encodeUsername("testuser");
    final String nativeUsername = sqlUsername.toLowerCase(Locale.ROOT);
    final java.util.concurrent.atomic.AtomicReference<Instant> now =
        new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
    final Clock clock =
        new Clock() {
          public ZoneId getZone() {
            return ZoneId.systemDefault();
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return now.get();
          }
        };
    String resetUrl, savedReply;
    int effects;
    boolean loseReply, corruptReceipt, failSetupCompletion, expiredAbsent, foreignUsername;
    boolean candidateCatalog, ambiguousCatalog, reverseCatalog, policyRefusal, barePolicy;
    int savedStatus = 200;
    int policyStatus = 400;
    final Set<String> readCandidates = new HashSet<>();

    Fixture() throws Exception {
      connection.afterPropertiesSet();
      redis = new StringRedisTemplate(connection);
      redis.afterPropertiesSet();
      jdbc.execute(
          "create table user(user_id varchar(36) primary key,tenant_id bigint,username varchar(255),email varchar(255),create_date timestamp,update_date timestamp,delete_date timestamp,deletion_lifecycle_state varchar(32))");
      jdbc.execute(
          "create table consultant(consultant_id varchar(36) primary key,tenant_id bigint,username varchar(255),email varchar(255),create_date timestamp,update_date timestamp,delete_date timestamp,deletion_lifecycle_state varchar(32),managed_paused boolean,password_change_required boolean)");
      jdbc.execute(
          "create table admin(admin_id varchar(36) primary key,tenant_id bigint,username varchar(255),email varchar(255),create_date timestamp,update_date timestamp,type varchar(16),managed_paused boolean)");
      jdbc.update(
          "insert into consultant values(?,42,?,'Original@Example.org',?,?,null,'ACTIVE',false,true)",
          ACCOUNT,
          sqlUsername,
          Timestamp.valueOf(LocalDateTime.of(2026, 10, 22, 12, 0)),
          Timestamp.valueOf(LocalDateTime.of(2026, 10, 22, 13, 0)));
      try (var c = source.getConnection()) {
        ScriptUtils.executeSqlScript(
            c,
            new ClassPathResource(
                "db/changelog/changeset/20261023_identity_claimed_password_intent_367/migrate.sql"));
      }
      when(mode.isFiveActorMode()).thenReturn(true);
      when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(actor);
      var checked = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
      when(checked.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);
      when(checked.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
      when(checked.accessToken()).thenReturn("fixture-admin");
      when(checked.issuer()).thenReturn(ISSUER);
      when(checked.clientId()).thenReturn(actor.clientId());
      when(checked.subject()).thenReturn(SUBJECT);
      when(checked.validatedJwtIssuedAt())
          .thenAnswer(x -> clock.instant().minusSeconds(1).getEpochSecond());
      when(checked.validatedJwtExpiresAt())
          .thenAnswer(x -> clock.instant().plusSeconds(300).getEpochSecond());
      when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(checked);
      server.createContext(
          "/realms/fixture/oriso-account-maintenance/v1/accounts/" + ACCOUNT + "/password-reset",
          exchange -> {
            try {
              assertThat(exchange.getRequestMethod()).isEqualTo("POST");
              assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                  .isEqualTo("Bearer fixture-admin");
              assertThat(exchange.getRequestHeaders().getFirst("X-ORISO-Human-Authorization"))
                  .isNull();
              assertThat(exchange.getRequestHeaders().getFirst("X-ORISO-Task-Context"))
                  .isNotBlank();
              var body =
                  codec.read(
                      new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                      JsonNode.class);
              String reply;
              if (exchange.getRequestURI().getPath().endsWith("/read")) {
                String candidate = body.path("workflow").path("profileId").textValue();
                readCandidates.add(candidate);
                if (candidateCatalog && "reviewed-wrong".equals(candidate) && !ambiguousCatalog) {
                  exchange.sendResponseHeaders(403, -1);
                  return;
                }

                if (savedReply == null && expiredAbsent) {
                  exchange.sendResponseHeaders(400, -1);
                  return;
                }
                reply = savedReply == null ? projection() : savedReply;
              } else {
                if (barePolicy) {
                  exchange.sendResponseHeaders(400, -1);
                  return;
                }
                if (savedReply == null) {
                  if (!policyRefusal) effects++;
                  savedStatus = policyRefusal ? policyStatus : 200;
                  savedReply = signedReply(body, policyRefusal);
                }
                reply = savedReply;
                if (loseReply) {
                  loseReply = false;
                  exchange.sendResponseHeaders(503, -1);
                  return;
                }
              }
              if (corruptReceipt && savedReply != null) reply = reply.replace("UPDATED", "DELETED");
              byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(savedReply == null ? 200 : savedStatus, bytes.length);
              exchange.getResponseBody().write(bytes);
            } catch (Exception failure) {
              throw new java.io.IOException(failure);
            } finally {
              exchange.close();
            }
          });
      server.start();
      restartAdapter();
      var model = new Consultant();
      model.setId(ACCOUNT);
      model.setTenantId(42L);
      model.setUsername(sqlUsername);
      model.setEmail("Original@Example.org");
      when(consultants.findConsultantForSignIn(anyString())).thenReturn(Optional.of(model));
      var settings = mock(ApplicationSettingsService.class);
      when(settings.getGlobalSmtpSettingsSnapshot())
          .thenReturn(
              Optional.of(
                  new ApplicationSettingsSmtpCredentialsDTO()
                      .globalFeatureSystemNotificationEmailsEnabled(true)
                      .globalSmtpEnabled(true)
                      .globalSmtpHost("smtp.example.org")
                      .globalSmtpPort("587")
                      .globalSmtpSecure(false)
                      .globalSmtpFrom("noreply@example.org")
                      .globalSmtpUsername("fixture")
                      .globalSmtpPassword("unused")));
      service =
          new PasswordResetService(
              users,
              consultants,
              admins,
              updater,
              new RedisOneTimeTokenStore(redis, prefix),
              settings,
              mock(OrisoEmailRenderer.class),
              mock(OrisoEmailBrand.class));
      ReflectionTestUtils.setField(service, "passwordResetExecutor", (Executor) Runnable::run);
      ReflectionTestUtils.setField(
          service, "passwordResetFrontendBaseUrl", "https://app.example.org");
      ReflectionTestUtils.setField(
          service,
          "mailSender",
          (PasswordResetService.PasswordResetMailSender)
              (recipient, locale, url, tenant, base, smtp) -> {
                assertThat(recipient).isEqualTo("Original@Example.org");
                resetUrl = url;
              });
    }

    Fixture(String kind, boolean dual) throws Exception {
      this();
      ownedKind = kind;
      ownedTenant = "PLATFORM_ADMIN".equals(kind) ? 0 : 42;
      ownsConsultant = "CONSULTANT".equals(kind) || dual;
      if (!ownsConsultant) jdbc.update("delete from consultant where consultant_id=?", ACCOUNT);
      else
        jdbc.update(
            "update consultant set tenant_id=? where consultant_id=?", ownedTenant, ACCOUNT);
      if (ownsConsultant) {
        var model = new Consultant();
        model.setId(ACCOUNT);
        model.setTenantId(ownedTenant);
        model.setUsername(sqlUsername);
        model.setEmail("Original@Example.org");
        when(consultants.findConsultantForSignIn(anyString())).thenReturn(Optional.of(model));
      }
      if ("USER".equals(kind)) {
        jdbc.update(
            "insert into user values(?,?,?,'Original@Example.org',?,?,null,'ACTIVE')",
            ACCOUNT,
            ownedTenant,
            sqlUsername,
            Timestamp.valueOf(LocalDateTime.of(2026, 10, 22, 12, 0)),
            Timestamp.valueOf(LocalDateTime.of(2026, 10, 22, 13, 0)));
        var model = new de.caritas.cob.userservice.api.model.User();
        model.setUserId(ACCOUNT);
        model.setTenantId(ownedTenant);
        model.setUsername(sqlUsername);
        model.setEmail("Original@Example.org");
        when(users.findUserByUsername(anyString())).thenReturn(Optional.of(model));
      } else if (kind.endsWith("ADMIN")) {
        String type =
            switch (kind) {
              case "AGENCY_ADMIN" -> "AGENCY";
              case "TENANT_ADMIN" -> "TENANT";
              default -> "SUPER";
            };
        jdbc.update(
            "insert into admin values(?,?,?,'Original@Example.org',?,?,?,false)",
            ACCOUNT,
            ownedTenant,
            sqlUsername,
            Timestamp.valueOf(LocalDateTime.of(2026, 10, 22, 12, 0)),
            Timestamp.valueOf(LocalDateTime.of(2026, 10, 22, 13, 0)),
            type);
        var admin =
            de.caritas.cob.userservice.api.model.Admin.builder()
                .id(ACCOUNT)
                .tenantId(ownedTenant)
                .username(sqlUsername)
                .firstName("Fixture")
                .lastName("Administrator")
                .email("Original@Example.org")
                .type(de.caritas.cob.userservice.api.model.Admin.AdminType.valueOf(type))
                .build();
        when(admins.findForSignIn(anyString())).thenReturn(Optional.of(admin));
        ReflectionTestUtils.setField(
            service, "passwordResetAdminFrontendBaseUrl", "https://admin.example.org");
      }
    }

    void restartAdapter() {
      var profiles = new HashMap<String, List<ClaimedPasswordMaintenance.ProfileSelector>>();
      for (String kind :
          Set.of("USER", "CONSULTANT", "AGENCY_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN"))
        profiles.put(
            kind,
            List.of(
                new ClaimedPasswordMaintenance.ProfileSelector(
                    kind.endsWith("ADMIN") && !kind.equals("AGENCY_ADMIN")
                        ? "PROTECTED"
                        : "ORDINARY",
                    "reviewed-" + kind.toLowerCase(Locale.ROOT),
                    1)));
      if (candidateCatalog) {
        var reviewed = profiles.get("CONSULTANT").getFirst();
        var wrong = new ClaimedPasswordMaintenance.ProfileSelector("ORDINARY", "reviewed-wrong", 7);
        profiles.put(
            "CONSULTANT", reverseCatalog ? List.of(reviewed, wrong) : List.of(wrong, reviewed));
      }
      adapter =
          new ClaimedPasswordMaintenance(
              mode,
              jdbc,
              transactions,
              actors,
              broker,
              codec,
              new RestTemplate(),
              clock,
              "http://127.0.0.1:" + server.getAddress().getPort(),
              ISSUER,
              "fixture",
              "fixture-realm",
              profiles);
      updater.setClaimedPasswordMaintenance(adapter);
    }

    ExistingAccountSetupService setupService() throws Exception {
      jdbc.execute(
          "create table account_invite(id bigint primary key,purpose varchar(64),status varchar(32),provisioning_status varchar(32),provisioned_user_id varchar(36),token_hash varchar(64),expires_at timestamp,target_role varchar(64),tenant_id bigint,setup_bound_username varchar(512),recipient_email varchar(512),initial_password_verifier varchar(255),provisioning_failure_reason varchar(1024),update_date timestamp)");
      String hash =
          HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest("fixture-original-setup-token".getBytes(StandardCharsets.UTF_8)));
      jdbc.update(
          "insert into account_invite values(11,'EXISTING_ACCOUNT_SETUP','EMAIL_SENT','PENDING',?,?,?,'COUNSELLOR',42,?,'Original@Example.org','fixture-salted-verifier',null,null)",
          ACCOUNT,
          hash,
          Timestamp.valueOf(LocalDateTime.now(clock).plusHours(2)),
          sqlUsername);
      try (var c = source.getConnection()) {
        ScriptUtils.executeSqlScript(
            c,
            new ClassPathResource(
                "db/changelog/changeset/20261022_identity_setup_password_claim_367/migrate.sql"));
      }
      var invites = mock(AccountInviteRepository.class);
      var localConsultants = mock(ConsultantRepository.class);
      var initial = mock(InitialPasswordVerifier.class);
      when(initial.matches(anyString(), anyString()))
          .thenAnswer(x -> "fixture-initial-password".equals(x.getArgument(0)));
      when(invites.findByTokenHash(anyString()))
          .thenAnswer(x -> setupInvite("token_hash", x.getArgument(0)));
      when(invites.findByIdForUpdate(anyLong()))
          .thenAnswer(x -> setupInvite("id", x.getArgument(0)));
      when(localConsultants.findByIdAndDeleteDateIsNull(ACCOUNT))
          .thenAnswer(
              x -> {
                var c = new Consultant();
                c.setId(ACCOUNT);
                c.setUsername(sqlUsername);
                c.setEmail("Original@Example.org");
                c.setTenantId(42L);
                c.setPasswordChangeRequired(required());
                return Optional.of(c);
              });
      when(localConsultants.save(any(Consultant.class)))
          .thenAnswer(
              x -> {
                var c = (Consultant) x.getArgument(0);
                jdbc.update(
                    "update consultant set password_change_required=?,update_date=? where consultant_id=?",
                    c.getPasswordChangeRequired(),
                    Timestamp.valueOf(LocalDateTime.now()),
                    ACCOUNT);
                return c;
              });
      when(invites.recordIndeterminateExistingAccountSetup(
              anyLong(), any(), any(), any(), anyString(), any()))
          .thenAnswer(
              x ->
                  jdbc.update(
                      "update account_invite set provisioning_failure_reason='SETUP_OUTCOME_INDETERMINATE' where id=11 and provisioning_status='IN_PROGRESS'"));
      when(invites.completeExistingAccountSetup(
              anyLong(), any(), any(), any(), any(), any(), any(), anyString(), any()))
          .thenAnswer(
              x -> {
                int changed =
                    jdbc.update(
                        "update account_invite set status='ACCEPTED',provisioning_status='COMPLETED',initial_password_verifier=null where id=11 and provisioning_status='IN_PROGRESS'");
                if (failSetupCompletion) {
                  failSetupCompletion = false;
                  throw new IllegalStateException("fixture owning completion rollback");
                }
                return changed;
              });
      var setup =
          new ExistingAccountSetupService(
              invites,
              mock(AdminRepository.class),
              localConsultants,
              mock(IdentityProfileLookup.class),
              mock(IdentityRoleLookup.class),
              mock(IdentityPasswordChangeRequirement.class),
              initial,
              updater,
              transactions);
      setup.setSetupPasswordClaims(
          new de.caritas.cob.userservice.api.adapters.persistence.password
              .JdbcExistingSetupPasswordClaims(jdbc, clock));
      ReflectionTestUtils.setField(setup, "clock", clock);
      return setup;
    }

    Optional<de.caritas.cob.userservice.api.model.AccountInvite> setupInvite(
        String field, Object value) {
      assertThat(
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .isActualTransactionActive())
          .isTrue();
      return jdbc
          .query(
              "select * from account_invite where " + field + "=? for update",
              (rs, index) ->
                  de.caritas.cob.userservice.api.model.AccountInvite.builder()
                      .id(rs.getLong("id"))
                      .purpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP)
                      .status(AccountInviteStatus.valueOf(rs.getString("status")))
                      .provisioningStatus(
                          AccountInviteProvisioningStatus.valueOf(
                              rs.getString("provisioning_status")))
                      .provisioningFailureReason(rs.getString("provisioning_failure_reason"))
                      .provisionedUserId(rs.getString("provisioned_user_id"))
                      .tokenHash(rs.getString("token_hash"))
                      .expiresAt(rs.getTimestamp("expires_at").toLocalDateTime())
                      .targetRole(AccountInviteTargetRole.COUNSELLOR)
                      .tenantId(42L)
                      .setupBoundUsername(sqlUsername)
                      .recipientEmail("Original@Example.org")
                      .initialPasswordVerifier(rs.getString("initial_password_verifier"))
                      .build(),
              value)
          .stream()
          .findFirst();
    }

    String requestLink() {
      service.requestPasswordReset(
          "testuser",
          "en",
          ownedKind.endsWith("ADMIN")
              ? de.caritas.cob.userservice.api.adapters.web.dto.PasswordResetApplication.ADMIN
              : de.caritas.cob.userservice.api.adapters.web.dto.PasswordResetApplication.APP);
      assertThat(resetUrl).isNotNull();
      return resetUrl.substring(resetUrl.indexOf("token=") + 6);
    }

    boolean required() {
      return jdbc.queryForObject(
          "select password_change_required from consultant where consultant_id=?",
          Boolean.class,
          ACCOUNT);
    }

    String phase() {
      return jdbc.queryForObject(
          "select status from identity_claimed_password_intent", String.class);
    }

    String envelope() {
      return jdbc.queryForObject(
          "select intent_envelope from identity_claimed_password_intent", String.class);
    }

    String projection() {
      var value = codec.read("{}", com.fasterxml.jackson.databind.node.ObjectNode.class);
      value
          .put("id", ACCOUNT)
          .put("username", foreignUsername ? "enc.FOREIGN" : nativeUsername)
          .put("email", "Original@Example.org")
          .put("firstName", "Fixture")
          .put("lastName", "Consultant")
          .put("enabled", true)
          .put("emailVerified", true)
          .put("tenantId", Long.toString(ownedTenant))
          .put("preferredLanguage", "de")
          .put("passwordChangeRequired", true)
          .put("state", "a".repeat(64));
      var roles = value.putArray("effectiveRoles");
      if ("USER".equals(ownedKind)) roles.add("user");
      else if ("AGENCY_ADMIN".equals(ownedKind)) {
        roles.add("restricted-agency-admin").add("user-admin");
        if (ownsConsultant) roles.add("consultant");
      } else
        roles.add(
            "consultant"); // Protected receiver-double roles are data; native separately proves its
      // exact installed profile.
      return codec.canonical(value);
    }

    String signedReply(JsonNode body, boolean noEffect) throws Exception {
      var receipt = new TreeMap<String, Object>();
      receipt.put("realmId", "fixture-realm");
      receipt.put("accountId", ACCOUNT);
      receipt.put("operationId", body.path("operationId").textValue());
      receipt.put("command", "password-reset");
      receipt.put("ownerClient", actor.clientId());
      receipt.put("ownerSubject", SUBJECT);
      receipt.put("intentDigest", codec.digest("maintenance-intent", body));
      receipt.put("previousState", body.path("expectedState").textValue());
      receipt.put("tenantId", Long.toString(ownedTenant));
      receipt.put(
          "originDigest", codec.digest("maintenance-origin", body.path("target").path("origin")));
      String result = noEffect ? "NO_EFFECT" : "UPDATED";
      receipt.put("result", result);
      if (noEffect) receipt.put("status", policyStatus);
      String encoded =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(codec.canonical(receipt).getBytes(StandardCharsets.UTF_8));
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
      String signed =
          encoded
              + "."
              + HexFormat.of()
                  .formatHex(
                      mac.doFinal(
                          ((noEffect
                                      ? "maintenance-no-effect-receipt/v1\n"
                                      : "maintenance-operation-receipt\n")
                                  + encoded)
                              .getBytes(StandardCharsets.UTF_8)));
      var outer = new TreeMap<String, Object>();
      outer.put("operationId", body.path("operationId").textValue());
      outer.put("accountId", ACCOUNT);
      outer.put("result", result);
      outer.put("receipt", signed);
      if (noEffect) outer.put("status", policyStatus);
      return codec.canonical(outer);
    }

    @Override
    public void close() {
      service.shutdownPasswordResetExecutor();
      server.stop(0);
      redis.delete(redis.keys(prefix + "*"));
      connection.destroy();
    }
  }
}
