package de.caritas.cob.userservice.api.workflow.deactivate.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.TaskIdentityGrant;
import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAnonymousEnquiryDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration;
import de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.testConfig.ApiControllerTestConfig;
import de.caritas.cob.userservice.api.testConfig.KeycloakTestConfig;
import de.caritas.cob.userservice.api.testConfig.TestAgencyControllerApi;
import de.caritas.cob.userservice.api.testHelper.AccountInactivityPolicyHttpFixture;
import de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures;
import de.caritas.cob.userservice.api.testHelper.ChatRecoveryPolicyFixtures;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;

@SpringBootTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({KeycloakTestConfig.class, ApiControllerTestConfig.class})
class DeactivateAnonymousUserSchedulerIT extends AccountInactivityPolicyHttpFixture {

  private static final String TASK_NAME = "anonymous-user-deactivation";

  @Autowired private DeactivateAnonymousUserScheduler deactivateAnonymousUserScheduler;

  @Autowired private CreateAnonymousEnquiryFacade createAnonymousEnquiryFacade;

  @Autowired private SessionRepository sessionRepository;

  @Autowired private ScheduledTaskClaimRepository claimRepository;

  @Autowired private UserService userService;

  @Autowired
  private de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory
      ownerFactory;

  private de.caritas.cob.userservice.api.testHelper.DpaOwnerHttpFixtures dpaOwner;

  @Autowired private ActionsRegistry actionsRegistry;

  @MockitoBean AgencyServiceApiControllerFactory agencyServiceApiControllerFactory;

  @MockitoBean MatrixSynapseService matrixSynapseService;

  @MockitoBean TenantService tenantService;

  @Value("${user.anonymous.deactivateworkflow.periodMinutes}")
  private long deactivatePeriodInMinutes;

  private Session currentSession;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private Environment environment;
  @Autowired private TaskIdentityConfiguration taskIdentities;
  @MockitoBean private TaskIdentityGrant taskGrants;
  @MockitoBean private org.springframework.security.oauth2.jwt.JwtDecoder taskJwtDecoder;

  @MockitoBean
  @Qualifier("keycloakRestTemplate")
  private RestTemplate keycloakRestTemplate;

  @MockitoBean
  @Qualifier("restTemplate")
  private RestTemplate restTemplate;

  private BoundedIdentityHttpFixtures.Provider identityProvider;

