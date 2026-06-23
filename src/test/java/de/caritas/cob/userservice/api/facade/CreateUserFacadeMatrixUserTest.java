package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.testHelper.KeycloakConstants.KEYCLOAK_CREATE_USER_RESPONSE_DTO_WITH_USER_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_SETTINGS_KREUZBUND;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_DTO_KREUZBUND;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_ID;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.helper.AgencyVerifier;
import de.caritas.cob.userservice.api.helper.PlainCredentialsHolder;
import de.caritas.cob.userservice.api.helper.UserVerifier;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class CreateUserFacadeMatrixUserTest {

  @InjectMocks private CreateUserFacade createUserFacade;

  @Mock private UserVerifier userVerifier;
  @Mock private IdentityClient identityClient;
  @Mock private UserService userService;
  @Mock private RollbackFacade rollbackFacade;
  @Mock private ConsultingTypeManager consultingTypeManager;
  @Mock private AgencyVerifier agencyVerifier;
  @Mock private CreateNewSessionFacade createNewSessionFacade;
  @Mock private CreateSessionFacade createSessionFacade;
  @Mock private StatisticsService statisticsService;
  @Mock private TopicService topicService;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private TenantService tenantService;
  @Mock private AgencyService agencyService;

  @AfterEach
  void tearDown() {
    PlainCredentialsHolder.clear();
    TenantContext.clear();
  }

  @Test
  void createUserAccountWithInitializedConsultingType_Should_CreateMatrixUserWithGeneratedPassword()
      throws Exception {
    var plainUsername = "plain-user";
    var matrixUserId = "@plain-user:matrix";
    PlainCredentialsHolder.set(plainUsername, null);

    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createKeycloakUser(any()))
        .thenReturn(KEYCLOAK_CREATE_USER_RESPONSE_DTO_WITH_USER_ID);

    var createdUser =
        new User(
            USER_ID, null, USER_DTO_KREUZBUND.getUsername(), USER_DTO_KREUZBUND.getEmail(), false);
    when(userService.createUser(anyString(), any(), anyString(), anyString(), anyBoolean(), any()))
        .thenReturn(createdUser);
    when(userService.saveUser(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    var matrixResponse = new MatrixCreateUserResponseDTO();
    matrixResponse.setUserId(matrixUserId);
    when(matrixSynapseService.createUser(eq(plainUsername), anyString(), eq(plainUsername)))
        .thenReturn(ResponseEntity.ok(matrixResponse));

    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenReturn(new NewRegistrationResponseDto().sessionId(1L).status(HttpStatus.CREATED));
    when(agencyService.getAgencyWithoutCaching(Mockito.anyLong())).thenReturn(new AgencyDTO());

    createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_KREUZBUND);

    ArgumentCaptor<String> matrixPasswordCaptor = ArgumentCaptor.forClass(String.class);
    verify(matrixSynapseService)
        .createUser(eq(plainUsername), matrixPasswordCaptor.capture(), eq(plainUsername));
    assertFalse(matrixPasswordCaptor.getValue().isBlank());
    assertNotEquals(USER_DTO_KREUZBUND.getPassword(), matrixPasswordCaptor.getValue());
    assertThat(createdUser.getMatrixUserId(), is(matrixUserId));
    assertNull(PlainCredentialsHolder.get());
  }
}
