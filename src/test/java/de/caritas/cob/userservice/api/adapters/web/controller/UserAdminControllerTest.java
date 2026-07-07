package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AdminSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.DeletionPauseRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAgencyAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantAdminDTO;
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
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;

@ExtendWith(MockitoExtension.class)
class UserAdminControllerTest {

  @Mock private SessionAdminService sessionAdminService;
  @Mock private ViolationReportGenerator violationReportGenerator;
  @Mock private ConsultantAdminFacade consultantAdminFacade;
  @Mock private AskerUserAdminFacade askerUserAdminFacade;
  @Mock private AdminUserFacade adminUserFacade;
  @Mock private AppointmentService appointmentService;
  @Mock private AdminDtoMapper adminDtoMapper;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private GrantConsultantIdentityService grantConsultantIdentityService;
  @Mock private UserIdentitiesService userIdentitiesService;

  private UserAdminController controller;

  @BeforeEach
  void setUp() {
    controller =
        new UserAdminController(
            sessionAdminService,
            violationReportGenerator,
            consultantAdminFacade,
            askerUserAdminFacade,
            adminUserFacade,
            appointmentService,
            adminDtoMapper,
            authenticatedUser,
            grantConsultantIdentityService,
            userIdentitiesService);
  }

  @Test
  void createTenantAdmin_emailIsLowercased_beforeDelegation() {
    // Business reason: admin account e-mails must be normalized to avoid duplicate identities by
    // case.
    var dto = new CreateAdminDTO();
    dto.setEmail("UPPER@EXAMPLE.ORG");
    when(adminUserFacade.createNewTenantAdmin(any())).thenReturn(new AdminResponseDTO());

    var response = controller.createTenantAdmin(dto);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    var captor = ArgumentCaptor.forClass(CreateAdminDTO.class);
    verify(adminUserFacade).createNewTenantAdmin(captor.capture());
    assertEquals("upper@example.org", captor.getValue().getEmail());
  }

  @Test
  void updateAgencyAdmin_emailIsLowercased_beforeDelegation() {
    // Business reason: updates must keep canonical e-mail format for stable identity lookups.
    var dto = new UpdateAgencyAdminDTO();
    dto.setEmail("AGENCY@EXAMPLE.ORG");
    when(adminUserFacade.updateAgencyAdmin(eq("admin-1"), any()))
        .thenReturn(new AdminResponseDTO());

    var response = controller.updateAgencyAdmin("admin-1", dto);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    var captor = ArgumentCaptor.forClass(UpdateAgencyAdminDTO.class);
    verify(adminUserFacade).updateAgencyAdmin(eq("admin-1"), captor.capture());
    assertEquals("agency@example.org", captor.getValue().getEmail());
  }

  @Test
  void updateTenantAdmin_emailIsLowercased_beforeDelegation() {
    // Business reason: tenant-admin updates should preserve consistent e-mail matching semantics.
    var dto = new UpdateTenantAdminDTO();
    dto.setEmail("TENANT@EXAMPLE.ORG");
    when(adminUserFacade.updateTenantAdmin(eq("admin-2"), any()))
        .thenReturn(new AdminResponseDTO());

    var response = controller.updateTenantAdmin("admin-2", dto);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    var captor = ArgumentCaptor.forClass(UpdateTenantAdminDTO.class);
    verify(adminUserFacade).updateTenantAdmin(eq("admin-2"), captor.capture());
    assertEquals("tenant@example.org", captor.getValue().getEmail());
  }

  @Test
  void searchAgencyAdmins_mapsSortAndPageBeforeDelegation() {
    // Business reason: admin search must pass normalized paging and sorting to repository layer.
    when(adminDtoMapper.mappedFieldOf("email")).thenReturn("email");
    when(adminUserFacade.findAgencyAdminsByInfix("john", 1, 20, "email", true))
        .thenReturn(Map.of());
    when(adminDtoMapper.adminSearchResultOf(any(), any(), any(), any(), any(), any()))
        .thenReturn(new AdminSearchResultDTO());

    var response = controller.searchAgencyAdmins("john", 2, 20, "email", "asc");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(adminUserFacade).findAgencyAdminsByInfix("john", 1, 20, "email", true);
  }

  @Test
  void classHasValidatedAnnotation_forBeanValidationEntryPoints() {
    // Business reason: validation interceptor must stay active for controller-level input
    // constraints.
    assertNotNull(UserAdminController.class.getAnnotation(Validated.class));
  }

  @Test
  void pauseDeletionMethods_requireValidRequestBody() throws Exception {
    // Business reason: deletion pause APIs must keep mandatory payload checks at the method
    // boundary.
    Method consultantPause =
        UserAdminController.class.getMethod(
            "pauseConsultantDeletion", String.class, DeletionPauseRequestDTO.class);
    Method askerPause =
        UserAdminController.class.getMethod(
            "pauseAskerDeletion", String.class, DeletionPauseRequestDTO.class);

    assertEquals(
        "jakarta.validation.Valid",
        consultantPause.getParameters()[1].getAnnotations()[0].annotationType().getName());
    assertEquals(
        "jakarta.validation.Valid",
        askerPause.getParameters()[1].getAnnotations()[0].annotationType().getName());
  }
}