  @BeforeEach
  public void setup() throws MatrixCreateUserException {
    identityProvider =
        BoundedIdentityHttpFixtures.givenProvider(
            keycloakRestTemplate, taskGrants, taskIdentities, environment, objectMapper, id -> {});
    BoundedIdentityHttpFixtures.givenTaskGrants(restTemplate, taskJwtDecoder, taskIdentities);
    var login = new KeycloakLoginResponseDTO();
    login.setAccessToken("synthetic-human-token");
    login.setRefreshToken("synthetic-human-refresh");
    org.mockito.Mockito.when(
            restTemplate.postForEntity(
                org.mockito.ArgumentMatchers.endsWith("/token"),
                org.mockito.ArgumentMatchers.any(HttpEntity.class),
                org.mockito.ArgumentMatchers.eq(KeycloakLoginResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(login));
    dpaOwner =
        de.caritas.cob.userservice.api.testHelper.DpaOwnerHttpFixtures.permitWithTenantLookup(
            ownerFactory, 1L);
    deleteSchedulerClaim();
    when(tenantService.getSingleTenancyTenantDataFresh())
        .thenReturn(ChatRecoveryPolicyFixtures.tenant());
    var matrixUserResponse = new MatrixCreateUserResponseDTO();
    matrixUserResponse.setUserId("@anonymous:matrix.test");
    when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.ok(matrixUserResponse));
    when(matrixSynapseService.deactivateUser(anyString())).thenReturn(true);
    when(agencyServiceApiControllerFactory.createControllerApi())
        .thenReturn(
            new TestAgencyControllerApi(
                new de.caritas.cob.userservice.agencyserivce.generated.ApiClient()));
    var createAnonymousEnquiryDTO = new CreateAnonymousEnquiryDTO().consultingType(12);
    var responseDTO =
        createAnonymousEnquiryFacade.createAnonymousEnquiry(createAnonymousEnquiryDTO);

    var sessionOptional = sessionRepository.findById(responseDTO.getSessionId());
    currentSession = sessionOptional.get();
  }

  @AfterEach
  public void cleanDatabase() {
    dpaOwner.close();
    this.sessionRepository.deleteAll();
    deleteSchedulerClaim();
  }

  private void deleteSchedulerClaim() {
    claimRepository.findById(TASK_NAME).ifPresent(claimRepository::delete);
  }

  @Test
  void performDeactivationWorkflow_Should_notDeleteUser_When_SessionIsNotInProgress() {
    var currentStatus = currentSession.getStatus();
    deactivateAnonymousUserScheduler.performDeactivationWorkflow();

    assertSessionAndUserArePresent(currentSession.getId());
    var sessionFromDb = sessionRepository.findById(currentSession.getId());
    assertEquals(currentSession, sessionFromDb.get());
  }

  @Test
  void staleRegisteredLiveChatSessionRemainsUnchangedAndEnabled() {
    var registered =
        registeredSessionWithUpdateDate(
            LocalDateTime.now().minusMinutes(deactivatePeriodInMinutes + 1));
    var before = sessionRepository.findById(registered.getId()).orElseThrow();
    var priorUpdateDate = before.getUpdateDate();
    String userId = before.getUser().getUserId();
    assertTrue(identityProvider.projections().get(userId).enabled());

    deactivateAnonymousUserScheduler.performDeactivationWorkflow();

    var retained = sessionRepository.findById(registered.getId()).orElseThrow();
    assertEquals(Session.RegistrationType.REGISTERED, retained.getRegistrationType());
    assertEquals("00000", retained.getPostcode());
    assertEquals(SessionStatus.IN_PROGRESS, retained.getStatus());
    assertEquals(priorUpdateDate, retained.getUpdateDate());
    assertTrue(userService.getUser(userId).isPresent());
    assertTrue(identityProvider.projections().get(userId).enabled());
    org.assertj.core.api.Assertions.assertThat(identityProvider.commands())
        .noneMatch(
            command ->
                command.operation().equals("account.deactivate")
                    && command.target().equals(userId));
  }

  @Test
  void registeredConversationProtectsTheSameAccountWhenItsAnonymousConversationBecomesStale() {
    prepareCurrentSessionForDeactivation();
    var registered = registeredSessionWithUpdateDate(LocalDateTime.now());
    var before = sessionRepository.findById(registered.getId()).orElseThrow();
    var priorUpdateDate = before.getUpdateDate();
    String userId = currentSession.getUser().getUserId();
    assertEquals(userId, before.getUser().getUserId());
    assertTrue(identityProvider.projections().get(userId).enabled());

    deactivateAnonymousUserScheduler.performDeactivationWorkflow();

    var retained = sessionRepository.findById(registered.getId()).orElseThrow();
    assertEquals(Session.RegistrationType.REGISTERED, retained.getRegistrationType());
    assertEquals(SessionStatus.IN_PROGRESS, retained.getStatus());
    assertEquals(priorUpdateDate, retained.getUpdateDate());
    assertEquals(userId, retained.getUser().getUserId());
    assertEquals(
        SessionStatus.DONE,
        sessionRepository.findById(currentSession.getId()).orElseThrow().getStatus());
    assertTrue(userService.getUser(userId).isPresent());
    assertTrue(identityProvider.projections().get(userId).enabled());
    org.assertj.core.api.Assertions.assertThat(identityProvider.commands())
        .noneMatch(
            command ->
                command.operation().equals("account.deactivate")
                    && command.target().equals(userId));
  }

  private Session registeredSessionWithUpdateDate(LocalDateTime updateDate) {
    return sessionRepository.save(
        Session.builder()
            .user(currentSession.getUser())
            .consultingTypeId(currentSession.getConsultingTypeId())
            .agencyId(currentSession.getAgencyId())
            .registrationType(Session.RegistrationType.REGISTERED)
            .conversationType(currentSession.getConversationType())
            .postcode("00000")
            .languageCode(currentSession.getLanguageCode())
            .status(SessionStatus.IN_PROGRESS)
            .isConsultantDirectlySet(false)
            .createDate(LocalDateTime.now())
            .updateDate(updateDate)
            .tenantId(currentSession.getTenantId())
            .build());
  }

  private void assertSessionAndUserArePresent(long sessionId) {
    var sessionOptional = sessionRepository.findById(sessionId);
    assertTrue(sessionOptional.isPresent());

    var userOptional = userService.getUser(sessionOptional.get().getUser().getUserId());
    assertTrue(userOptional.isPresent());
  }

  @Test
  void performDeletionWorkflow_Should_putSessionToDone_When_SessionAreDoneWithinDeletionPeriod() {
    currentSession.setStatus(SessionStatus.IN_PROGRESS);
    var oneMinuteBeforeDeletionPeriodIsOver =
        LocalDateTime.now().minusMinutes(deactivatePeriodInMinutes).plusMinutes(1L);
    currentSession.setUpdateDate(oneMinuteBeforeDeletionPeriodIsOver);
    sessionRepository.save(currentSession);

    deactivateAnonymousUserScheduler.performDeactivationWorkflow();

    assertSessionAndUserArePresent(currentSession.getId());
    var sessionFromDb = sessionRepository.findById(currentSession.getId());
    assertEquals(currentSession, sessionFromDb.get());
  }

  @Test
  void
      performDeactivationWorkflow_Should_deleteUser_When_UserSessionIsDoneAndOutsideOfDeletionPeriod() {
    prepareCurrentSessionForDeactivation();

    deactivateAnonymousUserScheduler.performDeactivationWorkflow();

    assertSessionAndUserArePresent(currentSession.getId());
    var sessionFromDb = sessionRepository.findById(currentSession.getId());
    assertEquals(SessionStatus.DONE, sessionFromDb.get().getStatus());
  }

  private void prepareCurrentSessionForDeactivation() {
    currentSession.setStatus(SessionStatus.IN_PROGRESS);
    var timeToDeactivation = LocalDateTime.now().minusMinutes(deactivatePeriodInMinutes);
    currentSession.setUpdateDate(timeToDeactivation);
    sessionRepository.save(currentSession);
  }
}
