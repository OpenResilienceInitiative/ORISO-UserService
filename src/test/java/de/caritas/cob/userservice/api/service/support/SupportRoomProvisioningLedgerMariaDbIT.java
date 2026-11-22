package de.caritas.cob.userservice.api.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.port.out.HandshakeSessionRepository;
import de.caritas.cob.userservice.api.port.out.SupportRoomCreationIntentRepository;
import de.caritas.cob.userservice.api.service.handshake.HandshakePurpose;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Import(SupportRoomProvisioningLedger.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SupportRoomProvisioningLedgerMariaDbIT {
  private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
  private static final LocalDateTime DEADLINE =
      LocalDateTime.ofInstant(NOW.plusSeconds(300), ZoneOffset.UTC);

  @Autowired private SupportRoomProvisioningLedger ledger;
  @Autowired private HandshakeSessionRepository handshakes;
  @Autowired private SupportRoomCreationIntentRepository intents;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  @TestBean(methodName = "reservationClock")
  private Clock clock;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    registry.add(
        "spring.datasource.hikari.transaction-isolation", () -> "TRANSACTION_REPEATABLE_READ");
    registry.add(
        "spring.datasource.hikari.connection-init-sql",
        () -> "SET SESSION innodb_lock_wait_timeout = 5");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  @Test
  void concurrentReservationsForDistinctHandshakesBothCommit() throws Exception {
    assertThat(jdbc.queryForObject("SELECT @@tx_isolation", String.class))
        .isEqualTo("REPEATABLE-READ");
    // The common prefix keeps both missing keys in the same primary-key gap.
    var prefix = "reserve-" + UUID.randomUUID().toString().substring(0, 23);
    var firstId = prefix + "-1";
    var secondId = prefix + "-2";
    try {
      createPendingHandshake(firstId, "1");
      createPendingHandshake(secondId, "2");
      try (var workers = Executors.newFixedThreadPool(2)) {
        var first = workers.submit(() -> reserve(firstId));
        var second = workers.submit(() -> reserve(secondId));

        assertThat(first.get(15, TimeUnit.SECONDS).firstAttempt()).isTrue();
        assertThat(second.get(15, TimeUnit.SECONDS).firstAttempt()).isTrue();
      }
      var firstIntent = intents.findById(firstId).orElseThrow();
      var secondIntent = intents.findById(secondId).orElseThrow();
      assertThat(firstIntent.getId()).isNotEqualTo(secondIntent.getId());
      assertThat(firstIntent.getConsultantId()).isEqualTo("synthetic-consultant-1");
      assertThat(secondIntent.getConsultantId()).isEqualTo("synthetic-consultant-2");
    } finally {
      intents.deleteById(firstId);
      intents.deleteById(secondId);
      handshakes.deleteById(firstId);
      handshakes.deleteById(secondId);
    }
  }

  private SupportRoomProvisioningLedger.Reservation reserve(String handshakeId) {
    return new TransactionTemplate(transactionManager)
        .execute(
            transaction -> {
              var session = handshakes.findByIdForUpdate(handshakeId).orElseThrow();
              var admin = session.getInitiatorId();
              var consultant = session.getCounterpartId();
              return ledger.reserve(
                  session.getId(),
                  admin,
                  "@" + admin + ":matrix.invalid",
                  consultant,
                  "@" + consultant + ":matrix.invalid",
                  session.getTenantId(),
                  session.getExpiryDate());
            });
  }

  private void createPendingHandshake(String handshakeId, String suffix) {
    handshakes.save(
        HandshakeSession.builder()
            .id(handshakeId)
            .purpose(HandshakePurpose.SUPPORT_ACCESS)
            .initiatorId("synthetic-support-" + suffix)
            .counterpartId("synthetic-consultant-" + suffix)
            .status(HandshakeSession.HandshakeStatus.PENDING)
            .createDate(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))
            .expiryDate(DEADLINE)
            .tenantId(1L)
            .build());
  }

  static Clock reservationClock() {
    var bothReadMissingIntents = new CountDownLatch(2);
    return new Clock() {
      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return Clock.fixed(NOW, zone);
      }

      @Override
      public Instant instant() {
        // reserve reads the clock immediately after locking the missing intent and before
        // inserting.
        bothReadMissingIntents.countDown();
        try {
          assertThat(bothReadMissingIntents.await(8, TimeUnit.SECONDS))
              .as("both reservation transactions read the missing intent")
              .isTrue();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
        return NOW;
      }
    };
  }
}
