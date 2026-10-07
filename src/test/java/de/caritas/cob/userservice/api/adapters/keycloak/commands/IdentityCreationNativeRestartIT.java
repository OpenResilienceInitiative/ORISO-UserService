package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.*;

/** Explicit opt-in: real native grants, proof verifier, recovery transaction and reopened US DB. */
class IdentityCreationNativeRestartIT {
  @TempDir Path directory;
  private JsonNode fixture;
  private KeycloakTaskCommands commands;

  @BeforeEach
  void configureActualNativeTransport() throws Exception {
    String path = System.getenv("ORISO_CREATION_NATIVE_FIXTURE");
    assertThat(path)
        .as("Native recovery fixture is required; this suite never substitutes mocks or skips")
        .isNotBlank();
    fixture = new ObjectMapper().readTree(Files.readString(Path.of(path)));
    var http = new RestTemplate(new JdkClientHttpRequestFactory());
    String issuer = fixture.path("issuer").asText();
    var credentials = fixture.path("provisioning");
    var identities = new TaskIdentityConfiguration();
    identities
        .getTasks()
        .put(
            "account-provisioning",
            new TaskIdentityCredentials(
                credentials.path("clientId").asText(),
                credentials.path("clientSecret").asText(),
                credentials.path("serviceSubject").asText()));
    var routing =
        mock(
            IdentityClientConfig
                .class); // configuration only; HTTP/grant/signature/provider are real
    when(routing.getOpenIdConnectUrl("/token"))
        .thenReturn(issuer + "/protocol/openid-connect/token");
    var authentication = new KeycloakAuthClient(http, mock(AuthenticatedUser.class), routing);
    var decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs").build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
    var grants =
        new TaskIdentityGrant(identities, authentication, new TaskIdentityTokenVerifier(decoder));
    var proof =
        new IdentityOriginProof(
            fixture.path("provisioningOriginKey").asText(),
            fixture.path("maintenanceOriginKey").asText(),
            Clock.systemUTC());
    int split = issuer.lastIndexOf("/realms/");
    commands =
        new KeycloakTaskCommands(
            http,
            identities,
            grants,
            proof,
            issuer.substring(0, split),
            issuer.substring(split + 8));
  }

