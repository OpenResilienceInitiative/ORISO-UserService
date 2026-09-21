package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.service.accountinvite.AdminSelfAssignmentService;
import de.caritas.cob.userservice.api.service.accountinvite.AdminSelfAssignmentService.SelfAssignmentCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AdminSelfAssignmentService.SelfAssignmentResult;
import de.caritas.cob.userservice.api.service.accountinvite.AdminSelfAssignmentService.SelfAssignmentRole;
import de.caritas.cob.userservice.api.service.accountinvite.AdminSelfAssignmentService.SelfAssignments;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP contract of the self-assignment endpoint (ORISO-Admin#1026, slice 3): admin authorities
 * only, the request shape, the 201 answer and the input errors. The rules themselves are covered by
 * {@code AdminSelfAssignmentIT}.
 */
@TestPropertySource(properties = "spring.profiles.active=testing")
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = Replace.NONE)
class AdminSelfAssignmentControllerIT {

  private static final String PATH = "/useradmin/self-assignments";
  private static final String CSRF_HEADER = "X-CSRF-TOKEN";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  @Autowired private MockMvc mvc;

  @MockitoBean private AdminSelfAssignmentService selfAssignmentService;

  @Test
  void assign_Should_Answer401_When_Anonymous() throws Exception {
    mvc.perform(
            post(PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"COUNSELLOR\",\"agencyId\":1}"))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(selfAssignmentService);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.CONSULTANT_DEFAULT})
  void assign_Should_Answer403_When_TheCallerIsNoAdmin() throws Exception {
    mvc.perform(
            post(PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"COUNSELLOR\",\"agencyId\":1}"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(selfAssignmentService);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void assign_Should_Answer201_WithTheAssignment() throws Exception {
    when(selfAssignmentService.assign(any()))
        .thenReturn(new SelfAssignmentResult(SelfAssignmentRole.COUNSELLOR, 7L, "admin-id", true));

    mvc.perform(
            post(PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"COUNSELLOR\",\"agencyId\":7,\"topicIds\":[3]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role").value("COUNSELLOR"))
        .andExpect(jsonPath("$.agencyId").value(7))
        .andExpect(jsonPath("$.userId").value("admin-id"))
        .andExpect(jsonPath("$.consultantIdentityCreated").value(true));

    ArgumentCaptor<SelfAssignmentCommand> command =
        ArgumentCaptor.forClass(SelfAssignmentCommand.class);
    verify(selfAssignmentService).assign(command.capture());
    assertThat(command.getValue())
        .isEqualTo(new SelfAssignmentCommand(SelfAssignmentRole.COUNSELLOR, 7L, List.of(3L)));
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void assign_Should_Answer400_When_TheRoleIsUnknown() throws Exception {
    mvc.perform(
            post(PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"TENANT_ADMIN\",\"agencyId\":7}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(selfAssignmentService);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void current_Should_ListTheCallersAssignments() throws Exception {
    when(selfAssignmentService.current())
        .thenReturn(new SelfAssignments(List.of(2L), List.of(1L, 2L)));

    mvc.perform(get(PATH).cookie(CSRF_COOKIE).header(CSRF_HEADER, CSRF_VALUE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agencyAdminAgencyIds[0]").value(2))
        .andExpect(jsonPath("$.counsellorAgencyIds.length()").value(2));
  }
}
