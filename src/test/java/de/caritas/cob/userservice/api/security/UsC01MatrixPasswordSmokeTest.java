package de.caritas.cob.userservice.api.security;

import static de.caritas.cob.userservice.api.testHelper.KeycloakConstants.KEYCLOAK_CREATE_USER_RESPONSE_DTO_WITH_USER_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_SETTINGS_KREUZBUND;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.PASSWORD;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.SESSION_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_DTO_KREUZBUND;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_ID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.controller.MatrixMessageController;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.facade.CreateNewSessionFacade;
import de.caritas.cob.userservice.api.facade.CreateSessionFacade;
import de.caritas.cob.userservice.api.facade.CreateUserFacade;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.helper.AgencyVerifier;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.PlainCredentialsHolder;
import de.caritas.cob.userservice.api.helper.UserVerifier;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.matrix.RedisMessageMirrorService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

/**
 * US-C01 smoke tests from the security findings index.
 *
 * <p>Verifies that user/consultant Matrix passwords are not persisted and that Matrix messaging
 * uses Synapse admin impersonation instead.
 */
class UsC01MatrixPasswordSmokeTest {

  private static final String REGISTRATION_PASSWORD = "TestPw123!";
  private static final String MATRIX_USER_ID = "@seeker:matrix.example.com";
  private static final String MATRIX_ROOM_ID = "!room:matrix.example.com";

  static boolean declaresMatrixPasswordField(Class<?> entityClass) {
    return Arrays.stream(entityClass.getDeclaredFields())
        .anyMatch(field -> "matrixPassword".equals(field.getName()));
  }

  @Nested
  @DisplayName("Smoke 1 — no plaintext matrix_password persisted after registration")
  @ExtendWith(MockitoExtension.class)
  class NoPlaintextInDatabaseAfterRegistration {

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
    @Mock private de.caritas.cob.userservice.api.helper.UserHelper userHelper;

    @InjectMocks private CreateUserFacade createUserFacade;

    @BeforeEach
    void setUpRegistrationMocks() throws Exception {
      PlainCredentialsHolder.set("plainuser", REGISTRATION_PASSWORD);

      when(consultingTypeManager.getConsultingTypeSettings(any()))
          .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
      when(identityClient.createKeycloakUser(any()))
          .thenReturn(KEYCLOAK_CREATE_USER_RESPONSE_DTO_WITH_USER_ID);
      doNothing().when(identityClient).updatePassword(anyString(), anyString());
      doNothing().when(identityClient).updateRole(anyString(), any(UserRole.class));
      doNothing().when(userVerifier).checkIfUsernameIsAvailable(any());
      doNothing().when(userVerifier).checkIfAllRequiredAttributesAreCorrectlyFilled(any());
      doNothing().when(agencyVerifier).checkIfConsultingTypeMatchesToAgency(any());

      User persistedUser =
          User.builder()
              .userId(USER_ID)
              .username(USER_DTO_KREUZBUND.getUsername())
              .email(USER_DTO_KREUZBUND.getEmail())
              .matrixUserId(MATRIX_USER_ID)
              .build();

      when(userService.createUser(
              anyString(), any(), anyString(), anyString(), any(Boolean.class), any()))
          .thenReturn(persistedUser);
      when(userService.saveUser(any())).thenAnswer(invocation -> invocation.getArgument(0));

      MatrixCreateUserResponseDTO matrixBody = new MatrixCreateUserResponseDTO();
      matrixBody.setUserId(MATRIX_USER_ID);
      when(matrixSynapseService.createUser(
              eq("plainuser"), eq(REGISTRATION_PASSWORD), eq("plainuser")))
          .thenReturn(ResponseEntity.ok(matrixBody));

      when(createNewSessionFacade.initializeNewSession(
              any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
          .thenReturn(
              new NewRegistrationResponseDto().sessionId(SESSION_ID).status(HttpStatus.CREATED));

      when(tenantService.getRestrictedTenantData(org.mockito.Mockito.anyLong()))
          .thenReturn(new RestrictedTenantDTO());
      when(agencyService.getAgencyWithoutCaching(any())).thenReturn(new AgencyDTO());
      doNothing().when(statisticsService).fireEvent(any());
    }

    @AfterEach
    void tearDown() {
      PlainCredentialsHolder.clear();
    }

    @Test
    @DisplayName(
        "Given POST /users/register password, When user is saved, Then entity has no matrixPassword field")
    void registration_ShouldNotPersistMatrixPasswordOnUserEntity() {
      createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_KREUZBUND);

      ArgumentCaptor<User> savedUserCaptor = ArgumentCaptor.forClass(User.class);
      verify(userService, org.mockito.Mockito.atLeastOnce()).saveUser(savedUserCaptor.capture());

      User savedUser =
          savedUserCaptor.getAllValues().stream()
              .filter(user -> MATRIX_USER_ID.equals(user.getMatrixUserId()))
              .findFirst()
              .orElseThrow();

      assertThat(savedUser.getMatrixUserId(), is(MATRIX_USER_ID));
      assertFalse(
          declaresMatrixPasswordField(User.class),
          "User entity must not declare matrixPassword (US-C01)");
      assertFalse(
          declaresMatrixPasswordField(Consultant.class),
          "Consultant entity must not declare matrixPassword (US-C01)");
    }

    @Test
    @DisplayName(
        "Given registration, When Matrix user is created, Then password is only used transiently")
    void registration_ShouldUsePasswordOnlyForTransientMatrixCreateUser() throws Exception {
      createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_KREUZBUND);

      verify(matrixSynapseService)
          .createUser(eq("plainuser"), eq(REGISTRATION_PASSWORD), eq("plainuser"));
      verify(matrixSynapseService, never()).loginUser(anyString(), eq(REGISTRATION_PASSWORD));
    }
  }

