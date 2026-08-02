package de.caritas.cob.userservice.api.port.out;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.HandshakeAuditEvent;
import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent;
import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent.OutboxStatus;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus;
import de.caritas.cob.userservice.api.model.SupportAccessSession;
import de.caritas.cob.userservice.api.model.SupportAccessSession.SupportAccessSessionStatus;
import de.caritas.cob.userservice.api.model.SupportAdminProfile;
import de.caritas.cob.userservice.api.model.SupportAdminProfile.SupportAdminStatus;
import de.caritas.cob.userservice.api.service.handshake.HandshakePurpose;
import de.caritas.cob.userservice.api.service.handshake.SupportAccessJobHandler;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The support-access invariants that only a real MariaDB can prove (ADR-018 test criteria).
 *
 * <p>H2 is not a substitute here. Two of these properties depend on engine behaviour rather than on
 * application logic: how a conditional {@code UPDATE} behaves when two transactions race for the
 * same row, and whether a unique index really admits many {@code NULL}s so that closed sessions
 * release their lease. A green H2 run would say nothing about either.
 *
 * <p>The schema is built by Liquibase, so this also proves the migration itself — in particular
 * that the widened {@code admin.type} column stores {@code SUPPORT} while the existing values keep
 * working.
 *
 * <p>Follows the env-var-provided-database pattern of {@link
 * de.caritas.cob.userservice.api.DatabaseChangelogDriftIT} (this project's Testcontainers 1.17.6
 * predates current Docker Desktop API versions and cannot bootstrap containers).
 *
 * <pre>
 *   docker run -d --name support-access-mariadb-it -p 3319:3306 -e MARIADB_ROOT_PASSWORD=root \
 *     -e MARIADB_DATABASE=userservice mariadb:11.0.6
 *   LIQUIBASE_IT_DB_URL=jdbc:mariadb://127.0.0.1:3319/userservice \
 *     ./mvnw integration-test -Dtest=SupportAccessMariaDbIT -DfailIfNoSpecifiedTests=false
 * </pre>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SupportAccessMariaDbIT {

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
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
    // Not "validate": whole-schema drift is DatabaseChangelogDriftIT's job, and an unrelated
    // pre-existing mismatch (reserved_public_slug.active) would otherwise mask every assertion
    // here. The schema is still the real Liquibase-built one, which is what these tests need.
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
  }

  /** No ambient transaction here, so nothing rolls back — fixtures must be unique per run. */
  private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

  @Autowired private HandshakeSessionRepository handshakeRepository;
  @Autowired private HandshakeAuditEventRepository auditRepository;
  @Autowired private HandshakeOutboxEventRepository outboxRepository;
  @Autowired private SupportAccessSessionRepository sessionRepository;
  @Autowired private SupportAdminProfileRepository profileRepository;
  @Autowired private AdminRepository adminRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  // --- migration ---

  @Test
  void adminTypeColumn_Should_StoreSupportAlongsideTheExistingTypes() {
    var support = adminRepository.save(admin(Admin.AdminType.SUPPORT));
    var agency = adminRepository.save(admin(Admin.AdminType.AGENCY));
    var tenant = adminRepository.save(admin(Admin.AdminType.TENANT));

    // The column used to be VARCHAR(6); "SUPPORT" is seven characters, so an unwidened column
    // would either truncate or reject here.
    assertThat(adminRepository.findById(support.getId()).orElseThrow().getType())
        .isEqualTo(Admin.AdminType.SUPPORT);
    assertThat(adminRepository.findById(agency.getId()).orElseThrow().getType())
        .isEqualTo(Admin.AdminType.AGENCY);
    assertThat(adminRepository.findById(tenant.getId()).orElseThrow().getType())
        .isEqualTo(Admin.AdminType.TENANT);
  }

  @Test
  void supportAdminProfile_Should_StoreEveryLifecycleState() {
    for (var status : SupportAdminStatus.values()) {
      var adminId = UUID.randomUUID().toString();
      profileRepository.saveAndFlush(
          SupportAdminProfile.builder()
              .adminId(adminId)
              .status(status)
              .createDate(nowInUtc())
              .updateDate(nowInUtc())
              .build());

      assertThat(profileRepository.findById(adminId).orElseThrow().getStatus()).isEqualTo(status);
    }
  }

  // --- concurrency ---

  @Test
  void twoSimultaneousConfirmations_Should_ProduceExactlyOneWinner() throws Exception {
    var handshakeId = persistPendingHandshake();
    var barrier = new CyclicBarrier(2);

    Callable<Integer> confirm =
        () -> {
          barrier.await(10, TimeUnit.SECONDS);
          return transactionTemplate.execute(
              status -> handshakeRepository.confirmIfStillPending(handshakeId, nowInUtc()));
        };

    var pool = Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(confirm);
      var second = pool.submit(confirm);
      var wins = first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS);

      // Exactly one caller may go on to create a session and a job. This is the property the whole
      // design rests on, and it is decided by the database, not by the service.
      assertThat(wins).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }

    assertThat(handshakeRepository.findById(handshakeId).orElseThrow().getStatus())
        .isEqualTo(HandshakeStatus.CONFIRMED);
  }

  @Test
  void aSecondProvisioningJobForTheSameHandshake_Should_BeRejectedByTheDatabase() {
    var handshakeId = UUID.randomUUID().toString();
    outboxRepository.saveAndFlush(job(handshakeId, SupportAccessJobHandler.PROVISION_ROOM));

    assertThatThrownBy(
            () ->
                outboxRepository.saveAndFlush(
                    job(handshakeId, SupportAccessJobHandler.PROVISION_ROOM)))
        .isInstanceOf(DataIntegrityViolationException.class);

    // A different job type for the same aggregate is legitimate — withdrawal follows provisioning.
    assertThatCode(
            () ->
                outboxRepository.saveAndFlush(
                    job(handshakeId, SupportAccessJobHandler.REVOKE_ACCESS)))
        .doesNotThrowAnyException();
  }

  // --- lease semantics ---

  @Test
  void onlyOneRunningSession_Should_BeAllowedPerSupportAdminConsultantAndAgency() {
    var lease = SupportAccessSession.leaseKeyOf("gsa-lease-" + RUN, "consultant-lease-" + RUN, 11L);
    sessionRepository.saveAndFlush(
        session("hs-lease-1-" + RUN, lease, SupportAccessSessionStatus.ACTIVE));

    assertThatThrownBy(
            () ->
                sessionRepository.saveAndFlush(
                    session("hs-lease-2-" + RUN, lease, SupportAccessSessionStatus.PROVISIONING)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void closedSessions_Should_ReleaseTheLeaseSoTheSamePairCanBeHelpedAgain() {
    var lease = SupportAccessSession.leaseKeyOf("gsa-reuse-" + RUN, "consultant-reuse-" + RUN, 12L);
    var first =
        sessionRepository.saveAndFlush(
            session("hs-reuse-1-" + RUN, lease, SupportAccessSessionStatus.ACTIVE));
    first.setStatus(SupportAccessSessionStatus.CLOSED);
    first.setActiveLeaseKey(null);
    sessionRepository.saveAndFlush(first);

    var second =
        sessionRepository.saveAndFlush(
            session("hs-reuse-2-" + RUN, lease, SupportAccessSessionStatus.PROVISIONING));
    first.setStatus(SupportAccessSessionStatus.CLOSED);

    // Many NULL lease keys must coexist under the unique index, otherwise a consultant could only
    // ever be helped once. MariaDB allows this; asserting it on H2 would prove nothing about prod.
    assertThat(second.getActiveLeaseKey()).isEqualTo(lease);
    assertThat(
            sessionRepository
                .findByHandshakeId("hs-reuse-1-" + RUN)
                .orElseThrow()
                .getActiveLeaseKey())
        .isNull();
  }

  @Test
  void beginRevocation_Should_SucceedOnlyOnceAcrossRacingCallers() {
    var session =
        sessionRepository.saveAndFlush(
            session(
                "hs-revoke-" + RUN,
                SupportAccessSession.leaseKeyOf(
                    "gsa-revoke-" + RUN, "consultant-revoke-" + RUN, 13L),
                SupportAccessSessionStatus.ACTIVE));

    var firstCall =
        transactionTemplate.execute(
            status -> sessionRepository.beginRevocation(session.getId(), "EXPIRED", nowInUtc()));
    var secondCall =
        transactionTemplate.execute(
            status -> sessionRepository.beginRevocation(session.getId(), "TERMINATED", nowInUtc()));

    // Expiry, manual termination and disabling all race for this transition; only one may enqueue
    // the withdrawal job.
    assertThat(firstCall).isEqualTo(1);
    assertThat(secondCall).isZero();
    var reloaded = sessionRepository.findById(session.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(SupportAccessSessionStatus.REVOCATION_PENDING);
    assertThat(reloaded.getCloseReason()).isEqualTo("EXPIRED");
    assertThat(reloaded.getRevocationStartedDate()).isNotNull();
  }

  // --- lapse ---

  @Test
  void aLapsedHandshake_Should_LeaveOneAuditEntryAndNoOperationalRow() {
    var handshakeId = persistPendingHandshake();
    var before = auditRepository.count();

    transactionTemplate.executeWithoutResult(
        status -> {
          var handshake = handshakeRepository.findById(handshakeId).orElseThrow();
          auditRepository.save(
              HandshakeAuditEvent.builder()
                  .handshakeId(handshakeId)
                  .purpose(HandshakePurpose.SUPPORT_ACCESS.name())
                  .event("SESSION_NOT_ESTABLISHED")
                  .counterpartId(handshake.getCounterpartId())
                  .tenantId(handshake.getTenantId())
                  .agencyId(handshake.getAgencyId())
                  .createDate(nowInUtc())
                  .build());
          handshakeRepository.delete(handshake);
        });

    assertThat(handshakeRepository.findById(handshakeId)).isEmpty();
    assertThat(auditRepository.count()).isEqualTo(before + 1);
  }

  // --- fixtures ---

  private String persistPendingHandshake() {
    var id = UUID.randomUUID().toString();
    handshakeRepository.saveAndFlush(
        HandshakeSession.builder()
            .id(id)
            .purpose(HandshakePurpose.SUPPORT_ACCESS)
            .initiatorId("gsa-" + id.substring(0, 8))
            .counterpartId("consultant-" + id.substring(0, 8))
            .agencyId(21L)
            .tenantId(1L)
            .status(HandshakeStatus.PENDING)
            .createDate(nowInUtc())
            .expiryDate(nowInUtc().plusMinutes(5))
            .build());
    return id;
  }

  private HandshakeOutboxEvent job(String aggregateId, String type) {
    return HandshakeOutboxEvent.builder()
        .aggregateId(aggregateId)
        .eventType(type)
        .status(OutboxStatus.PENDING)
        .attempts(0)
        .createDate(nowInUtc())
        .nextAttemptDate(nowInUtc())
        .build();
  }

  private SupportAccessSession session(
      String handshakeId, String leaseKey, SupportAccessSessionStatus status) {
    return SupportAccessSession.builder()
        .id(UUID.randomUUID().toString())
        .handshakeId(handshakeId)
        .supportAdminId(leaseKey.split(":")[0])
        .consultantId(leaseKey.split(":")[1])
        .agencyId(Long.valueOf(leaseKey.split(":")[2]))
        .status(status)
        .activeLeaseKey(leaseKey)
        .createDate(nowInUtc())
        .expiryDate(nowInUtc().plusHours(4))
        .tenantId(1L)
        .build();
  }

  private Admin admin(Admin.AdminType type) {
    var id = UUID.randomUUID().toString();
    return Admin.builder()
        .id(id)
        .type(type)
        .tenantId(0L)
        .username("user-" + id.substring(0, 8))
        .firstName("First")
        .lastName("Last")
        .email(id.substring(0, 8) + "@example.org")
        .createDate(nowInUtc())
        .updateDate(nowInUtc())
        .build();
  }
}
