package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Appointment;
import de.caritas.cob.userservice.api.model.Appointment.AppointmentStatus;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.AppointmentRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class OrganizerMariaDbReplicaIT {

  private static final Instant NOW = Instant.parse("2026-07-26T05:00:00Z");
  private static final String CONSULTANT_ID = "appointment-replica-proof";
  private static final int EXPIRED_APPOINTMENTS = 120;
  private static final int CURRENT_APPOINTMENTS = 30;

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
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
    registry.add("appointments.delete-job-enabled", () -> "true");
    registry.add("appointments.lifespan-in-hours", () -> "24");
  }

  @Autowired private Organizer organizer;
  @Autowired private AppointmentRepository appointmentRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @MockitoBean private Clock clock;

  @BeforeEach
  void setUp() {
    appointmentRepository.deleteAll();
    consultantRepository.deleteById(CONSULTANT_ID);
    consultantRepository.save(
        Consultant.builder()
            .id(CONSULTANT_ID)
            .rocketChatId("replica-proof")
            .username("appointment-replica-proof")
            .firstName("Appointment")
            .lastName("Replica")
            .email("appointment-replica@example.invalid")
            .encourage2fa(true)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(true)
            .notifyNewChatMessageFromAdviceSeeker(true)
            .walkThroughEnabled(true)
            .languageCode(LanguageCode.de)
            .status(ConsultantStatus.IN_PROGRESS)
            .createDate(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))
            .updateDate(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))
            .build());
    when(clock.instant()).thenReturn(NOW);
  }

  @AfterEach
  void cleanUp() {
    appointmentRepository.deleteAll();
    consultantRepository.deleteById(CONSULTANT_ID);
  }

  @Test
  void concurrentCleanupTransactionsDeleteEveryExpiredAppointmentExactlyOnce() throws Exception {
    var consultant = consultantRepository.findById(CONSULTANT_ID).orElseThrow();
    saveAppointments(consultant, NOW.minus(48, ChronoUnit.HOURS), EXPIRED_APPOINTMENTS, "expired");
    saveAppointments(consultant, NOW, CURRENT_APPOINTMENTS, "current");
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var firstResult = executor.submit(() -> runCleanup(ready, start));
      var secondResult = executor.submit(() -> runCleanup(ready, start));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      firstResult.get(10, TimeUnit.SECONDS);
      secondResult.get(10, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    var remaining = appointmentRepository.findAll();
    assertThat(remaining)
        .hasSize(CURRENT_APPOINTMENTS)
        .allSatisfy(appointment -> assertThat(appointment.getDatetime()).isEqualTo(NOW));
  }

  private void saveAppointments(
      Consultant consultant, Instant datetime, int count, String description) {
    var appointments = new ArrayList<Appointment>();
    for (int index = 0; index < count; index++) {
      var appointment = new Appointment();
      appointment.setConsultant(consultant);
      appointment.setDatetime(datetime);
      appointment.setDescription(description + "-" + index);
      appointment.setStatus(AppointmentStatus.CREATED);
      appointments.add(appointment);
    }
    appointmentRepository.saveAll(appointments);
  }

  private void runCleanup(CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    organizer.deleteObsoleteAppointments();
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent cleanup proof");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
