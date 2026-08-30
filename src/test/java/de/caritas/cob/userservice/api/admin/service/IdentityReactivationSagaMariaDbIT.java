package de.caritas.cob.userservice.api.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.IdentityReactivator;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Proves the reactivation generation claim with PreDev's real MariaDB transaction semantics. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Import({
  IdentityReactivationSagaStore.class,
  IdentityReactivationRepairWriter.class,
  UsernameTranscoder.class,
  DeletionLifecycleService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IdentityReactivationSagaMariaDbIT {

  private static final String USER_ID = "reactivation-saga-proof";
  private static final String USERNAME = "marge.saga@dreambau.de";

  @Autowired private UserRepository userRepository;
  @Autowired private IdentityReactivationSagaStore sagaStore;
  @Autowired private IdentityReactivationRepairWriter repairWriter;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoBean private IdentityReactivator identityReactivator;
  @MockitoBean private IdentityDeactivator identityDeactivator;

  private TransactionTemplate transactionTemplate;

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
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  @BeforeEach
  void setUp() {
    transactionTemplate = new TransactionTemplate(transactionManager);
    deleteProofUser();
    transactionTemplate.executeWithoutResult(ignored -> userRepository.save(deletedUser()));
  }

  @AfterEach
  void tearDown() {
    deleteProofUser();
  }

  @Test
  void crashWindowLeavesCommittedClaimVisibleBeforeKeycloakStarts() {
    IdentityReactivationOperation operation = sagaStore.begin(USERNAME, USERNAME, 40L);

    User persisted = userRepository.findById(USER_ID).orElseThrow();
    assertThat(persisted.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.REACTIVATION_IN_PROGRESS);
    assertThat(persisted.getReactivationOperationId()).isEqualTo(operation.operationId());
    assertThat(persisted.getReactivationOperationStartedAt()).isNotNull();
  }

  @Test
  void successfulFinalizeClearsOnlyItsGenerationInSameCommit() {
    IdentityReactivationOperation operation = sagaStore.begin(USERNAME, USERNAME, 40L);

    sagaStore.reactivateAndCommit(operation, "NewPassw0rd!");

    User persisted = userRepository.findById(USER_ID).orElseThrow();
    assertThat(persisted.getDeleteDate()).isNull();
    assertThat(persisted.getDeletionLifecycleState()).isEqualTo(DeletionLifecycleState.ACTIVE);
    assertThat(persisted.getReactivationOperationId()).isNull();
  }

  @Test
  void lateCompensationCannotDisableASecondSuccessfulGeneration() {
    IdentityReactivationOperation first = sagaStore.begin(USERNAME, USERNAME, 40L);
    assertThat(repairWriter.retry(first.userId(), first.operationId())).isTrue();
    IdentityReactivationOperation second = sagaStore.begin(USERNAME, USERNAME, 40L);

    assertThat(repairWriter.compensate(first, new IllegalStateException("late callback")))
        .isFalse();

    verify(identityDeactivator, times(1)).deactivateUser(USER_ID);
    User persisted = userRepository.findById(USER_ID).orElseThrow();
    assertThat(persisted.getReactivationOperationId()).isEqualTo(second.operationId());
    assertThat(persisted.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.REACTIVATION_IN_PROGRESS);
  }

  private User deletedUser() {
    var user = new User(USER_ID, null, USERNAME, USERNAME, true);
    var now = LocalDateTime.now();
    user.setCreateDate(now);
    user.setUpdateDate(now);
    user.setTenantId(40L);
    user.setDeleteDate(now.minusDays(3));
    user.setDeletionReadOnlyUntil(now.minusMinutes(1));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    return user;
  }

  private void deleteProofUser() {
    transactionTemplate.executeWithoutResult(
        ignored -> userRepository.findById(USER_ID).ifPresent(userRepository::delete));
  }
}
