package de.caritas.cob.userservice.api.service.handshake;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus.EXPIRED;
import static de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.port.out.HandshakeAuditEventRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeSessionRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionParticipantWriter;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Import({
  HandshakeService.class,
  ScheduledTaskClaimService.class,
  ScheduledTaskClaimWriter.class,
  TeamDiscussionParticipantWriter.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HandshakeExpiryMariaDbIT {

  private static final String TASK_NAME = "handshake-expiry-sweep";
  private static final String AUDIT_PURGE_TASK_NAME = "handshake-audit-retention";

  @Autowired private HandshakeService handshakes;
  @Autowired private HandshakeSessionRepository sessions;
  @Autowired private HandshakeAuditEventRepository auditEvents;
  @Autowired private ScheduledTaskClaimService claims;
  @Autowired private JdbcTemplate jdbc;

  @MockitoBean private KeycloakAuthClient keycloak;
  @MockitoBean private IdentityClient identity;
  @MockitoBean private IdentityClientConfig identityConfig;
  @MockitoBean private IdentitySecondFactor secondFactor;
  @MockitoBean private TaskScheduler taskScheduler;

  @TestBean(methodName = "utcClock")
  private Clock schedulerClock;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  @Test
  void overlappingReplicaSweepsLeaveOneExpiryAuditEvent() throws Exception {
    var id = UUID.randomUUID().toString();
    var now = nowInUtc();
    sessions.save(
        HandshakeSession.builder()
            .id(id)
            .purpose(HandshakePurpose.SUPPORT_ACCESS)
            .initiatorId("expiry-test-initiator")
            .counterpartId("expiry-test-counterpart")
            .status(PENDING)
            .createDate(now.minusMinutes(10))
            .expiryDate(now.minusMinutes(5))
            .tenantId(1L)
            .build());

    try {
      var otherReplica = claims.tryClaimLease(TASK_NAME, Duration.ofMinutes(2)).orElseThrow();
      try {
        handshakes.sweepExpired();
        assertThat(sessions.findById(id).orElseThrow().getStatus()).isEqualTo(PENDING);
        assertThat(expiryEvents(id)).isZero();
      } finally {
        claims.release(otherReplica);
      }

      runTwoSweeps();
      assertThat(sessions.findById(id).orElseThrow().getStatus()).isEqualTo(EXPIRED);
      assertThat(expiryEvents(id)).isOne();
    } finally {
      jdbc.update("DELETE FROM handshake_audit_event WHERE handshake_id = ?", id);
      sessions.deleteById(id);
    }
  }

  private int expiryEvents(String id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM handshake_audit_event WHERE handshake_id = ? AND event = ?",
        Integer.class,
        id,
        "SESSION_NOT_ESTABLISHED");
  }

  @Test
  void anotherReplicaHoldingTheAuditRetentionClaimLeavesTheRowsForThatReplica() {
    var id = UUID.randomUUID().toString();
    recordAudit(id, HandshakePurpose.SUPPORT_ACCESS, 1L, nowInUtc().minusMonths(13));
    try {
      var otherReplica =
          claims.tryClaimLease(AUDIT_PURGE_TASK_NAME, Duration.ofMinutes(2)).orElseThrow();
      try {
        handshakes.purgeOldAuditEvents();
        assertThat(auditRows(id)).isOne();
      } finally {
        claims.release(otherReplica);
      }

      handshakes.purgeOldAuditEvents();
      assertThat(auditRows(id)).isZero();
    } finally {
      jdbc.update("DELETE FROM handshake_audit_event WHERE handshake_id = ?", id);
      jdbc.update("DELETE FROM scheduled_task_claim WHERE task_name = ?", AUDIT_PURGE_TASK_NAME);
    }
  }

  @Test
  void auditBatchesCommitIndependentlyAndKeepRowsAtOrAfterTheCutoff() {
    var id = UUID.randomUUID().toString();
    var cutoff = LocalDateTime.of(2025, 1, 5, 3, 30);
    var older = cutoff.minusSeconds(1);
    recordAudit(id, HandshakePurpose.SUPPORT_ACCESS, 1L, older);
    recordAudit(id, HandshakePurpose.RECOVERY_SUPPORT_ADMIN, 0L, older);
    recordAudit(id, HandshakePurpose.RECOVERY_CONSULTANT, 7L, older);
    recordAudit(id, HandshakePurpose.IDENTITY_GRANT, null, older);
    recordAudit(id, HandshakePurpose.SUPPORT_ACCESS, null, older);
    recordAudit(id, HandshakePurpose.IDENTITY_GRANT, 1L, cutoff);
    try {
      assertThat(auditEvents.deleteBatchCreatedBefore(cutoff, 2)).isEqualTo(2);
      assertThat(auditRows(id)).isEqualTo(4);
      recordAudit(id, HandshakePurpose.SUPPORT_ACCESS, 1L, cutoff.plusSeconds(1));

      assertThat(auditEvents.deleteBatchCreatedBefore(cutoff, 2)).isEqualTo(2);
      assertThat(auditEvents.deleteBatchCreatedBefore(cutoff, 2)).isOne();
      assertThat(auditEvents.deleteBatchCreatedBefore(cutoff, 2)).isZero();
      assertThat(
              jdbc.queryForList(
                  "SELECT create_date FROM handshake_audit_event WHERE handshake_id = ? ORDER BY create_date",
                  java.sql.Timestamp.class,
                  id))
          .extracting(java.sql.Timestamp::toLocalDateTime)
          .containsExactly(cutoff, cutoff.plusSeconds(1));
    } finally {
      jdbc.update("DELETE FROM handshake_audit_event WHERE handshake_id = ?", id);
    }
  }

  private void recordAudit(
      String id, HandshakePurpose purpose, Long tenant, LocalDateTime createdAt) {
    jdbc.update(
        """
        INSERT INTO handshake_audit_event (handshake_id, purpose, event, tenant_id, create_date)
        VALUES (?, ?, 'INITIATED', ?, ?)
        """,
        id,
        purpose.name(),
        tenant,
        createdAt);
  }

  private int auditRows(String id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM handshake_audit_event WHERE handshake_id = ?", Integer.class, id);
  }

  private void runTwoSweeps() throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> sweepWhenReady(ready, start));
      var second = executor.submit(() -> sweepWhenReady(ready, start));
      try {
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        start.countDown();
      }
      first.get(15, TimeUnit.SECONDS);
      second.get(15, TimeUnit.SECONDS);
    }
  }

  private Void sweepWhenReady(CountDownLatch ready, CountDownLatch start) throws Exception {
    ready.countDown();
    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
    handshakes.sweepExpired();
    return null;
  }

  static Clock utcClock() {
    return Clock.systemUTC();
  }
}
