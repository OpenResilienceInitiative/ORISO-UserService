package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AdminFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.AdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AdminSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyConsultantResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyTypeDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminAgencyRelationDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.DeletionPauseRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GrantConsultantIdentityDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionAdminResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAgencyAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserIdentitiesDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ViolationDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.AdminDtoMapper;
import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.report.service.ViolationReportGenerator;
import de.caritas.cob.userservice.api.admin.service.consultant.create.GrantConsultantIdentityService;
import de.caritas.cob.userservice.api.admin.service.session.SessionAdminService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.identity.UserIdentitiesService;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
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
  @Mock private AdminDtoMapper adminDtoMapper;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private GrantConsultantIdentityService grantConsultantIdentityService;
  @Mock private UserIdentitiesService userIdentitiesService;

  private UserAdminController controller;

  @BeforeEach
  void setUp() {
    controller =
        new UserAdminController(
            new UserAdminQueryControllerDelegate(sessionAdminService, violationReportGenerator),
            new UserAdminConsultantControllerDelegate(
                consultantAdminFacade,
                authenticatedUser,
                grantConsultantIdentityService,
                userIdentitiesService),
            new UserAdminAskerControllerDelegate(askerUserAdminFacade, authenticatedUser),
            new UserAdminAccountControllerDelegate(adminUserFacade, adminDtoMapper));
  }

  @Test
  void createTenantAdmin_emailIsLowercased_beforeDelegation() {
    // Business reason: admin account e-mails must be normalized to avoid duplicate identities by
    // case, independently of the host JVM locale.
    var dto = new CreateAdminDTO();
    dto.setEmail("IDENTITY@EXAMPLE.ORG");
    when(adminUserFacade.createNewTenantAdmin(any())).thenReturn(new AdminResponseDTO());

    var response = withTurkishDefaultLocale(() -> controller.createTenantAdmin(dto));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    var captor = ArgumentCaptor.forClass(CreateAdminDTO.class);
    verify(adminUserFacade).createNewTenantAdmin(captor.capture());
    assertEquals("identity@example.org", captor.getValue().getEmail());
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
  void getRoot_Should_returnRootDto() {
    var response = controller.getRoot();
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
  }

  @Test
  void getSessions_Should_delegateToSessionAdminService() {
    var expected = new SessionAdminResultDTO();
    when(sessionAdminService.findSessions(1, 10, null)).thenReturn(expected);

    var response = controller.getSessions(1, 10, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expected, response.getBody());
  }

  @Test
  void getSessions_Should_passFilterThrough() {
    var filter = new SessionFilter();
    var expected = new SessionAdminResultDTO();
    when(sessionAdminService.findSessions(2, 20, filter)).thenReturn(expected);

    controller.getSessions(2, 20, filter);

    verify(sessionAdminService).findSessions(2, 20, filter);
  }

  @Test
  void createConsultant_emailIsLowercased_beforeDelegation() {
    var dto = new CreateConsultantDTO();
    dto.setEmail("IDENTITY@EXAMPLE.ORG");
    dto.setUsername("user");
    when(consultantAdminFacade.createNewConsultant(any()))
        .thenReturn(new ConsultantAdminResponseDTO());

    var response = withTurkishDefaultLocale(() -> controller.createConsultant(dto));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    var captor = ArgumentCaptor.forClass(CreateConsultantDTO.class);
    verify(consultantAdminFacade).createNewConsultant(captor.capture());
    assertEquals("identity@example.org", captor.getValue().getEmail());
  }

  @Test
  void grantConsultantIdentity_Should_delegate() {
    var dto = new GrantConsultantIdentityDTO();
    var expected = new ConsultantAdminResponseDTO();
    when(grantConsultantIdentityService.grantConsultantIdentityToAdmin("a-1", dto))
        .thenReturn(expected);

    var response = controller.grantConsultantIdentity("a-1", dto);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expected, response.getBody());
  }

  @Test
  void getUserIdentities_Should_delegate() {
    var expected = new UserIdentitiesDTO();
    when(userIdentitiesService.getUserIdentities("u-1")).thenReturn(expected);

    var response = controller.getUserIdentities("u-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expected, response.getBody());
  }

  @Test
  void generateViolationReport_Should_returnList() {
    when(violationReportGenerator.generateReport()).thenReturn(List.of(new ViolationDTO()));

    var response = controller.generateViolationReport();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(1, response.getBody().size());
  }

  @Test
  void createConsultantAgency_Should_returnCreatedAndDelegate() {
    var dto = new CreateConsultantAgencyDTO();

    var response = controller.createConsultantAgency("c-1", dto);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    verify(consultantAdminFacade).checkPermissionsToAssignedAgencies(any());
    verify(consultantAdminFacade).createNewConsultantAgency("c-1", dto);
  }

  @Test
  void setConsultantAgencies_Should_returnOkAndDelegate() {
    var list = List.of(new CreateConsultantAgencyDTO());

    var response = controller.setConsultantAgencies("c-1", list);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(consultantAdminFacade).checkPermissionsToAssignedAgencies(list);
    verify(consultantAdminFacade).setConsultantAgencies("c-1", list);
  }

  @Test
  void deleteConsultantAgency_Should_delegate() {
    var response = controller.deleteConsultantAgency("c-1", 42L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(consultantAdminFacade).markConsultantAgencyForDeletion("c-1", 42L);
  }

  @Test
  void markConsultantForDeletion_Should_delegate() {
    var response = controller.markConsultantForDeletion("c-1", true);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(consultantAdminFacade).markConsultantForDeletion("c-1", true);
  }

  @Test
  void pauseConsultantDeletion_Should_delegateWithAuthenticatedUser() {
    var request = new DeletionPauseRequestDTO();
    request.setReason("legal");
    request.setMonths(3);
    when(authenticatedUser.getUserId()).thenReturn("admin-1");

    var response = controller.pauseConsultantDeletion("c-1", request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(consultantAdminFacade).pauseConsultantDeletion("c-1", "legal", 3, "admin-1");
  }

  @Test
  void updateConsultant_emailIsLowercased_beforeDelegation() {
    var dto = new UpdateAdminConsultantDTO();
    dto.setEmail("IDENTITY@EXAMPLE.ORG");
    when(consultantAdminFacade.updateConsultant(eq("c-1"), any()))
        .thenReturn(new ConsultantAdminResponseDTO());

    var response = withTurkishDefaultLocale(() -> controller.updateConsultant("c-1", dto));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    var captor = ArgumentCaptor.forClass(UpdateAdminConsultantDTO.class);
    verify(consultantAdminFacade).updateConsultant(eq("c-1"), captor.capture());
    assertEquals("identity@example.org", captor.getValue().getEmail());
  }

  @Test
  void updateConsultant_Should_leaveNullEmailIntact() {
    var dto = new UpdateAdminConsultantDTO();
    dto.setEmail(null);
    when(consultantAdminFacade.updateConsultant(eq("c-2"), any()))
        .thenReturn(new ConsultantAdminResponseDTO());

    var response = controller.updateConsultant("c-2", dto);

    assertEquals(HttpStatus.OK, response.getStatusCode());
  }

  @Test
  void getConsultant_Should_delegate() {
    var expected = new ConsultantAdminResponseDTO();
    when(consultantAdminFacade.findConsultant("c-1")).thenReturn(expected);

    var response = controller.getConsultant("c-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expected, response.getBody());
  }

  @Test
  void getConsultants_Should_delegate() {
    var filter = new ConsultantFilter();
    var sort = new Sort();
    var expected = new ConsultantSearchResultDTO();
    when(consultantAdminFacade.findFilteredConsultants(1, 20, filter, sort)).thenReturn(expected);

    var response = controller.getConsultants(1, 20, filter, sort);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expected, response.getBody());
  }

  @Test
  void getAgencyConsultants_Should_delegate() {
    var expected = new AgencyConsultantResponseDTO();
    when(consultantAdminFacade.findConsultantsForAgency("42")).thenReturn(expected);

    var response = controller.getAgencyConsultants("42");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expected, response.getBody());
  }

  @Test
  void getConsultantAgencies_Should_delegate() {
    var expected = new ConsultantAgencyResponseDTO();
    when(consultantAdminFacade.findConsultantAgencies("c-1")).thenReturn(expected);

    var response = controller.getConsultantAgencies("c-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expected, response.getBody());
  }

  @Test
  void changeAgencyType_Should_delegate() {
    var dto = new AgencyTypeDTO();

    var response = controller.changeAgencyType(42L, dto);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(consultantAdminFacade).changeAgencyType(42L, dto);
  }

  @Test
  void markAskerForDeletion_Should_delegate() {
    var response = controller.markAskerForDeletion("asker-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(askerUserAdminFacade).markAskerForDeletion("asker-1");
  }

  @Test
  void pauseAskerDeletion_Should_delegateWithAuthenticatedUser() {
    var request = new DeletionPauseRequestDTO();
    request.setReason("hold");
    request.setMonths(1);
    when(authenticatedUser.getUserId()).thenReturn("admin-1");

    var response = controller.pauseAskerDeletion("a-1", request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(askerUserAdminFacade).pauseAskerDeletion("a-1", "hold", 1, "admin-1");
  }

  @Test
  void getAsker_Should_delegate() {
    var expected = new AskerResponseDTO();
    when(askerUserAdminFacade.getAsker("a-1")).thenReturn(expected);

    var response = controller.getAsker("a-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expected, response.getBody());
  }

  @Test
  void createAgencyAdmin_Should_delegate() {
    var dto = new CreateAdminDTO();
    dto.setEmail("IDENTITY@EXAMPLE.ORG");
    var expected = new AdminResponseDTO();
    when(adminUserFacade.createNewAgencyAdmin(any())).thenReturn(expected);

    var response = withTurkishDefaultLocale(() -> controller.createAgencyAdmin(dto));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expected, response.getBody());
    var captor = ArgumentCaptor.forClass(CreateAdminDTO.class);
    verify(adminUserFacade).createNewAgencyAdmin(captor.capture());
    assertEquals("identity@example.org", captor.getValue().getEmail());
  }

  @Test
  void getAgencyAdmin_Should_delegate() {
    var expected = new AdminResponseDTO();
    when(adminUserFacade.findAgencyAdmin("admin-1")).thenReturn(expected);

    var response = controller.getAgencyAdmin("admin-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expected, response.getBody());
  }

  @Test
  void getTenantAdmin_Should_delegate() {
    var expected = new AdminResponseDTO();
    when(adminUserFacade.findTenantAdmin("t-1")).thenReturn(expected);

    var response = controller.getTenantAdmin("t-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expected, response.getBody());
  }

  @Test
  void getTenantAdmins_Should_delegate() {
    var expected = List.of(new AdminResponseDTO());
    when(adminUserFacade.findTenantAdmins(7)).thenReturn(expected);

    var response = controller.getTenantAdmins(7);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expected, response.getBody());
  }

  @Test
  void getAdminAgencies_Should_delegate() {
    when(adminUserFacade.findAdminUserAgencyIds("admin-1")).thenReturn(List.of(1L, 2L));

    var response = controller.getAdminAgencies("admin-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(List.of(1L, 2L), response.getBody());
  }

  @Test
  void getAgencyAdmins_Should_delegate() {
    var filter = new AdminFilter();
    var sort = new Sort();
    var expected = new AdminSearchResultDTO();
    when(adminUserFacade.findFilteredAdminsAgency(1, 20, filter, sort)).thenReturn(expected);

    var response = controller.getAgencyAdmins(1, 20, filter, sort);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expected, response.getBody());
  }

  @Test
  void deleteAgencyAdmin_Should_delegate() {
    var response = controller.deleteAgencyAdmin("admin-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(adminUserFacade).deleteAgencyAdmin("admin-1");
  }

  @Test
  void deleteTenantAdmin_Should_delegate() {
    var response = controller.deleteTenantAdmin("admin-t");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(adminUserFacade).deleteTenantAdmin("admin-t");
  }

  @Test
  void createAdminAgencyRelation_Should_returnCreated() {
    var dto = new CreateAdminAgencyRelationDTO();

    var response = controller.createAdminAgencyRelation("admin-1", dto);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    verify(adminUserFacade).createNewAdminAgencyRelation("admin-1", dto);
  }

  @Test
  void deleteAdminAgencyRelation_Should_delegate() {
    var response = controller.deleteAdminAgencyRelation("admin-1", 42L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(adminUserFacade).deleteAdminAgencyRelation("admin-1", 42L);
  }

  @Test
  void setAdminAgenciesRelation_Should_delegate() {
    var list = List.of(new CreateAdminAgencyRelationDTO());

    var response = controller.setAdminAgenciesRelation("admin-1", list);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(adminUserFacade).setAdminAgenciesRelation("admin-1", list);
  }

  @Test
  void patchAdminData_Should_delegate() {
    var dto = new PatchAdminDTO();
    var expected = new AdminResponseDTO();
    when(adminUserFacade.patchAdminUserData(dto)).thenReturn(expected);

    var response = controller.patchAdminData(dto);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expected, response.getBody());
  }

  @Test
  void searchTenantAdmins_Should_delegate() {
    when(adminDtoMapper.mappedFieldOf("email")).thenReturn("email");
    when(adminUserFacade.findTenantAdminsByInfix("jane", 0, 20, "email", false))
        .thenReturn(Map.of());
    when(adminDtoMapper.adminSearchResultOf(any(), any(), any(), any(), any(), any()))
        .thenReturn(new AdminSearchResultDTO());

    var response = controller.searchTenantAdmins("jane", 1, 20, "email", "desc");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(adminUserFacade).findTenantAdminsByInfix("jane", 0, 20, "email", false);
  }

  @Test
  void searchAgencyAdmins_Should_urlDecodeNonEmailQuery() {
    String encoded = URLEncoder.encode("hello world", StandardCharsets.UTF_8);
    when(adminDtoMapper.mappedFieldOf("name")).thenReturn("name");
    when(adminUserFacade.findAgencyAdminsByInfix("hello world", 0, 10, "name", true))
        .thenReturn(Map.of());
    when(adminDtoMapper.adminSearchResultOf(any(), any(), any(), any(), any(), any()))
        .thenReturn(new AdminSearchResultDTO());

    var response = controller.searchAgencyAdmins(encoded, 1, 10, "name", "asc");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(adminUserFacade).findAgencyAdminsByInfix("hello world", 0, 10, "name", true);
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

  private <T> T withTurkishDefaultLocale(Supplier<T> action) {
    Locale originalLocale = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      return action.get();
    } finally {
      Locale.setDefault(originalLocale);
    }
  }
}
