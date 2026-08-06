package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.AdminDtoMapper;
import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.report.service.ViolationReportGenerator;
import de.caritas.cob.userservice.api.admin.service.consultant.create.GrantConsultantIdentityService;
import de.caritas.cob.userservice.api.admin.service.session.SessionAdminService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.identity.UserIdentitiesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone routing check (no DB/Keycloak): verifies that PUT
 * /useradmin/consultants/{consultantId} and PUT /service/useradmin/consultants/{consultantId} are
 * both mapped and return 200, not 404/405.
 */
class UserAdminControllerPostUpdateRoutingTest {

  private static final String CONSULTANT_ID = "6205491b-042e-484b-b941-0910ae011da3";
  private static final String VALID_BODY =
      "{\"firstname\":\"Avram\",\"lastname\":\"Mayo\","
          + "\"email\":\"a@b.com\",\"formalLanguage\":true,\"absent\":false,"
          + "\"topicIds\":[1,2,3]}";

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsultantAdminFacade consultantAdminFacade = mock(ConsultantAdminFacade.class);
    when(consultantAdminFacade.updateConsultant(anyString(), any()))
        .thenReturn(new ConsultantAdminResponseDTO());

    var controller =
        new UserAdminController(
            mock(SessionAdminService.class),
            mock(ViolationReportGenerator.class),
            consultantAdminFacade,
            mock(AskerUserAdminFacade.class),
            mock(AdminUserFacade.class),
            mock(AppointmentService.class),
            mock(AdminDtoMapper.class),
            mock(AuthenticatedUser.class),
            mock(GrantConsultantIdentityService.class),
            mock(UserIdentitiesService.class));

    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void putUpdate_directPath_returns200() throws Exception {
    mockMvc
        .perform(
            put("/useradmin/consultants/{id}", CONSULTANT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept("application/hal+json")
                .content(VALID_BODY))
        .andExpect(status().isOk());
  }

  @Test
  void putUpdate_servicePrefix_returns200() throws Exception {
    mockMvc
        .perform(
            put("/service/useradmin/consultants/{id}", CONSULTANT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept("application/hal+json")
                .content(VALID_BODY))
        .andExpect(status().isOk());
  }
}
