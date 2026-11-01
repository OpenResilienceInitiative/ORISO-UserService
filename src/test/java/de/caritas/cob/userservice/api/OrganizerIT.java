package de.caritas.cob.userservice.api;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.Mockito.doReturn;

import de.caritas.cob.userservice.api.model.Appointment;
import de.caritas.cob.userservice.api.port.out.AppointmentRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OrganizerIT {

  private static final EasyRandom easyRandom = new EasyRandom();

  private static final Instant NOW =
      LocalDateTime.of(2022, 2, 15, 13, 37).toInstant(ZoneOffset.UTC);

  @Autowired private ConsultantRepository consultantRepository;

  @Autowired private AppointmentRepository appointmentRepository;

  @MockitoBean private Clock clock;

  @Autowired private Organizer organizer;

  /**
   * The application enables scheduling, so a scheduled task can call this mock from another thread.
   * {@code when(clock.instant())} resolves its target from the mock's shared invocation container,
   * so a concurrent {@code getZone()} stole the stub and the test failed with {@code
   * WrongTypeOfReturnValue: Instant cannot be returned by getZone()}. The doReturn form names its
   * target explicitly, and stubbing getZone() keeps a stray caller from getting null.
   */
  @BeforeEach
  void fixTheClock() {
    doReturn(ZoneOffset.UTC).when(clock).getZone();
    doReturn(NOW).when(clock).instant();
  }

  @Test
  void deleteObsoleteAppointmentsShouldDeleteAppointmentsOlderThanLifespan() {
    var today = NOW;
    createAppointment(today);
    var tomorrow = LocalDateTime.of(2022, 2, 16, 11, 44).toInstant(ZoneOffset.UTC);
    createAppointment(tomorrow);
    // test properties' lifespan is three hours
    var threeHoursAgo = today.minus(3, ChronoUnit.HOURS);
    createAppointment(threeHoursAgo);
    var yesterday = today.minus(1, ChronoUnit.DAYS);
    createAppointment(yesterday);

    organizer.deleteObsoleteAppointments();

    var notDeleted =
        StreamSupport.stream(appointmentRepository.findAll().spliterator(), false)
            .map(Appointment::getDatetime)
            .collect(Collectors.toList());
    assertThat(notDeleted).containsExactlyInAnyOrder(today, tomorrow);
  }

  private void createAppointment(Instant datetime) {
    var consultant = consultantRepository.findAll().iterator().next();
    var toSave = easyRandom.nextObject(Appointment.class);
    toSave.setConsultant(consultant);
    toSave.setId(null);
    var desc = toSave.getDescription();
    if (desc.length() > 300) {
      toSave.setDescription(desc.substring(0, 300));
    }
    toSave.setDatetime(datetime);
    appointmentRepository.save(toSave);
  }
}
