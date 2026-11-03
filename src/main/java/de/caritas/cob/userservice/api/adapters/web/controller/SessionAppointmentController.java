package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.SessionAppointmentDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionAppointmentTimeDTO;
import de.caritas.cob.userservice.api.service.sessionappointment.SessionAppointmentService;
import de.caritas.cob.userservice.generated.api.sessionappointment.controller.UsersApi;
import io.swagger.annotations.Api;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 1:1 appointments of a conversation (ORISO-Frontend#1960). */
@RestController
@RequiredArgsConstructor
@Api(tags = "session-appointment-controller")
public class SessionAppointmentController implements UsersApi {

  private final SessionAppointmentService service;

  @Override
  public ResponseEntity<List<SessionAppointmentDTO>> getSessionAppointments(Long sessionId) {
    return ResponseEntity.ok(service.list(sessionId));
  }

  @Override
  public ResponseEntity<SessionAppointmentDTO> createSessionAppointment(
      Long sessionId, SessionAppointmentTimeDTO time) {
    return new ResponseEntity<>(service.create(sessionId, time), HttpStatus.CREATED);
  }

  @Override
  public ResponseEntity<SessionAppointmentDTO> rescheduleSessionAppointment(
      Long sessionId, UUID appointmentId, SessionAppointmentTimeDTO time) {
    return ResponseEntity.ok(service.reschedule(sessionId, appointmentId, time));
  }

  @Override
  public ResponseEntity<SessionAppointmentDTO> cancelSessionAppointment(
      Long sessionId, UUID appointmentId) {
    return ResponseEntity.ok(service.cancel(sessionId, appointmentId));
  }

  @Override
  public ResponseEntity<List<SessionAppointmentDTO>> getMyUpcomingSessionAppointments() {
    return ResponseEntity.ok(service.upcoming());
  }
}
