package de.caritas.cob.userservice.api.service.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.Appointment;
import de.caritas.cob.userservice.api.port.out.AppointmentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Emits the appointment events already exposed by the notification settings contract. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentLifecycleNotificationService {

  private static final Duration BRIEFING_WINDOW = Duration.ofMinutes(15);

  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull CallLifecycleEmailNotificationService emailNotificationService;
  private final @NonNull AppointmentRepository appointmentRepository;
  private final @NonNull ObjectMapper objectMapper;
  private final @NonNull Clock clock;

  public void requested(Map<String, Object> appointment) {
    create(
        "appointment.requested",
        appointment,
        "Appointment requested",
        "A new appointment was requested.");
  }

  public void scheduled(Map<String, Object> appointment) {
    create(
        "appointment.scheduled",
        appointment,
        "Appointment scheduled",
        "An appointment was scheduled.");
  }

  public void cancelled(Map<String, Object> appointment) {
    create(
        "appointment.cancelled",
        appointment,
        "Appointment cancelled",
        "An appointment was cancelled.");
  }

  @Scheduled(cron = "0 * * * * ?")
  public void emitUpcomingBriefings() {
    Instant start = clock.instant();
    Instant end = start.plus(BRIEFING_WINDOW);
    appointmentRepository.findByDatetimeBetween(start, end).stream()
        .filter(appointment -> appointment.getStatus() == Appointment.AppointmentStatus.CREATED)
        .map(this::mapOf)
        .forEach(
            appointment ->
                create(
                    "appointment.briefing",
                    appointment,
                    "Appointment starts soon",
                    "Your appointment starts soon."));
  }

  private void create(
      String eventType, Map<String, Object> appointment, String title, String text) {
    if (appointment == null) {
      return;
    }
    String appointmentId = value(appointment.get("id"));
    String consultantId = value(appointment.get("consultantId"));
    if (appointmentId == null || consultantId == null) {
      return;
    }
    String occurrence =
        "appointment.scheduled".equals(eventType) ? ":" + value(appointment.get("datetime")) : "";
    boolean created =
        eventNotificationService.createEventOnce(
            "appointment:" + eventType + ":" + appointmentId + occurrence,
            consultantId,
            eventType,
            EventNotificationService.CATEGORY_SYSTEM,
            title,
            text,
            serialize(appointment),
            "/appointments",
            null,
            null);
    if (created && "appointment.briefing".equals(eventType)) {
      emailNotificationService.sendReminder(consultantId, null);
    }
  }

  private Map<String, Object> mapOf(Appointment appointment) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("id", appointment.getId().toString());
    values.put("appointmentId", appointment.getId().toString());
    values.put("datetime", appointment.getDatetime().toString());
    values.put("startsAt", appointment.getDatetime().toString());
    values.put("consultantId", appointment.getConsultant().getId());
    if (appointment.getBookingId() != null) {
      values.put("bookingId", appointment.getBookingId());
    }
    return values;
  }

  private String serialize(Map<String, Object> appointment) {
    Map<String, Object> params = new LinkedHashMap<>();
    putIfPresent(params, "appointmentId", appointment.get("id"));
    putIfPresent(params, "startsAt", appointment.get("datetime"));
    putIfPresent(params, "bookingId", appointment.get("bookingId"));
    try {
      return objectMapper.writeValueAsString(params);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not serialize appointment notification", exception);
    }
  }

  private void putIfPresent(Map<String, Object> target, String key, Object value) {
    if (value != null) {
      target.put(key, value);
    }
  }

  private String value(Object raw) {
    if (raw == null) {
      return null;
    }
    String value = String.valueOf(raw);
    return value.isBlank() ? null : value;
  }
}