  @Nested
  @DisplayName("Smoke 2 — Matrix chat works without stored plaintext password")
  @WebMvcTest(MatrixMessageController.class)
  @AutoConfigureMockMvc(addFilters = false)
  class MatrixChatWithoutStoredPassword {

    @Autowired private MockMvc mockMvc;

    @MockBean private MatrixSynapseService matrixSynapseService;
    @MockBean private SessionService sessionService;
    @MockBean private de.caritas.cob.userservice.api.service.ChatService chatService;
    @MockBean private AuthenticatedUser authenticatedUser;
    @MockBean private de.caritas.cob.userservice.api.service.ConsultantService consultantService;
    @MockBean private UserService userService;

    @MockBean
    private de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient
        matrixCredentialClient;

    @MockBean private RedisMessageMirrorService redisMessageMirrorService;

    @BeforeEach
    void setUpMatrixMessageFlow() {
      User sessionUser = User.builder().userId(USER_ID).matrixUserId(MATRIX_USER_ID).build();
      Session session =
          Session.builder().id(SESSION_ID).matrixRoomId(MATRIX_ROOM_ID).user(sessionUser).build();

      when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(session));
      when(authenticatedUser.getUserId()).thenReturn(USER_ID);
      when(authenticatedUser.getUsername()).thenReturn("seeker");
      when(authenticatedUser.getRoles()).thenReturn(Set.of(UserRole.USER.getValue()));

      when(matrixSynapseService.loginUserViaAdmin(MATRIX_USER_ID))
          .thenReturn("matrix-access-token");
      when(matrixSynapseService.sendMessage(
              eq(MATRIX_ROOM_ID), eq("hello"), eq("matrix-access-token")))
          .thenReturn(Map.of("event_id", "evt-1"));
    }

    @Test
    @DisplayName(
        "Given registered user session, When POST /matrix/sessions/{id}/messages, Then HTTP 200 via admin token")
    void sendMessage_ShouldUseAdminImpersonationNotStoredPassword() throws Exception {
      mockMvc
          .perform(
              post("/matrix/sessions/{sessionId}/messages", SESSION_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"message\":\"hello\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true));

      verify(matrixSynapseService).loginUserViaAdmin(MATRIX_USER_ID);
      verify(matrixSynapseService, never()).loginUser(anyString(), eq(REGISTRATION_PASSWORD));
      verify(matrixSynapseService, never()).loginUser(anyString(), eq(PASSWORD));
    }
  }

  @Nested
  @DisplayName("Smoke 3 — PlainCredentialsHolder cleared after registration request")
  @ExtendWith(MockitoExtension.class)
  class PlainCredentialsHolderClearedAfterRequest {

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

    @InjectMocks private CreateUserFacade createUserFacade;

    @BeforeEach
    void setUp() throws Exception {
      PlainCredentialsHolder.set("plainuser", REGISTRATION_PASSWORD);

      when(consultingTypeManager.getConsultingTypeSettings(any()))
          .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
      when(identityClient.createKeycloakUser(any()))
          .thenReturn(KEYCLOAK_CREATE_USER_RESPONSE_DTO_WITH_USER_ID);
      doNothing().when(identityClient).updatePassword(anyString(), anyString());
      doNothing().when(identityClient).updateRole(anyString(), any(UserRole.class));
      doNothing().when(userVerifier).checkIfUsernameIsAvailable(any());
      doNothing().when(userVerifier).checkIfAllRequiredAttributesAreCorrectlyFilled(any());
      doNothing().when(agencyVerifier).checkIfConsultingTypeMatchesToAgency(any());

      when(userService.createUser(
              anyString(), any(), anyString(), anyString(), any(Boolean.class), any()))
          .thenReturn(User.builder().userId(USER_ID).username("user").build());
      when(userService.saveUser(any())).thenAnswer(invocation -> invocation.getArgument(0));

      when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
          .thenReturn(ResponseEntity.ok(new MatrixCreateUserResponseDTO()));

      when(createNewSessionFacade.initializeNewSession(
              any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
          .thenReturn(
              new NewRegistrationResponseDto().sessionId(SESSION_ID).status(HttpStatus.CREATED));

      when(tenantService.getRestrictedTenantData(org.mockito.Mockito.anyLong()))
          .thenReturn(new RestrictedTenantDTO());
      when(agencyService.getAgencyWithoutCaching(any())).thenReturn(new AgencyDTO());
      doNothing().when(statisticsService).fireEvent(any());
    }

    @AfterEach
    void tearDown() {
      PlainCredentialsHolder.clear();
    }

    @Test
    @DisplayName(
        "Given completed registration, When same thread continues, Then PlainCredentialsHolder.get() is null")
    void registration_ShouldClearPlainCredentialsHolderAfterRequest() {
      createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_KREUZBUND);

      assertThat(PlainCredentialsHolder.get(), nullValue());
    }

    @Test
    @DisplayName(
        "Given holder cleared after registration, When next simulated request reuses thread, Then no password leak")
    void nextRequestOnSameThread_ShouldNotSeePreviousRegistrationPassword() {
      createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_KREUZBUND);

      PlainCredentialsHolder.PlainCredentials leaked = PlainCredentialsHolder.get();
      assertThat(leaked, nullValue());

      PlainCredentialsHolder.set("other-user", null);
      assertThat(PlainCredentialsHolder.get().getPassword(), nullValue());
      assertThat(PlainCredentialsHolder.get().getPassword(), not(REGISTRATION_PASSWORD));
    }
  }
}
