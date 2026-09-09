package de.caritas.cob.userservice.api.service.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.Appointment;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AppointmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppointmentLifecycleNotificationServiceTest {

  @Mock private EventNotificationService eventNotificationService;
  @Mock private CallLifecycleEmailNotificationService emailNotificationService;
  @Mock private AppointmentRepository appointmentRepository;

  private AppointmentLifecycleNotificationService service;

  @BeforeEach
  void setUp() {
    service =
        new AppointmentLifecycleNotificationService(
            eventNotificationService,
            emailNotificationService,
            appointmentRepository,
            new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-09-10T09:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void requestedUsesAppointmentIdAsStableDeduplicationKey() {
    service.requested(
        Map.of(
            "id", "appointment-1",
            "consultantId", "consultant-1",
            "datetime", "2026-09-10T09:10:00Z"));

    verify(eventNotificationService)
        .createEventOnce(
            eq("appointment:appointment.requested:appointment-1"),
            eq("consultant-1"),
            eq("appointment.requested"),
            eq(EventNotificationService.CATEGORY_SYSTEM),
            any(),
            any(),
            any(),
            eq("/appointments"),
            eq(null),
            eq(null));
  }

  @Test
  void briefingEmitsOnlyForCreatedAppointmentsInUpcomingWindow() {
    Appointment created = appointment(Appointment.AppointmentStatus.CREATED, "consultant-1");
    Appointment started = appointment(Appointment.AppointmentStatus.STARTED, "consultant-2");
    when(appointmentRepository.findByDatetimeBetween(
            Instant.parse("2026-09-10T09:00:00Z"), Instant.parse("2026-09-10T09:15:00Z")))
        .thenReturn(List.of(created, started));
    when(eventNotificationService.createEventOnce(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(true);

    service.emitUpcomingBriefings();

    verify(eventNotificationService)
        .createEventOnce(
            any(),
            eq("consultant-1"),
            eq("appointment.briefing"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
    verify(eventNotificationService, never())
        .createEventOnce(
            any(),
            eq("consultant-2"),
            eq("appointment.briefing"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
    verify(emailNotificationService).sendReminder("consultant-1", null);
  }

  private Appointment appointment(Appointment.AppointmentStatus status, String consultantId) {
    Consultant consultant = new Consultant();
    consultant.setId(consultantId);
    Appointment appointment = new Appointment();
    appointment.setId(UUID.randomUUID());
    appointment.setConsultant(consultant);
    appointment.setDatetime(Instant.parse("2026-09-10T09:10:00Z"));
    appointment.setStatus(status);
    return appointment;
  }
}
