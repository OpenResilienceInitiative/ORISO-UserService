package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.mapping.AdminDtoMapper;
import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.report.service.ViolationReportGenerator;
import de.caritas.cob.userservice.api.admin.service.session.SessionAdminService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone routing check (no DB/Keycloak/Mongo): proves POST /useradmin/consultants/update is
 * mapped and not shadowed by the /useradmin/consultants/{consultantId} routes. A 405 here would
 * mean the route is missing in the build; 200 means routing is correct.
 */
class UserAdminControllerPostUpdateRoutingTest {

  @Test
  void postUpdate_isRouted_notMethodNotAllowed() throws Exception {
    var controller =
        new UserAdminController(
            mock(SessionAdminService.class),
            mock(ViolationReportGenerator.class),
            mock(ConsultantAdminFacade.class),
            mock(AskerUserAdminFacade.class),
            mock(AdminUserFacade.class),
            mock(AppointmentService.class),
            mock(AdminDtoMapper.class),
            mock(AuthenticatedUser.class),
            new ObjectMapper());

    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    mockMvc
        .perform(
            post("/useradmin/consultants/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"id\":\"abc\",\"firstname\":\"A\",\"lastname\":\"B\","
                        + "\"email\":\"a@b.c\",\"formalLanguage\":true,\"absent\":false}"))
        .andExpect(status().isOk());
  }
}
