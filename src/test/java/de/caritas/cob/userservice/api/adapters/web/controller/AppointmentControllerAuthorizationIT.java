package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.Appointment;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import java.util.UUID;
import javax.servlet.http.Cookie;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class AppointmentControllerAuthorizationIT {

  private static final EasyRandom easyRandom = new EasyRandom();
  private static final ObjectMapper objectMapper = new ObjectMapper();

  private static final String CSRF_HEADER = "csrfHeader";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("csrfCookie", CSRF_VALUE);

  @MockBean SessionTopicEnrichmentService sessionTopicEnrichmentService;
  @Autowired private MockMvc mvc;

  private Appointment appointment;

  @BeforeEach
  public void setUp() {
    objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  }

  @AfterEach
  public void cleanUp() {
    appointment = null;
  }

  @Test
  void getAppointmentShouldReturnForbiddenWhenNoCsrfTokens() throws Exception {
    mvc.perform(
            get("/appointments/{id}", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }

  @Test
  void putAppointmentShouldReturnForbiddenWhenNoCsrfTokens() throws Exception {
    givenAValidAppointment();

    mvc.perform(
            put("/appointments/{id}", appointment.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(appointment)))
        .andExpect(status().isForbidden());
  }

  @Test
  void putAppointmentShouldReturnUnauthorizedWhenNoKeycloakAuthorization() throws Exception {
    givenAValidAppointment();

    mvc.perform(
            put("/appointments/{id}", appointment.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(appointment)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(
      authorities = {
        AuthorityValue.ANONYMOUS_DEFAULT,
        AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION,
        AuthorityValue.ASSIGN_CONSULTANT_TO_ENQUIRY,
        AuthorityValue.CREATE_NEW_CHAT,
        AuthorityValue.TECHNICAL_DEFAULT,
        AuthorityValue.USER_DEFAULT,
        AuthorityValue.USER_ADMIN,
        AuthorityValue.START_CHAT,
        AuthorityValue.STOP_CHAT,
        AuthorityValue.UPDATE_CHAT,
        AuthorityValue.VIEW_AGENCY_CONSULTANTS
      })
  void putAppointmentsShouldReturnForbiddenWhenNoConsultantAuthority() throws Exception {
    givenAValidAppointment();

    mvc.perform(
            put("/appointments/{id}", appointment.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(appointment)))
        .andExpect(status().isForbidden());
  }

  @Test
  void deleteAppointmentShouldReturnForbiddenWhenNoCsrfTokens() throws Exception {
    mvc.perform(
            delete("/appointments/{id}", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }

  @Test
  void deleteAppointmentShouldReturnUnauthorizedWhenNoKeycloakAuthorization() throws Exception {
    mvc.perform(
            delete("/appointments/{id}", UUID.randomUUID())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(
      authorities = {
        AuthorityValue.ANONYMOUS_DEFAULT,
        AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION,
        AuthorityValue.ASSIGN_CONSULTANT_TO_ENQUIRY,
        AuthorityValue.CREATE_NEW_CHAT,
        AuthorityValue.TECHNICAL_DEFAULT,
        AuthorityValue.USER_DEFAULT,
        AuthorityValue.USER_ADMIN,
        AuthorityValue.START_CHAT,
        AuthorityValue.STOP_CHAT,
        AuthorityValue.UPDATE_CHAT,
        AuthorityValue.VIEW_AGENCY_CONSULTANTS
      })
  void deleteAppointmentsShouldReturnForbiddenWhenNoConsultantAuthority() throws Exception {
    mvc.perform(
            delete("/appointments/{id}", UUID.randomUUID())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }

  @Test
  void getAppointmentsShouldReturnForbiddenWhenNoCsrfTokens() throws Exception {
    mvc.perform(
            get("/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }

  @Test
  void getAppointmentsShouldReturnUnauthorizedWhenNoKeycloakAuthorization() throws Exception {
    mvc.perform(
            get("/appointments")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(
      authorities = {
        AuthorityValue.ANONYMOUS_DEFAULT,
        AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION,
        AuthorityValue.ASSIGN_CONSULTANT_TO_ENQUIRY,
        AuthorityValue.CREATE_NEW_CHAT,
        AuthorityValue.USER_DEFAULT,
        AuthorityValue.USER_ADMIN,
        AuthorityValue.START_CHAT,
        AuthorityValue.STOP_CHAT,
        AuthorityValue.UPDATE_CHAT,
        AuthorityValue.VIEW_AGENCY_CONSULTANTS
      })
  void getAppointmentsShouldReturnForbiddenWhenNoConsultantAuthority() throws Exception {
    mvc.perform(
            get("/appointments")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }

  @Test
  void postAppointmentsShouldReturnForbiddenWhenNoCsrfTokens() throws Exception {
    mvc.perform(
            post("/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }

  @Test
  void postAppointmentsShouldReturnUnauthorizedWhenNoKeycloakAuthorization() throws Exception {
    mvc.perform(
            post("/appointments")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(
      authorities = {
        AuthorityValue.ANONYMOUS_DEFAULT,
        AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION,
        AuthorityValue.ASSIGN_CONSULTANT_TO_ENQUIRY,
        AuthorityValue.CREATE_NEW_CHAT,
        AuthorityValue.USER_DEFAULT,
        AuthorityValue.USER_ADMIN,
        AuthorityValue.START_CHAT,
        AuthorityValue.STOP_CHAT,
        AuthorityValue.UPDATE_CHAT,
        AuthorityValue.VIEW_AGENCY_CONSULTANTS
      })
  void postAppointmentsShouldReturnForbiddenWhenNoConsultantAuthority() throws Exception {
    mvc.perform(
            post("/appointments")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }

  private void givenAValidAppointment() {
    appointment = easyRandom.nextObject(Appointment.class);

    var desc = appointment.getDescription();
    if (desc.length() > 300) {
      appointment.setDescription(desc.substring(0, 300));
    }
  }
}
