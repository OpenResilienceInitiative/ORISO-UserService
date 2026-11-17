package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.AppointmentKind;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionAppointmentTimeDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CalendarBookingSetting;
import de.caritas.cob.userservice.api.model.CalendarConsultationHours;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CalendarBookingSettingRepository;
import de.caritas.cob.userservice.api.port.out.CalendarConsultationHoursRepository;
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
import java.util.Set;
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
 * Self-booking (ORISO-Frontend#1050, #2287) on MariaDB at REPEATABLE READ. Two askers of the same
 * counsellor book the same slot at the same moment: both have read their conversation, so both
 * transactions hold a snapshot from before either booking; both then wait on the counsellor's row.
 * Exactly one booking may be stored: the second must see the first although its snapshot does not.
 * The same holds when the counsellor confirms a request while another asker books its time.
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
  @Autowired private CalendarConsultationHoursRepository consultationHours;
  @Autowired private CounsellorFreeTime freeTime;
  @Autowired private CalendarBookingSettingRepository bookingSettings;
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
  private Consultant colleague;
  private Session colleaguesSession;

  @BeforeEach
  void setUp() {
    transaction = new TransactionTemplate(transactionManager);
    when(caller.getUserId()).thenAnswer(invocation -> actingAs.get());
    when(caller.isConsultant())
        .thenAnswer(invocation -> counsellor != null && counsellor.getId().equals(actingAs.get()));
    when(caller.getTenantId()).thenReturn(TENANT);
    transaction.executeWithoutResult(
        status -> {
          counsellor = consultants.save(consultant());
          first = sessions.save(session(users.save(user()), counsellor));
          second = sessions.save(session(users.save(user()), counsellor));
          for (int weekday = 0; weekday <= 6; weekday++) {
            var row = new CalendarConsultationHours();
            row.setConsultantId(counsellor.getId());
            row.setWeekday(weekday);
            row.setStartTime(LocalTime.of(0, 0));
            row.setEndTime(LocalTime.of(23, 30));
            consultationHours.save(row);
          }
        });
  }

  @AfterEach
  void tearDown() {
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.update(
        "DELETE FROM calendar_consultation_hours WHERE consultant_id = ?", counsellor.getId());
    jdbc.update("DELETE FROM calendar_booking_setting WHERE consultant_id = ?", counsellor.getId());
    for (var session : new Session[] {first, second}) {
      jdbc.update("DELETE FROM session_appointment WHERE session_id = ?", session.getId());
      jdbc.update("DELETE FROM session WHERE id = ?", session.getId());
      jdbc.update("DELETE FROM user WHERE user_id = ?", session.getUser().getUserId());
    }
    jdbc.update("DELETE FROM consultant WHERE consultant_id = ?", counsellor.getId());
    if (colleague != null) {
      jdbc.update(
          "DELETE FROM calendar_consultation_hours WHERE consultant_id = ?", colleague.getId());
      jdbc.update(
          "DELETE FROM session_appointment WHERE session_id = ?", colleaguesSession.getId());
      jdbc.update("DELETE FROM session WHERE id = ?", colleaguesSession.getId());
      jdbc.update("DELETE FROM user WHERE user_id = ?", colleaguesSession.getUser().getUserId());
      jdbc.update("DELETE FROM consultant WHERE consultant_id = ?", colleague.getId());
    }
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
                    counsellor.getId(), Set.of(), slot.start(), slot.end()));
    assertThat(stored).hasSize(1);
  }

  @Test
  void confirmAndACompetingBooking_Should_NeverBothTakeTheTime() throws Exception {
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

      // The second asker's booking reads its conversation, then waits on the counsellor's row:
      // its snapshot is older than the request stored next.
      var booking = CompletableFuture.supplyAsync(() -> book(second, time), pool);
      awaitWaitersOnTheCounsellor(1, booking);
      var request =
          transaction.execute(
              status -> {
                var requested = new SessionAppointment();
                requested.setSession(sessions.findById(first.getId()).orElseThrow());
                requested.setStatus(SessionAppointment.Status.REQUESTED);
                requested.setStartTime(slot.start());
                requested.setEndTime(slot.end());
                requested.setKind(SessionAppointment.Kind.VIDEO);
                return appointments.save(requested);
              });
      var confirm = CompletableFuture.supplyAsync(() -> confirm(request.getId()), pool);
      awaitWaitersOnTheCounsellor(2, booking, confirm);
      release.countDown();
      holder.get(15, TimeUnit.SECONDS);

      assertThat(outcome(booking)).isEqualTo("conflict");
      assertThat(outcome(confirm)).isEqualTo("confirmed");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
    var stored =
        transaction.execute(
            status ->
                appointments.lockScheduledOfConsultantBetween(
                    counsellor.getId(), Set.of(), slot.start(), slot.end()));
    assertThat(stored)
        .extracting(SessionAppointment::getStatus)
        .containsExactly(SessionAppointment.Status.SCHEDULED);
  }

  /**
   * The counsellor turns automatic acceptance off while a booking waits on their row: the booking
   * read its conversation before, yet it must follow the setting the counsellor committed and store
   * a request, not a booked appointment (CodeRabbit on UserService#1669).
   */
  @Test
  void aBookingWaitingOnTheCounsellor_Should_FollowAutoAcceptTurnedOffMeanwhile() throws Exception {
    var now = Instant.now();
    var slot =
        freeTime.slots(counsellor.getId(), now, now.plus(Duration.ofDays(3))).stream()
            .filter(free -> free.mode() == FreeSlot.BookingMode.INSTANT)
            .findFirst()
            .orElseThrow();
    var time =
        new SessionAppointmentTimeDTO()
            .startTime(slot.start().atOffset(ZoneOffset.UTC))
            .endTime(slot.end().atOffset(ZoneOffset.UTC))
            .kind(AppointmentKind.VIDEO);

    var pool = Executors.newFixedThreadPool(2);
    var holding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try {
      // Changes the setting the way the counsellor's settings do: under the counsellor's row.
      var counsellorTurnsItOff =
          CompletableFuture.runAsync(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        consultants.lockConsultant(counsellor.getId());
                        holding.countDown();
                        await(release);
                        var setting = CalendarBookingSetting.defaults(counsellor.getId());
                        setting.setAutoAccept(false);
                        bookingSettings.save(setting);
                      }),
              pool);
      assertThat(holding.await(15, TimeUnit.SECONDS)).isTrue();

      var booking = CompletableFuture.supplyAsync(() -> book(first, time), pool);
      awaitWaitersOnTheCounsellor(1, booking);
      release.countDown();
      counsellorTurnsItOff.get(15, TimeUnit.SECONDS);

      assertThat(outcome(booking)).isEqualTo("booked");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
    var stored =
        transaction.execute(
            status ->
                appointments.lockScheduledOfConsultantBetween(
                    counsellor.getId(), Set.of(), slot.start(), slot.end()));
    assertThat(stored)
        .extracting(SessionAppointment::getStatus)
        .containsExactly(SessionAppointment.Status.REQUESTED);
  }

  /**
   * The counsellor removes their Sprechstunden while a booking waits on their row: the booking read
   * its conversation before, yet it must find the time no longer offered (CodeRabbit on
   * UserService#1669).
   */
  @Test
  void aBookingWaitingOnTheCounsellor_Should_FollowSprechstundenRemovedMeanwhile()
      throws Exception {
    var now = Instant.now();
    var slot = freeTime.slots(counsellor.getId(), now, now.plus(Duration.ofDays(3))).get(0);
    var time =
        new SessionAppointmentTimeDTO()
            .startTime(slot.start().atOffset(ZoneOffset.UTC))
            .endTime(slot.end().atOffset(ZoneOffset.UTC))
            .kind(AppointmentKind.VIDEO);

    var pool = Executors.newFixedThreadPool(2);
    var holding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try {
      // Changes the Sprechstunden the way the counsellor's settings do: under the counsellor's row.
      var counsellorClearsThem =
          CompletableFuture.runAsync(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        consultants.lockConsultant(counsellor.getId());
                        holding.countDown();
                        await(release);
                        consultationHours.deleteAll(
                            consultationHours
                                .findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(
                                    counsellor.getId()));
                      }),
              pool);
      assertThat(holding.await(15, TimeUnit.SECONDS)).isTrue();

      var booking = CompletableFuture.supplyAsync(() -> book(first, time), pool);
      awaitWaitersOnTheCounsellor(1, booking);
      release.countDown();
      counsellorClearsThem.get(15, TimeUnit.SECONDS);

      assertThat(outcome(booking)).isEqualTo("conflict");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  /**
   * A colleague's conversation the counsellor holds co-access to shows in the counsellor's calendar
   * and takes their time. An asker's booking that waits on the counsellor's row must still see an
   * appointment booked in that conversation meanwhile (CodeRabbit on UserService#1669).
   */
  @Test
  void aBookingInACoAccessConversation_Should_KeepTheCounsellorsTimeTaken() throws Exception {
    transaction.executeWithoutResult(
        status -> {
          colleague = consultants.save(consultant());
          colleaguesSession = sessions.save(session(users.save(user()), colleague));
          for (int weekday = 0; weekday <= 6; weekday++) {
            var row = new CalendarConsultationHours();
            row.setConsultantId(colleague.getId());
            row.setWeekday(weekday);
            row.setStartTime(LocalTime.of(0, 0));
            row.setEndTime(LocalTime.of(23, 30));
            consultationHours.save(row);
          }
        });
    when(caseHandovers.sessionIdsWithActiveCoAccess(counsellor.getId()))
        .thenReturn(Set.of(colleaguesSession.getId()));
    var now = Instant.now();
    // Booked at once, so it is a scheduled appointment: a request in a co-access conversation
    // does not take the counsellor's time, in their calendar or here.
    var slot =
        freeTime.slots(counsellor.getId(), now, now.plus(Duration.ofDays(3))).stream()
            .filter(free -> free.mode() == FreeSlot.BookingMode.INSTANT)
            .findFirst()
            .orElseThrow();
    var time =
        new SessionAppointmentTimeDTO()
            .startTime(slot.start().atOffset(ZoneOffset.UTC))
            .endTime(slot.end().atOffset(ZoneOffset.UTC))
            .kind(AppointmentKind.VIDEO);

    var pool = Executors.newFixedThreadPool(3);
    var holding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try {
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

      // The counsellor's asker reads the conversation, then waits on the counsellor's row.
      var ownBooking = CompletableFuture.supplyAsync(() -> book(first, time), pool);
      awaitWaitersOnTheCounsellor(1, ownBooking);
      // Meanwhile the colleague's asker books the same time in the co-access conversation.
      assertThat(book(colleaguesSession, time)).isEqualTo("booked");
      release.countDown();
      holder.get(15, TimeUnit.SECONDS);

      assertThat(outcome(ownBooking)).isEqualTo("conflict");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  private String confirm(UUID appointmentId) {
    actingAs.set(counsellor.getId());
    try {
      Tenants.in(TENANT, () -> service.confirm(first.getId(), appointmentId));
      return "confirmed";
    } catch (ConflictException conflict) {
      return "conflict";
    } finally {
      actingAs.remove();
    }
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
                    // The monitoring statement matches its own pattern: leave it out.
                    + " AND ID <> CONNECTION_ID()"
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
