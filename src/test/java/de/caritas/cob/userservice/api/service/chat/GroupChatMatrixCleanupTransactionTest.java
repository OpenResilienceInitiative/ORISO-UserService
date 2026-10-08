package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual additive Liquibase DDL, JPA writer and Spring REQUIRES_NEW propagation on isolated H2. */
class GroupChatMatrixCleanupTransactionTest {
  @Test
  @Timeout(15)
  void committedIntentSurvivesOuterRollbackAndWorkerWaitsForWriterMutex() throws Exception {
    var data =
        new DriverManagerDataSource(
            "jdbc:h2:mem:group_cleanup_"
                + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
            "sa",
            "");
    try (var connection = data.getConnection()) {
      var db =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try (var migration =
          new Liquibase(
              "db/changelog/changeset/20261008_group_matrix_cleanup/changeSet.xml",
              new ClassLoaderResourceAccessor(),
              db)) {
        migration.update(new liquibase.Contexts());
      }
    }
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.datasource", data)
            .applySetting("hibernate.hbm2ddl.auto", "validate")
            .build();
    try (var factory =
        new MetadataSources(registry)
            .addAnnotatedClass(GroupChatMatrixCleanupTask.class)
            .buildMetadata()
            .buildSessionFactory()) {
      var manager = new JpaTransactionManager(factory);
      manager.setDataSource(data);
      manager.setJpaDialect(new HibernateJpaDialect());
      manager.afterPropertiesSet();
      var repository =
          new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory))
              .getRepository(GroupChatMatrixCleanupTaskRepository.class);
      var consultants = mock(ConsultantRepository.class);
      var chats = mock(ChatRepository.class);
      var matrix = mock(MatrixSynapseService.class);
      var target =
          new GroupChatMatrixCleanupService(
              repository,
              consultants,
              chats,
              mock(GroupChatParticipantRepository.class),
              matrix,
              mock(GroupChatMembershipService.class),
              Clock.systemUTC());
      var proxy = new ProxyFactory(target);
      proxy.addAdvice(
          new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
      var service = (GroupChatMatrixCleanupService) proxy.getProxy();
      var jdbc = new JdbcTemplate(data);
      jdbc.execute("CREATE TABLE cleanup_owner (id VARCHAR(36) PRIMARY KEY)");
      jdbc.update("INSERT INTO cleanup_owner VALUES ('owner')");
      var owner = new Consultant();
      owner.setId("owner");
      owner.setTenantId(41L);
      var workerAtLock = new CountDownLatch(1);
      when(consultants.findPictureOwnerForUpdate("owner"))
          .thenAnswer(
              i -> {
                workerAtLock.countDown();
                jdbc.queryForObject(
                    "SELECT id FROM cleanup_owner WHERE id='owner' FOR UPDATE", String.class);
                return Optional.of(owner);
              });
      when(matrix.purgeRoomOrConfirmGone("!orphan:matrix"))
          .thenReturn(MatrixSynapseService.RoomPurgeOutcome.PURGED);
      // Independent commit survives outer rollback, including a scheduled intent deletion.
      new TransactionTemplate(manager)
          .executeWithoutResult(
              status -> {
                var id =
                    service.recordRoom(
                        new GroupChatMatrixCleanupService.GroupOwner(7L, "owner", 41L),
                        "!rollback:matrix");
                service.clear(id);
                status.setRollbackOnly();
              });
      assertThat(repository.count()).isEqualTo(1);
      new TransactionTemplate(manager).executeWithoutResult(status -> repository.deleteAll());
      var worker = Executors.newSingleThreadExecutor();
      var future = new java.util.concurrent.atomic.AtomicReference<Future<?>>();
      try {
        new TransactionTemplate(manager)
            .executeWithoutResult(
                status -> {
                  jdbc.queryForObject(
                      "SELECT id FROM cleanup_owner WHERE id='owner' FOR UPDATE", String.class);
                  Long id =
                      service.recordRoom(
                          new GroupChatMatrixCleanupService.GroupOwner(7L, "owner", 41L),
                          "!orphan:matrix"); // Actual independent insert cannot self-block (no FK).
                  service.clear(id); // This deletion must roll back with the failed operation.
                  future.set(worker.submit(() -> service.retry(id)));
                  try {
                    assertThat(workerAtLock.await(3, TimeUnit.SECONDS)).isTrue();
                    assertThrows(
                        TimeoutException.class, () -> future.get().get(150, TimeUnit.MILLISECONDS));
                  } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                  }
                  verifyNoInteractions(matrix);
                  status.setRollbackOnly();
                });
        future.get().get(5, TimeUnit.SECONDS);
        verify(matrix).purgeRoomOrConfirmGone("!orphan:matrix");
        assertThat(repository.count()).isZero();
      } finally {
        worker.shutdownNow();
        assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }
}