  @Test
  void unknownNativeCreateResponseIsRecoveredAfterFileDatabaseRestart() throws Exception {
    var source = source();
    var attempt = UUID.randomUUID();
    var origin = IdentityCreationJournalRestartTest.origin();
    var start = Instant.now();
    String account;
    try (var context =
        IdentityCreationJournalRestartTest.open(source, true, Clock.fixed(start, ZoneOffset.UTC))) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      journal.begin(attempt, origin, "unknown-response");
      account =
          commands
              .create(attempt, command(), origin.command("account.create", attempt))
              .accountId();
      // Actual provider committed, response never captured in US journal.
    }
    recoverAndFinish(source, start.plusSeconds(61), attempt);
    assertNativeAbsent(account);
  }

  @Test
  void absentRecoveryTombstoneRejectsALateNativeCreate() throws Exception {
    var source = source();
    var attempt = UUID.randomUUID();
    var origin = IdentityCreationJournalRestartTest.origin();
    var start = Instant.now();
    try (var context =
        IdentityCreationJournalRestartTest.open(source, true, Clock.fixed(start, ZoneOffset.UTC))) {
      context.getBean(IdentityCreationJournalWriter.class).begin(attempt, origin, "late-response");
    }
    recoverAndFinish(source, start.plusSeconds(61), attempt);
    assertThatThrownBy(
            () -> commands.create(attempt, command(), origin.command("account.create", attempt)))
        .isInstanceOf(HttpClientErrorException.Conflict.class);
  }

  @Test
  void abruptProcessDeathRollsBackLocalWritesAndOwnNativeIdentityIsAutomaticallyRemoved()
      throws Exception {
    var source = source();
    var attempt = UUID.randomUUID();
    var origin = IdentityCreationJournalRestartTest.origin();
    var start = Instant.now();
    String account;
    try (var context =
        IdentityCreationJournalRestartTest.open(source, true, Clock.fixed(start, ZoneOffset.UTC))) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var execution = journal.begin(attempt, origin, "hard-crash");
      var created = commands.create(attempt, command(), origin.command("account.create", attempt));
      account = created.accountId();
      journal.created(created, origin, execution);
      new JdbcTemplate(source)
          .execute("CREATE TABLE local_completion(attempt_id VARCHAR(36) PRIMARY KEY)");
    }
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                CrashBeforeIntent.class.getName(),
                ((JdbcDataSource) source).getURL(),
                attempt.toString())
            .redirectErrorStream(true)
            .redirectOutput(directory.resolve("child.log").toFile())
            .start();
    assertThat(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).isEqualTo(23);
    assertThat(
            new JdbcTemplate(source)
                .queryForObject("SELECT COUNT(*) FROM local_completion", Integer.class))
        .isZero();
    recoverAndFinish(source, start.plusSeconds(61), attempt);
    assertNativeAbsent(account);
  }

  private void recoverAndFinish(DataSource source, Instant expired, UUID attempt) throws Exception {
    try (var context =
        IdentityCreationJournalRestartTest.open(
            source, false, Clock.fixed(expired, ZoneOffset.UTC))) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var service = new IdentityAccountProvisioning(commands, journal);
      var candidate =
          journal.reconciliationRequired().stream()
              .filter(r -> r.getId().equals(attempt.toString()))
              .findFirst()
              .orElseThrow();
      service.recover(candidate);
      var recovered = journal.attempt(attempt);
      if (!"COMPENSATED".equals(recovered.getStatus())) {
        assertThat(recovered.getStatus()).isEqualTo("LOCAL_CLEANUP_REQUESTED");
        // No committed local rows survive the killed creation transaction.
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
            .executeWithoutResult(status -> journal.cleanedLocally(attempt));
        var receipt =
            new KeycloakTaskCommands.CreationResult(
                attempt, recovered.getAccountId(), recovered.getCreationProof(), "OPEN");
        service.compensate(
            receipt, IdentityCreationOrigin.pendingFinalization(journal.attempt(attempt)));
      }
      assertThat(journal.attempt(attempt).getStatus()).isEqualTo("COMPENSATED");
    }
    try (var context = IdentityCreationJournalRestartTest.open(source, false)) {
      assertThat(context.getBean(IdentityCreationJournalWriter.class).attempt(attempt).getStatus())
          .isEqualTo("COMPENSATED");
    }
  }

  private void assertNativeAbsent(String account) {
    assertThatThrownBy(
            () ->
                commands.provisioningRead(
                    account,
                    new IdentityCommandAuthorization(
                        "INVITATION", "account.read", account, "42", List.of())))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
  }

  private KeycloakTaskCommands.AccountCreation command() {
    String username = "creation-restart-" + UUID.randomUUID();
    return new KeycloakTaskCommands.AccountCreation(
        username,
        username + "@example.invalid",
        "Test",
        "Fixture",
        null,
        42L,
        "Local-test-only-Password!123",
        false,
        List.of("consultant"),
        "CONSULTANT");
  }

  private DataSource source() {
    var source = new JdbcDataSource();
    source.setURL(
        "jdbc:h2:file:" + directory.resolve("journal") + ";MODE=MariaDB;DB_CLOSE_ON_EXIT=FALSE");
    source.setUser("sa");
    return source;
  }

  public static final class CrashBeforeIntent {
    public static void main(String[] args) throws Exception {
      try (var connection = java.sql.DriverManager.getConnection(args[0], "sa", "")) {
        connection.setAutoCommit(false);
        try (var lock =
            connection.prepareStatement(
                "SELECT id FROM identity_creation_attempt WHERE id=? FOR UPDATE")) {
          lock.setString(1, args[1]);
          lock.executeQuery().close();
        }
        try (var insert =
            connection.prepareStatement("INSERT INTO local_completion(attempt_id) VALUES (?)")) {
          insert.setString(1, args[1]);
          insert.executeUpdate();
        }
        Runtime.getRuntime()
            .halt(23); // Abrupt process death, no shutdown hooks / normal transaction cleanup.
      }
    }
  }
}
