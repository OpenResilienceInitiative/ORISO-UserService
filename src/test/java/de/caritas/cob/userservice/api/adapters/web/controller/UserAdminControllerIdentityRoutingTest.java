package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserIdentitiesDTO;
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
 * Standalone routing check (no DB/Keycloak): verifies that the grant-consultant-identity and the
 * user-identities endpoints (both the direct and the {@code /service}-prefixed variants) are mapped
 * and return 200, not 404/405.
 */
class UserAdminControllerIdentityRoutingTest {

  private static final String USER_ID = "6205491b-042e-484b-b941-0910ae011da3";
  private static final String GRANT_BODY =
      "{\"groupchatConsultant\":false,\"absent\":false,\"formalLanguage\":true,"
          + "\"agencyIds\":[1],\"topicIds\":[1,2]}";

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    GrantConsultantIdentityService grantConsultantIdentityService =
        mock(GrantConsultantIdentityService.class);
    when(grantConsultantIdentityService.grantConsultantIdentityToAdmin(anyString(), any()))
        .thenReturn(new ConsultantAdminResponseDTO());

    UserIdentitiesService userIdentitiesService = mock(UserIdentitiesService.class);
    when(userIdentitiesService.getUserIdentities(anyString())).thenReturn(new UserIdentitiesDTO());

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
            grantConsultantIdentityService,
            userIdentitiesService);

    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void grantConsultantIdentity_directPath_returns200() throws Exception {
    mockMvc
        .perform(
            post("/useradmin/admins/{adminId}/grant-consultant-identity", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept("application/hal+json")
                .content(GRANT_BODY))
        .andExpect(status().isOk());
  }

  @Test
  void grantConsultantIdentity_servicePrefix_returns200() throws Exception {
    mockMvc
        .perform(
            post("/service/useradmin/admins/{adminId}/grant-consultant-identity", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept("application/hal+json")
                .content(GRANT_BODY))
        .andExpect(status().isOk());
  }

  @Test
  void getUserIdentities_directPath_returns200() throws Exception {
    mockMvc
        .perform(get("/useradmin/users/{userId}/identities", USER_ID))
        .andExpect(status().isOk());
  }

  @Test
  void getUserIdentities_servicePrefix_returns200() throws Exception {
    mockMvc
        .perform(get("/service/useradmin/users/{userId}/identities", USER_ID))
        .andExpect(status().isOk());
  }
}
