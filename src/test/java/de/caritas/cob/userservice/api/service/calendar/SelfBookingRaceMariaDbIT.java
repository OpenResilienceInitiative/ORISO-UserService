package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.AppointmentKind;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionAppointmentTimeDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarWorkingHours;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverService;
import de.caritas.cob.userservice.api.service.sessionappointment.SessionAppointmentService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Self-booking (ORISO-Frontend#1050) on MariaDB at REPEATABLE READ: two askers of the same
 * counsellor book the same slot at the same moment. Both have read their conversation, so both
 * transactions hold a snapshot from before either booking; both then wait on the counsellor's row.
 * Exactly one booking may be stored: the second must see the first although its snapshot does not.
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  SessionAppointmentService.class,
  CounsellorFreeTime.class,
  CalendarEntryReader.class,
  SelfBookingRaceMariaDbIT.Clocks.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SelfBookingRaceMariaDbIT {

  private static final long TENANT = 7L;

  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>(System.getProperty("it.mariadb.image", "mariadb:10.11"))
          .withDatabaseName("userservice");

  static {
    DATABASE.start();
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
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
    registry.add(
        "spring.datasource.hikari.transaction-isolation", () -> "TRANSACTION_REPEATABLE_READ");
    registry.add(
        "spring.datasource.hikari.connection-init-sql",
        () -> "SET SESSION innodb_lock_wait_timeout = 15");
  }

  @TestConfiguration
  static class Clocks {
    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }

  @Autowired private SessionAppointmentService service;
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private SessionRepository sessions;
  @Autowired private SessionAppointmentRepository appointments;
  @Autowired private CalendarWorkingHoursRepository workingHours;
  @Autowired private CounsellorFreeTime freeTime;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private javax.sql.DataSource dataSource;

  @MockitoBean private AuthenticatedUser caller;
  @MockitoBean private CaseHandoverService caseHandovers;

  /** The asker each booking thread acts as; the mocked caller answers per thread. */
  private final ThreadLocal<String> actingAs = new ThreadLocal<>();

  private TransactionTemplate transaction;
  private Consultant counsellor;
  private Session first;
  private Session second;

  @BeforeEach
  void setUp() {
    transaction = new TransactionTemplate(transactionManager);
    when(caller.getUserId()).thenAnswer(invocation -> actingAs.get());
    when(caller.isConsultant()).thenReturn(false);
    when(caller.getTenantId()).thenReturn(TENANT);
    transaction.executeWithoutResult(
        status -> {
          counsellor = consultants.save(consultant());
          first = sessions.save(session(users.save(user()), counsellor));
          second = sessions.save(session(users.save(user()), counsellor));
          for (int weekday = 0; weekday <= 6; weekday++) {
            var row = new CalendarWorkingHours();
            row.setConsultantId(counsellor.getId());
            row.setWeekday(weekday);
            row.setStartTime(LocalTime.of(0, 0));
            row.setEndTime(LocalTime.of(23, 30));
            workingHours.save(row);
          }
        });
  }

  @AfterEach
  void tearDown() {
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM calendar_working_hours WHERE consultant_id = ?", counsellor.getId());
    for (var session : new Session[] {first, second}) {
      jdbc.update("DELETE FROM session_appointment WHERE session_id = ?", session.getId());
      jdbc.update("DELETE FROM session WHERE id = ?", session.getId());
      jdbc.update("DELETE FROM user WHERE user_id = ?", session.getUser().getUserId());
    }
    jdbc.update("DELETE FROM consultant WHERE consultant_id = ?", counsellor.getId());
  }

  @Test
  void twoAskers_Should_NeverGetTheSameSlotOfOneCounsellor() throws Exception {
    var now = Instant.now();
    var slot = freeTime.slots(counsellor.getId(), now, now.plus(Duration.ofDays(3))).get(0);
    var time =
        new SessionAppointmentTimeDTO()
            .startTime(slot.start().atOffset(ZoneOffset.UTC))
            .endTime(slot.end().atOffset(ZoneOffset.UTC))
            .kind(AppointmentKind.VIDEO);

    var pool = Executors.newFixedThreadPool(3);
    var holding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try {
      // Holds the counsellor's row, so both bookings read their conversation and then wait.
      var holder =
          CompletableFuture.runAsync(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        consultants.lockConsultant(counsellor.getId());
                        holding.countDown();
                        await(release);
                      }),
              pool);
      assertThat(holding.await(15, TimeUnit.SECONDS)).isTrue();

      var bookFirst = CompletableFuture.supplyAsync(() -> book(first, time), pool);
      var bookSecond = CompletableFuture.supplyAsync(() -> book(second, time), pool);
      awaitWaitersOnTheCounsellor(2, bookFirst, bookSecond);
      release.countDown();
      holder.get(15, TimeUnit.SECONDS);

      var outcomes = new String[] {outcome(bookFirst), outcome(bookSecond)};
      assertThat(outcomes).containsExactlyInAnyOrder("booked", "conflict");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
    var stored =
        transaction.execute(
            status ->
                appointments.lockScheduledOfConsultantBetween(
                    counsellor.getId(), slot.start(), slot.end()));
    assertThat(stored).hasSize(1);
  }

  private String book(Session session, SessionAppointmentTimeDTO time) {
    actingAs.set(session.getUser().getUserId());
    try {
      // A thread without a Träger sees no row: the booking runs in the conversation's.
      Tenants.in(TENANT, () -> service.book(session.getId(), time));
      return "booked";
    } catch (ConflictException conflict) {
      return "conflict";
    } finally {
      actingAs.remove();
    }
  }

  private static String outcome(CompletableFuture<String> booking)
      throws InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
    return booking.get(30, TimeUnit.SECONDS);
  }

  /** Waits until MariaDB shows {@code count} statements blocked on the counsellor's row. */
  private void awaitWaitersOnTheCounsellor(int count, CompletableFuture<?>... bookings)
      throws Exception {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    try (var connection =
            DriverManager.getConnection(DATABASE.getJdbcUrl(), "root", DATABASE.getPassword());
        var statement =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE COMMAND = 'Query'"
                    + " AND LOWER(INFO) LIKE '%from consultant where consultant_id%for update%'")) {
      while (true) {
        try (var result = statement.executeQuery()) {
          result.next();
          if (result.getLong(1) >= count) {
            return;
          }
        }
        if (System.nanoTime() > deadline) {
          var states = new StringBuilder();
          for (var booking : bookings) {
            states.append(' ').append(booking.isDone() ? stateOf(booking) : "running");
          }
          throw new AssertionError("The bookings do not wait on the counsellor's row:" + states);
        }
        Thread.sleep(20);
      }
    }
  }

  private static String stateOf(CompletableFuture<?> booking) {
    try {
      return String.valueOf(booking.join());
    } catch (RuntimeException failure) {
      return failure.getCause() == null ? failure.toString() : failure.getCause().toString();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static Session session(User asker, Consultant counsellor) {
    var session = new Session(asker, 1, "12345", 1L, Session.SessionStatus.IN_PROGRESS, false);
    session.setTenantId(TENANT);
    session.setAgencyId(99L);
    session.setLanguageCode(LanguageCode.de);
    session.setIsConsultantDirectlySet(false);
    session.setConsultant(counsellor);
    session.setCreateDate(LocalDateTime.now());
    session.setUpdateDate(LocalDateTime.now());
    session.setSupervisionOptedOut(false);
    return session;
  }

  private static User user() {
    var id = "booking-asker-" + UUID.randomUUID().toString().substring(0, 8);
    return User.builder()
        .userId(id)
        .username(id)
        .email(id + "@synthetic.oriso.test")
        .matrixUserId("@" + id + ":example")
        .tenantId(TENANT)
        .encourage2fa(true)
        .magicLinkLoginEnabled(false)
        .languageCode(LanguageCode.de)
        .createDate(LocalDateTime.now())
        .updateDate(LocalDateTime.now())
        .build();
  }

  private static Consultant consultant() {
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
    return consultant;
  }
}
