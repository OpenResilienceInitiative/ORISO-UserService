package de.caritas.cob.userservice.api.admin.service.admin.create;

import static de.caritas.cob.userservice.api.config.auth.UserRole.AGENCY_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.UserRole.SINGLE_TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.UserRole.TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.UserRole.TOPIC_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.UserRole.USER_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import java.util.List;
import javax.ws.rs.NotFoundException;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CreateAdminServiceTest {

  @InjectMocks private CreateAdminService createAdminService;

  @Mock private IdentityClient identityClient;

  @Mock private UserAccountInputValidator userAccountInputValidator;

  @Mock private UserHelper userHelper;

  @Mock private AdminRepository adminRepository;

  @Mock private AuthenticatedUser authenticatedUser;

  private final EasyRandom easyRandom = new EasyRandom();

  @Test
  void getDefaultRoles_Should_NotAssignLegacySingleTenantAdmin_ForSingleDomainTenantAdmin() {
    ReflectionTestUtils.setField(createAdminService, "multitenancyWithSingleDomain", true);

    List<UserRole> defaultRoles = createAdminService.getDefaultRoles(Admin.AdminType.TENANT);

    assertThat(defaultRoles).containsOnly(USER_ADMIN, AGENCY_ADMIN, TENANT_ADMIN);
    assertThat(defaultRoles).doesNotContain(SINGLE_TENANT_ADMIN);
  }

  @Test
  void getDefaultRoles_Should_NotAssignLegacySingleTenantAdmin_ForMultidomainTenantAdmin() {
    ReflectionTestUtils.setField(createAdminService, "multitenancyWithSingleDomain", false);

    List<UserRole> defaultRoles = createAdminService.getDefaultRoles(Admin.AdminType.TENANT);

    assertThat(defaultRoles).containsOnly(USER_ADMIN, AGENCY_ADMIN, TENANT_ADMIN, TOPIC_ADMIN);
    assertThat(defaultRoles).doesNotContain(SINGLE_TENANT_ADMIN);
  }

  @Test
  void createNewAgencyAdmin_ShouldRollbackUser_WhenRoleAssignmentFails() {
    KeycloakCreateUserResponseDTO keycloakResponse = new KeycloakCreateUserResponseDTO();
    keycloakResponse.setUserId("kc-user-id");
    when(identityClient.createKeycloakUser(any(), anyString(), anyString()))
        .thenReturn(keycloakResponse);
    doThrow(new RuntimeException("role assignment failed"))
        .when(identityClient)
        .updateRole(anyString(), any(UserRole.class));

    CreateAdminDTO createAdminDTO = easyRandom.nextObject(CreateAdminDTO.class);
    createAdminDTO.setUsername("valid_username");
    createAdminDTO.setEmail("valid@email.com");

    assertThrows(
        InternalServerErrorException.class,
        () -> createAdminService.createNewAgencyAdmin(createAdminDTO));

    verify(identityClient).rollBackUser("kc-user-id");
  }

  @Test
  void createNewAgencyAdmin_ShouldThrowRoleNotFoundReason_AndRollbackUser_WhenRealmRoleIsMissing() {
    KeycloakCreateUserResponseDTO keycloakResponse = new KeycloakCreateUserResponseDTO();
    keycloakResponse.setUserId("kc-user-id");
    when(identityClient.createKeycloakUser(any(), anyString(), anyString()))
        .thenReturn(keycloakResponse);
    doThrow(new NotFoundException("HTTP 404 Not Found"))
        .when(identityClient)
        .updateRole(anyString(), any(UserRole.class));

    CreateAdminDTO createAdminDTO = easyRandom.nextObject(CreateAdminDTO.class);
    createAdminDTO.setUsername("valid_username");
    createAdminDTO.setEmail("valid@email.com");

    CustomValidationHttpStatusException exception =
        assertThrows(
            CustomValidationHttpStatusException.class,
            () -> createAdminService.createNewAgencyAdmin(createAdminDTO));

    assertThat(exception.getCustomHttpHeaders().getFirst("X-Reason"))
        .isEqualTo(HttpStatusExceptionReason.ROLE_NOT_FOUND.name());
    verify(identityClient).rollBackUser("kc-user-id");
  }
}
