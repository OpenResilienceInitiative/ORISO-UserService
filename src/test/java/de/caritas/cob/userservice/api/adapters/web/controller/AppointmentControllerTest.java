package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.Appointment;
import de.caritas.cob.userservice.api.adapters.web.dto.AppointmentStatus;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateEnquiryMessageResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.EnquiryAppointmentDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.AppointmentDtoMapper;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.CreateEnquiryMessageFacade;
import de.caritas.cob.userservice.api.facade.assignsession.AssignEnquiryFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.EnquiryData;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.Organizing;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AppointmentControllerTest {

  @Mock private Organizing organizer;
  @Mock private AppointmentDtoMapper mapper;
  @Mock private AuthenticatedUser currentUser;
  @Mock private UserAccountService userAccountProvider;
  @Mock private CreateEnquiryMessageFacade createEnquiryMessageFacade;
  @Mock private AssignEnquiryFacade assignEnquiryFacade;
  @Mock private SessionService sessionService;
  @Mock private ConsultantService consultantService;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private StatisticsService statisticsService;

  @InjectMocks private AppointmentController controller;

  @Test
  void createEnquiryAppointment_happyPath_returnsCreatedAndDelegatesToFacades() {
    // Business reason: enquiry appointments must create a message and assign a consultant
    // atomically.
    var dto = org.mockito.Mockito.mock(EnquiryAppointmentDTO.class);
    var responseDto = org.mockito.Mockito.mock(CreateEnquiryMessageResponseDTO.class);
    var consultant = org.mockito.Mockito.mock(Consultant.class);
    var session = org.mockito.Mockito.mock(Session.class);
    var user = User.builder().userId("user-1").username("user").email("u@example.org").build();
    when(dto.getT()).thenReturn("message");
    when(dto.getCounselorEmail()).thenReturn("consultant@example.org");
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user);
    when(createEnquiryMessageFacade.createEnquiryMessage(any(EnquiryData.class)))
        .thenReturn(responseDto);
    when(consultantService.findConsultantByEmail("consultant@example.org"))
        .thenReturn(Optional.of(consultant));
    when(sessionService.getSession(44L)).thenReturn(Optional.of(session));

    var response = controller.createEnquiryAppointment(44L, dto);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertEquals(responseDto, response.getBody());
    verify(assignEnquiryFacade).assignRegisteredEnquiry(session, consultant, true);
  }

  @Test
  void createEnquiryAppointment_consultantMissing_throwsNoSuchElementException() {
    // Business reason: assigning enquiry without a resolved consultant must fail loudly.
    var dto = org.mockito.Mockito.mock(EnquiryAppointmentDTO.class);
    var user = User.builder().userId("user-1").username("user").email("u@example.org").build();
    when(dto.getCounselorEmail()).thenReturn("missing@example.org");
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user);
    when(createEnquiryMessageFacade.createEnquiryMessage(any(EnquiryData.class)))
        .thenReturn(org.mockito.Mockito.mock(CreateEnquiryMessageResponseDTO.class));
    when(consultantService.findConsultantByEmail("missing@example.org"))
        .thenReturn(Optional.empty());
    when(sessionService.getSession(45L))
        .thenReturn(Optional.of(org.mockito.Mockito.mock(Session.class)));

    assertThrows(
        java.util.NoSuchElementException.class,
        () -> controller.createEnquiryAppointment(45L, dto));
  }

  @Test
  void createEnquiryAppointment_sessionMissing_throwsNoSuchElementException() {
    // Business reason: assigning enquiry requires an existing session context.
    var dto = org.mockito.Mockito.mock(EnquiryAppointmentDTO.class);
    var consultant = org.mockito.Mockito.mock(Consultant.class);
    var user = User.builder().userId("user-1").username("user").email("u@example.org").build();
    when(dto.getCounselorEmail()).thenReturn("consultant@example.org");
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user);
    when(createEnquiryMessageFacade.createEnquiryMessage(any(EnquiryData.class)))
        .thenReturn(org.mockito.Mockito.mock(CreateEnquiryMessageResponseDTO.class));
    when(consultantService.findConsultantByEmail("consultant@example.org"))
        .thenReturn(Optional.of(consultant));
    when(sessionService.getSession(46L)).thenReturn(Optional.empty());

    assertThrows(
        java.util.NoSuchElementException.class,
        () -> controller.createEnquiryAppointment(46L, dto));
  }

  @Test
  void createEnquiryAppointment_withExistingSession_delegatesWithoutThrow() {
    // Business reason: an appointment request for an existing session must be delegated.
    var dto = org.mockito.Mockito.mock(EnquiryAppointmentDTO.class);
    var responseDto = org.mockito.Mockito.mock(CreateEnquiryMessageResponseDTO.class);
    var consultant = org.mockito.Mockito.mock(Consultant.class);
    var session = org.mockito.Mockito.mock(Session.class);
    var user = User.builder().userId("user-2").username("user2").email("u2@example.org").build();
    when(dto.getT()).thenReturn("hello");
    when(dto.getCounselorEmail()).thenReturn("consultant@example.org");
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user);
    when(createEnquiryMessageFacade.createEnquiryMessage(any(EnquiryData.class)))
        .thenReturn(responseDto);
    when(consultantService.findConsultantByEmail("consultant@example.org"))
        .thenReturn(Optional.of(consultant));
    when(sessionService.getSession(47L)).thenReturn(Optional.of(session));

    var response = controller.createEnquiryAppointment(47L, dto);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    verify(createEnquiryMessageFacade).createEnquiryMessage(any(EnquiryData.class));
  }

  @Test
  void getAppointmentByBookingId_unknownBookingId_throwsNotFoundException() {
    // Business reason: unknown booking IDs should map to 404 for API consumers.
    when(organizer.findAppointmentByBookingId(999)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> controller.getAppointmentByBookingId(999));
  }

  @Test
  void createAppointment_statusNotCreated_throwsBadRequest() {
    // Business reason: new appointments must start from CREATED status to preserve workflow
    // invariants.
    var appointment = new Appointment();
    appointment.setId(null);
    appointment.setStatus(AppointmentStatus.STARTED);

    assertThrows(BadRequestException.class, () -> controller.createAppointment(appointment));
  }

  @Test
  void createAppointment_technicalRoleUnknownConsultantEmail_throwsBadRequest() {
    // Business reason: technical users may create appointments only for known consultant
    // identities.
    var appointment = new Appointment();
    appointment.setId(null);
    appointment.setStatus(AppointmentStatus.CREATED);
    appointment.setConsultantEmail("missing@example.org");
    appointment.setDatetime(Instant.now());
    when(currentUser.getRoles()).thenReturn(Set.of(UserRole.TECHNICAL.getValue()));
    when(consultantRepository.findByEmailAndDeleteDateIsNull("missing@example.org"))
        .thenReturn(Optional.empty());

    assertThrows(BadRequestException.class, () -> controller.createAppointment(appointment));
  }

  @Test
  void createAppointment_invalidRoleEmailCombination_throwsBadRequest() {
    // Business reason: role/email combinations outside supported rules must be rejected
    // consistently.
    var appointment = new Appointment();
    appointment.setId(null);
    appointment.setStatus(AppointmentStatus.CREATED);
    appointment.setConsultantEmail("consultant@example.org");
    appointment.setDatetime(Instant.now());
    when(currentUser.getRoles()).thenReturn(Set.of(UserRole.CONSULTANT.getValue()));

    assertThrows(BadRequestException.class, () -> controller.createAppointment(appointment));
  }

  @Test
  void updateAppointment_nullPayloadIdMismatch_throwsBadRequest() {
    // Business reason: updates require strict path/payload ID consistency to avoid wrong-record
    // edits.
    var appointment = new Appointment();
    appointment.setId(null);
    appointment.setStatus(AppointmentStatus.CREATED);

    assertThrows(
        BadRequestException.class,
        () -> controller.updateAppointment(UUID.randomUUID(), appointment));
  }

  @Test
  void updateAppointment_noStatisticsEvent_emitsNoStatisticsCall() {
    // Business reason: only start/pause transitions should emit statistics events.
    var id = UUID.randomUUID();
    var appointment = new Appointment();
    appointment.setId(id);
    appointment.setStatus(AppointmentStatus.CREATED);
    appointment.setDescription("updated");
    appointment.setDatetime(Instant.now());
    var existing = Map.<String, Object>of("id", id.toString());
    var updated = Map.<String, Object>of("id", id.toString(), "status", "created");
    var saved = new Appointment();
    saved.setId(id);
    saved.setStatus(AppointmentStatus.CREATED);
    when(organizer.findAppointment(id.toString())).thenReturn(Optional.of(existing));
    when(mapper.mapOf(existing, appointment)).thenReturn(updated);
    when(organizer.upsertAppointment(updated)).thenReturn(updated);
    when(mapper.appointmentOf(updated, true)).thenReturn(saved);
    when(currentUser.getUserId()).thenReturn("consultant-1");
    when(mapper.eventOf(id, AppointmentStatus.CREATED, "consultant-1"))
        .thenReturn(Optional.empty());

    var response = controller.updateAppointment(id, appointment);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(statisticsService, never()).fireEvent(any());
  }
}
