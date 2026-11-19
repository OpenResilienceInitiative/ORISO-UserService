package de.caritas.cob.userservice.api.service.session;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgencyStatus;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRevocationTaskRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService.InitialRoomContact;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@Import({AgencyEnquiryRoomPublication.class, AgencyEnquiryRevocations.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgencyEnquiryRoomPublicationDatabaseIT {

  private static final long TENANT_ID = 47L;
  private static final long AGENCY_ID = 471179L;
  private static final String ROOM = "!new:example.invalid";
  private static final String MEMBER_ID = "@member:example.invalid";

  @Autowired private AgencyEnquiryRoomPublication publication;
  @Autowired private AgencyEnquiryRevocations revocations;
  @Autowired private AgencyEnquiryRevocationTaskRepository tasks;
  @Autowired private ConsultantRepository consultants;
  @Autowired private ConsultantAgencyRepository assignments;
  @Autowired private SessionRepository sessions;
  @Autowired private UserRepository users;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  @MockitoBean private Clock clock;
  @MockitoBean private SessionService sessionService;
  @MockitoBean private AgencyEnquiryRoomRetirements retiredRooms;
  @MockitoBean private AgencyMatrixCredentialClient agencyCredentials;
  @MockitoBean private SessionRoomGateway matrix;

  private Session enquiry;
  private String consultantId;
  private String userId;
  private Long relationId;

  @DynamicPropertySource
  static void configureMariaDb(DynamicPropertyRegistry registry) {
    var url = System.getenv("LIQUIBASE_IT_DB_URL");
    if (url == null || url.isBlank()) {
      return;
    }
    registry.add("spring.datasource.url", () -> url);
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
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

  @BeforeEach
  void seedUnboundEnquiryAndCurrentMember() {
    TenantContext.setCurrentTenant(TENANT_ID);
    when(clock.instant()).thenReturn(Instant.parse("2026-11-19T12:00:00Z"));
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              var now = LocalDateTime.now(clock);
              consultantId = UUID.randomUUID().toString();
              userId = UUID.randomUUID().toString();
              var consultant =
                  consultants.save(
                      Consultant.builder()
                          .id(consultantId)
                          .username(consultantId)
                          .firstName("Test")
                          .lastName("Counsellor")
                          .email(consultantId + "@example.invalid")
                          .matrixUserId(MEMBER_ID)
                          .tenantId(TENANT_ID)
                          .languageCode(LanguageCode.de)
                          .encourage2fa(false)
                          .magicLinkLoginEnabled(false)
                          .notifyEnquiriesRepeating(false)
                          .notifyNewChatMessageFromAdviceSeeker(false)
                          .createDate(now)
                          .updateDate(now)
                          .build());
              relationId =
                  assignments
                      .save(
                          ConsultantAgency.builder()
                              .consultant(consultant)
                              .status(ConsultantAgencyStatus.CREATED)
                              .agencyId(AGENCY_ID)
                              .tenantId(TENANT_ID)
                              .createDate(now)
                              .updateDate(now)
                              .build())
                      .getId();
              var user =
                  users.save(
                      User.builder()
                          .userId(userId)
                          .username(userId)
                          .email(userId + "@example.invalid")
                          .tenantId(TENANT_ID)
                          .languageCode(LanguageCode.de)
                          .encourage2fa(false)
                          .magicLinkLoginEnabled(false)
                          .createDate(now)
                          .updateDate(now)
                          .build());
              enquiry =
                  sessions.save(
                      Session.builder()
                          .user(user)
                          .agencyId(AGENCY_ID)
                          .tenantId(TENANT_ID)
                          .status(SessionStatus.INITIAL)
                          .isConsultantDirectlySet(false)
                          .postcode("12345")
                          .languageCode(LanguageCode.de)
                          .registrationType(Session.RegistrationType.REGISTERED)
                          .createDate(now)
                          .updateDate(now)
                          .build());
            });
  }

  @AfterEach
  void removeOnlyTheTestData() {
    try {
      if (enquiry != null) {
        pending().forEach(tasks::delete);
        sessions.deleteById(enquiry.getId());
        assignments.deleteById(relationId);
        users.deleteById(userId);
        consultants.deleteById(consultantId);
      }
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void departureFirstIsCorrectedWhenTheRoomIsPublished() {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> detach());
    assertThat(pending()).isEmpty();

    assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM);

    assertThat(sessions.findById(enquiry.getId()).orElseThrow().getMatrixRoomId()).isEqualTo(ROOM);
    assertOneCorrection();
  }

  @Test
  void departureAfterPublicationCapturesTheFirstRoom() {
    assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM);
    assertThat(pending()).isEmpty();

    new TransactionTemplate(transactionManager).executeWithoutResult(status -> detach());

    assertOneCorrection();
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
  void publicationFirstIsVisibleToADepartureWithAnOlderMariaDbSnapshot() {
    var departure = new TransactionTemplate(transactionManager);
    departure.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    departure.executeWithoutResult(
        status -> {
          // Establish an older snapshot before the competing transaction commits its binding.
          assertThat(sessions.findById(enquiry.getId()).orElseThrow().getMatrixRoomId()).isNull();
          var publicationTransaction = new TransactionTemplate(transactionManager);
          publicationTransaction.setPropagationBehavior(
              TransactionDefinition.PROPAGATION_REQUIRES_NEW);
          publicationTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
          publicationTransaction.executeWithoutResult(
              ignored -> assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM));
          detach();
        });

    assertOneCorrection();
    assertAgencyLeadingIndexIsAvailable();
  }

  @Test
  @Timeout(20)
  @EnabledIfEnvironmentVariable(
      named = "LIQUIBASE_IT_DB_URL",
      matches = "jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/userservice")
  void databaseEventOrderSurvivesAnOverlappingDecisionAndRollback() throws Exception {
    var suffix = UUID.randomUUID().toString().replace("-", "");
    var orderSequence = "test_enquiry_order_" + suffix;
    var decisionTable = "test_enquiry_decision_" + suffix;
    jdbc.execute("CREATE SEQUENCE " + orderSequence + " NOCACHE NOCYCLE");
    try {
      jdbc.execute(
          "CREATE TABLE "
              + decisionTable
              + " (id INT PRIMARY KEY, event_order BIGINT,"
              + " event_time DATETIME(6) NOT NULL) ENGINE=InnoDB");
      jdbc.update("INSERT INTO " + decisionTable + " VALUES (1, 0, ?)", LocalDateTime.now(clock));
      var decisionRow = "SELECT event_order, event_time FROM " + decisionTable + " WHERE id = 1";
      var nextEvent = "SELECT NEXT VALUE FOR " + orderSequence;
      var departureAllocated = new CompletableFuture<DatabaseEvent>();

      try (var threads = Executors.newSingleThreadExecutor();
          var accepting = dataSource.getConnection()) {
        accepting.setAutoCommit(false);
        readEvent(accepting, decisionRow + " FOR UPDATE");

        var departing =
            threads.submit(
                () -> {
                  try (var departure = dataSource.getConnection()) {
                    departure.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                    departure.setAutoCommit(false);
                    assertThat(readEvent(departure, decisionRow).order()).isZero();
                    var event =
                        new DatabaseEvent(
                            readOrder(departure, nextEvent), LocalDateTime.now(clock));
                    assertThatThrownBy(
                            () -> readEvent(departure, decisionRow + " LOCK IN SHARE MODE NOWAIT"))
                        .isInstanceOfSatisfying(
                            SQLException.class,
                            failure -> assertThat(failure.getErrorCode()).isEqualTo(1205));
                    departureAllocated.complete(event);
                    var visibleDecision =
                        readEvent(departure, decisionRow + " LOCK IN SHARE MODE WAIT 5");
                    departure.rollback();
                    return visibleDecision;
                  } catch (Exception | AssertionError failure) {
                    departureAllocated.completeExceptionally(failure);
                    throw failure;
                  }
                });

        var departureEvent = departureAllocated.get(6, SECONDS);
        var replacement =
            new DatabaseEvent(readOrder(accepting, nextEvent), LocalDateTime.now(clock));
        try (var update =
            accepting.prepareStatement(
                "UPDATE " + decisionTable + " SET event_order = ?, event_time = ? WHERE id = 1")) {
          update.setLong(1, replacement.order());
          update.setObject(2, replacement.time());
          assertThat(update.executeUpdate()).isEqualTo(1);
        }
        accepting.commit();

        assertThat(departureEvent.time()).isEqualTo(replacement.time());
        assertThat(departureEvent.order()).isLessThan(replacement.order());
        assertThat(departing.get(6, SECONDS)).isEqualTo(replacement);
        long rolledBackOrder = readOrder(accepting, nextEvent);
        accepting.rollback();
        assertThat(jdbc.queryForObject(nextEvent, Long.class)).isGreaterThan(rolledBackOrder);
      }
    } finally {
      jdbc.execute("DROP TABLE IF EXISTS " + decisionTable);
      jdbc.execute("DROP SEQUENCE IF EXISTS " + orderSequence);
    }
  }

  private record DatabaseEvent(long order, LocalDateTime time) {}

  private static DatabaseEvent readEvent(Connection connection, String query) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(6);
      try (var row = statement.executeQuery(query)) {
        assertThat(row.next()).isTrue();
        return new DatabaseEvent(row.getLong(1), row.getObject(2, LocalDateTime.class));
      }
    }
  }

  private static long readOrder(Connection connection, String query) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(6);
      try (var row = statement.executeQuery(query)) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  @Test
  void aValidReassignmentBeforePublicationIsKept() {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(status -> detach());
    transaction.executeWithoutResult(
        status -> {
          consultants.lockConsultant(consultantId);
          var relation = assignments.findById(relationId).orElseThrow();
          relation.setDeleteDate(null);
        });

    assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM);
    assertThat(pending()).isEmpty();
    verifyNoInteractions(matrix);
  }

  @Test
  void publicationAndCorrectionRollBackTogether() {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> detach());
    var transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> {
          assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM);
          status.setRollbackOnly();
        });

    assertThat(sessions.findById(enquiry.getId()).orElseThrow().getMatrixRoomId()).isNull();
    assertThat(pending()).isEmpty();
    verifyNoInteractions(matrix);
  }

  private void detach() {
    consultants.lockConsultant(consultantId);
    var relation = assignments.findById(relationId).orElseThrow();
    relation.setDeleteDate(LocalDateTime.now(clock));
    revocations.stage(relation);
  }

  private List<InitialRoomContact> contacts() {
    return List.of(new InitialRoomContact(consultantId, MEMBER_ID));
  }

  private void assertAgencyLeadingIndexIsAvailable() {
    var plan =
        jdbc.queryForMap(
            """
            EXPLAIN SELECT id, matrix_room_id FROM session
            WHERE tenant_id = ? AND agency_id = ?
              AND status IN (?, ?) AND consultant_id IS NULL
              AND (is_consultant_directly_set IS NULL OR is_consultant_directly_set = FALSE)
              AND matrix_room_id IS NOT NULL AND TRIM(matrix_room_id) <> ''
            LOCK IN SHARE MODE
            """,
            TENANT_ID,
            AGENCY_ID,
            SessionStatus.INITIAL.getValue(),
            SessionStatus.NEW.getValue());
    assertThat((String) plan.get("possible_keys")).contains("idx_session_agency_consultant_status");
  }

  private List<AgencyEnquiryRevocationTask> pending() {
    return tasks.findAll().stream()
        .filter(task -> relationId.equals(task.getConsultantAgencyId()))
        .toList();
  }

  private void assertOneCorrection() {
    assertThat(pending())
        .singleElement()
        .satisfies(
            task -> {
              assertThat(task.getRoomId()).isEqualTo(ROOM);
              assertThat(task.getMemberId()).isEqualTo(MEMBER_ID);
              assertThat(task.getSessionId()).isEqualTo(enquiry.getId());
            });
    verifyNoInteractions(matrix);
  }
}
