package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.model.GroupRosterRemovalIntent;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.GroupRosterRemovalIntentRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises the roster journal's actual Liquibase schema and Spring transaction propagation. */
class GroupRosterRemovalRecoveryPersistenceIT {

  @Test
  @Timeout(20)
  void departureReadbackSurvivesRollbackAndLaterAcceptanceRetiresOnlyItsMainRoomWork()
      throws Exception {
    var data = migratedDataSource();
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.datasource", data)
            .applySetting("hibernate.hbm2ddl.auto", "validate")
            .build();
    try (var factory =
        new MetadataSources(registry)
            .addAnnotatedClass(GroupChatParticipant.class)
            .addAnnotatedClass(GroupRosterRemovalIntent.class)
            .buildMetadata()
            .buildSessionFactory()) {
      var manager = new JpaTransactionManager(factory);
      manager.setDataSource(data);
      manager.setJpaDialect(new HibernateJpaDialect());
      manager.afterPropertiesSet();
      var repositoryFactory =
          new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory));
      var repository = repositoryFactory.getRepository(GroupRosterRemovalIntentRepository.class);
      var participants = repositoryFactory.getRepository(GroupChatParticipantRepository.class);
      var matrix = mock(MatrixSynapseService.class);
      var target =
          new GroupRosterRemovalRecovery(
              repository,
              mock(ChatRepository.class),
              mock(ConsultantRepository.class),
              participants,
              mock(SessionSupervisorRepository.class),
              mock(MembersOnlyRevocation.class),
              matrix,
              Clock.systemUTC());
      var proxy = new ProxyFactory(target);
      proxy.addAdvice(
          new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
      var service = (GroupRosterRemovalRecovery) proxy.getProxy();
      var transaction = new TransactionTemplate(manager);
      var group = new GroupRosterRemovalRecovery.GroupScope(42L, "owner", 7L, "!group:matrix", 3);
      var otherGroup =
          new GroupRosterRemovalRecovery.GroupScope(45L, "owner", 7L, "!other-group:matrix", 2);
      var legacyParticipant = participants.findById(24L).orElseThrow();
      assertThat(legacyParticipant.getRosterVersion()).isZero();
      var participant = GroupRosterRemovalRecovery.ParticipantScope.of(legacyParticipant);

      var originalIntents =
          transaction.execute(
              status -> {
                var sideReservation =
                    service.record(
                        group, participant, "!supervision:matrix", "@consultant:matrix", 18L, null);
                assertThat(sideReservation.sendLeave()).isTrue();
                service.markDispatchSettled(sideReservation.id());
                var mainReservation =
                    service.record(
                        group, participant, group.roomId(), "@consultant:matrix", null, null);
                assertThat(mainReservation.sendLeave()).isTrue();
                service.markDispatchSettled(mainReservation.id());
                service.clearSideExit(sideReservation.id());
                service.acceptRoster(group.id());
                status.setRollbackOnly();
                return List.of(mainReservation.id(), sideReservation.id());
              });

      assertThat(repository.findAllById(originalIntents))
          .extracting(GroupRosterRemovalIntent::isDispatchSettled)
          .containsExactly(true, true);
      var unrelated =
          service.record(
              otherGroup, participant, otherGroup.roomId(), "@consultant:matrix", null, null);
      var unsettled =
          service.record(
              group,
              new GroupRosterRemovalRecovery.ParticipantScope(28L, 0L, "second"),
              group.roomId(),
              "@second:matrix",
              null,
              null);
      assertThat(repository.findById(unsettled.id()))
          .get()
          .extracting(GroupRosterRemovalIntent::isDispatchSettled)
          .isEqualTo(false);

      transaction.executeWithoutResult(status -> service.acceptRoster(group.id()));

      assertThat(repository.findAll())
          .extracting(GroupRosterRemovalIntent::getId)
          .containsExactlyInAnyOrder(originalIntents.get(1), unrelated.id(), unsettled.id());

      transaction.executeWithoutResult(
          status -> participants.findById(24L).orElseThrow().setRole(ParticipantRole.PARTICIPANT));
      transaction.executeWithoutResult(
          status -> participants.findById(24L).orElseThrow().setRole(ParticipantRole.CO_MODERATOR));
      assertThat(participants.findById(24L).orElseThrow().getRosterVersion()).isEqualTo(2L);
      verifyNoInteractions(matrix);
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }

  private static DriverManagerDataSource migratedDataSource() throws Exception {
    var data =
        new DriverManagerDataSource(
            "jdbc:h2:mem:group_roster_"
                + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
            "sa",
            "");
    var jdbc = new JdbcTemplate(data);
    jdbc.execute(
        """
        CREATE TABLE group_chat_participant (
          id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
          chat_id BIGINT NOT NULL,
          consultant_id VARCHAR(36) NOT NULL,
          series_id BIGINT,
          participant_role VARCHAR(16) NOT NULL
        )
        """);
    jdbc.update(
        "INSERT INTO group_chat_participant VALUES (24, 12, 'consultant', 42, 'CO_MODERATOR')");
    try (var connection = data.getConnection()) {
      var db =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try (var migration =
          new Liquibase(
              "db/changelog/changeset/20261121_group_roster_removal_1740/changeSet.xml",
              new ClassLoaderResourceAccessor(),
              db)) {
        migration.update(new Contexts());
      }
    }
    return data;
  }
}
