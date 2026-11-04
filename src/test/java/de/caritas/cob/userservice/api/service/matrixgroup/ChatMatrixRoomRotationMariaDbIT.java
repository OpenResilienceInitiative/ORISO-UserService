package de.caritas.cob.userservice.api.service.matrixgroup;

import static org.assertj.core.api.Assertions.assertThat;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Stopping a recurring group runs inside one transaction, while the next room is reserved and
 * committed in a transaction of its own. MariaDB's REPEATABLE READ hides that commit from the outer
 * transaction, which is why this needs the real database: H2 reads committed and never fails.
 */
@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ChatMatrixRoomRotationMariaDbIT {
  private static final long TENANT = 1L;
  private static final String STORED_ROOM = "!synthetic-stored:synthetic.oriso.test";
  private static final String NEXT_ROOM = "!synthetic-next:synthetic.oriso.test";
  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>("mariadb:10.11").withDatabaseName("userservice");

  static {
    DATABASE.start();
  }

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add(
        "spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MariaDBDialect");
    registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    registry.add("spring.sql.init.mode", () -> "never");
  }

  @Autowired private ChatMatrixRoomHistory history;
  @Autowired private ChatRepository chats;
  @Autowired private ConsultantRepository consultants;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactions;

  @BeforeEach
  void inTenant() {
    TenantContext.setCurrentTenant(TENANT);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void aRotationReservedInItsOwnTransactionCanBeRecordedInTheOuterTransaction() {
    var chat = recurringChat();

    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              // The first consistent read fixes the snapshot of the outer transaction.
              jdbc.queryForObject("select count(*) from chat_matrix_room", Long.class);
              assertThat(history.reserveNextRoom(chat, NEXT_ROOM)).isEqualTo(NEXT_ROOM);
              history.recordRotation(chat, NEXT_ROOM);
            });

    assertThat(
            jdbc.queryForObject(
                "select count(*) from chat_matrix_room where series_id = ?",
                Long.class,
                chat.getId()))
        .isEqualTo(2);
  }

  private Chat recurringChat() {
    var start = LocalDateTime.of(2999, 1, 1, 12, 0);
    return chats.save(
        Chat.builder()
            .topic("Synthetic recurring group")
            .createDate(LocalDateTime.now())
            .updateDate(LocalDateTime.now())
            .consultingTypeId(1)
            .chatOwner(owner())
            .initialStartDate(start)
            .startDate(start)
            .duration(60)
            .repetitive(true)
            .repeatCount(3)
            .chatInterval(Chat.ChatInterval.WEEKLY)
            .conversationType(ConversationType.SELF_HELP)
            .matrixRoomId(STORED_ROOM)
            .active(true)
            .build());
  }

  private Consultant owner() {
    var id = UUID.randomUUID().toString();
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(TENANT);
    consultant.setCreateDate(LocalDateTime.now());
    consultant.setUpdateDate(LocalDateTime.now());
    consultant.setUsername("synthetic-" + id.substring(0, 8));
    consultant.setFirstName("Synthetic");
    consultant.setLastName("C" + id.substring(0, 8));
    consultant.setEmail(id.substring(0, 8) + "@synthetic.oriso.test");
    consultant.setAppointments(null);
    consultant.setConsultantAgencies(new HashSet<>());
    consultant.setConsultantMobileTokens(new HashSet<>());
    consultant.setEncourage2fa(true);
    consultant.setNotifyEnquiriesRepeating(true);
    consultant.setNotifyNewChatMessageFromAdviceSeeker(true);
    consultant.setWalkThroughEnabled(true);
    consultant.setTeamConsultant(false);
    consultant.setMagicLinkLoginEnabled(false);
    consultant.setLanguageCode(LanguageCode.de);
    consultant.setMatrixUserId("@synthetic-" + id.substring(0, 8) + ":synthetic.oriso.test");
    return consultants.save(consultant);
  }
}
