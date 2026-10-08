package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_AIDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.testConfig.ApiControllerTestConfig;
import de.caritas.cob.userservice.api.testConfig.ConsultingTypeManagerTestConfig;
import de.caritas.cob.userservice.api.testConfig.KeycloakTestConfig;
import de.caritas.cob.userservice.api.testConfig.TestAgencyControllerApi;
import de.caritas.cob.userservice.api.testHelper.AccountInactivityPolicyHttpFixture;
import de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures;
import de.caritas.cob.userservice.api.testHelper.ChatRecoveryPolicyFixtures;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.service.WorkflowErrorMailService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestTemplate;

@SpringBootTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({
  KeycloakTestConfig.class,
  ApiControllerTestConfig.class,
  ConsultingTypeManagerTestConfig.class
})
class DeleteUserAnonymousSchedulerIT extends AccountInactivityPolicyHttpFixture {

  private static final String TASK_NAME = "anonymous-user-deletion";

  @Autowired private DeleteUserAnonymousScheduler deleteUserAnonymousScheduler;

  @Autowired private CreateAnonymousEnquiryFacade createAnonymousEnquiryFacade;

  @Autowired
  private de.caritas.cob.userservice.api.conversation.service.user.anonymous
          .AnonymousUsernameRegistry
      anonymousUsernameRegistry;

  private String originalAnonymousUsernamePrefix;

  @Autowired private SessionRepository sessionRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private ScheduledTaskClaimRepository claimRepository;

  @Autowired private UserService userService;

  @Autowired
  private de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory
      ownerFactory;

  private de.caritas.cob.userservice.api.testHelper.DpaOwnerHttpFixtures dpaOwner;

  @Value("${user.anonymous.deleteworkflow.periodMinutes}")
  private long deletionPeriodInMinutes;

  @MockitoBean AgencyServiceApiControllerFactory agencyServiceApiControllerFactory;

  @MockitoBean MatrixSynapseService matrixSynapseService;

  @MockitoBean TenantService tenantService;

  @MockitoBean WorkflowErrorMailService workflowErrorMailService;

  @MockitoSpyBean UserAgencyRepository userAgencyRepository;

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
    originalAnonymousUsernamePrefix =
        (String)
            org.springframework.test.util.ReflectionTestUtils.getField(
                anonymousUsernameRegistry, "usernamePrefix");
    // Use a per-test namespace while preserving the real allocation and occupancy checks.
    org.springframework.test.util.ReflectionTestUtils.setField(
        anonymousUsernameRegistry,
        "usernamePrefix",
        originalAnonymousUsernamePrefix
            + java.util.UUID.randomUUID().toString().replace("-", "")
            + "-");
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
    de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures.givenOwnedMatrixUser(
        matrixSynapseService, "@anonymous:matrix.test");
    when(matrixSynapseService.deactivateUser(anyString())).thenReturn(true);
    when(agencyServiceApiControllerFactory.createControllerApi())
        .thenReturn(
            new TestAgencyControllerApi(
                new de.caritas.cob.userservice.agencyserivce.generated.ApiClient()));
    var createAnonymousEnquiryDTO =
        new CreateAnonymousEnquiryDTO().consultingType(CONSULTING_TYPE_ID_AIDS);
    var responseDTO =
        createAnonymousEnquiryFacade.createAnonymousEnquiry(createAnonymousEnquiryDTO);

    var sessionOptional = sessionRepository.findById(responseDTO.getSessionId());
    currentSession = sessionOptional.get();
  }

  private Session createAnotherAnonymousSession() {
    var responseDTO =
        createAnonymousEnquiryFacade.createAnonymousEnquiry(
            new CreateAnonymousEnquiryDTO().consultingType(CONSULTING_TYPE_ID_AIDS));

    return sessionRepository.findById(responseDTO.getSessionId()).orElseThrow();
  }

  @AfterEach
  public void cleanDatabase() {
    if (originalAnonymousUsernamePrefix != null)
      org.springframework.test.util.ReflectionTestUtils.setField(
          anonymousUsernameRegistry, "usernamePrefix", originalAnonymousUsernamePrefix);
    dpaOwner.close();
    this.sessionRepository.deleteAll();
    deleteSchedulerClaim();
  }

  private void deleteSchedulerClaim() {
    claimRepository.findById(TASK_NAME).ifPresent(claimRepository::delete);
  }

  @Test
  void performDeletionWorkflow_Should_notDeleteUser_When_SessionIsNotDone() {
    deleteUserAnonymousScheduler.performDeletionWorkflow();

    assertSessionAndUserArePresent(currentSession.getId());
  }

  private void assertSessionAndUserArePresent(long sessionId) {
    var sessionOptional = sessionRepository.findById(sessionId);
    assertTrue(sessionOptional.isPresent());

    var userOptional = userService.getUser(sessionOptional.get().getUser().getUserId());
    assertTrue(userOptional.isPresent());
  }

  @Test
  void performDeletionWorkflow_Should_notDeleteUser_When_SessionAreDoneWithinDeletionPeriod() {
    currentSession.setStatus(SessionStatus.DONE);
    var oneMinuteBeforeDeletionPeriodIsOver =
        LocalDateTime.now().minusMinutes(deletionPeriodInMinutes).plusMinutes(1L);
    currentSession.setUpdateDate(oneMinuteBeforeDeletionPeriodIsOver);
    sessionRepository.save(currentSession);

    deleteUserAnonymousScheduler.performDeletionWorkflow();

    assertSessionAndUserArePresent(currentSession.getId());
  }

  @Test
  void
      performDeletionWorkflow_Should_deleteUser_When_UserSessionIsDoneAndOutsideOfDeletionPeriod() {
    prepareCurrentSessionForDeletion();

    deleteUserAnonymousScheduler.performDeletionWorkflow();

    assertSessionAndUserDoNotExistInDatabase(
        currentSession.getId(), currentSession.getUser().getUserId());
  }

  /**
   * The deletion transaction ends before notification starts, so a notification failure cannot
   * reach it. #746 additionally made that failure non-fatal for the scheduler run, so it is logged
   * rather than propagated; the deletion stays committed either way.
   */
  @Test
  void performDeletionWorkflow_Should_commitDeletionBeforeErrorNotificationFails() {
    prepareCurrentSessionForDeletion();
    when(matrixSynapseService.deactivateUser(anyString())).thenReturn(false);
    doThrow(new IllegalStateException("tenant unavailable"))
        .when(workflowErrorMailService)
        .buildAndSendErrorMail(anyList());

    assertDoesNotThrow(deleteUserAnonymousScheduler::performDeletionWorkflow);

    assertSessionAndUserDoNotExistInDatabase(
        currentSession.getId(), currentSession.getUser().getUserId());
  }

  @Test
  void completedRegisteredConversationProtectsTheSameAccountFromAnonymousDeletion() {
    prepareCurrentSessionForDeletion();
    var oldDate = LocalDateTime.now().minusMinutes(deletionPeriodInMinutes + 1);
    var registered =
        sessionRepository.save(
            Session.builder()
                .user(currentSession.getUser())
                .consultingTypeId(currentSession.getConsultingTypeId())
                .agencyId(currentSession.getAgencyId())
                .registrationType(Session.RegistrationType.REGISTERED)
                .conversationType(currentSession.getConversationType())
                .postcode("00000")
                .languageCode(currentSession.getLanguageCode())
                .status(SessionStatus.DONE)
                .isConsultantDirectlySet(false)
                .createDate(oldDate)
                .updateDate(oldDate)
                .tenantId(currentSession.getTenantId())
                .build());
    var before = sessionRepository.findById(registered.getId()).orElseThrow();
    var priorUpdateDate = before.getUpdateDate();
    String userId = currentSession.getUser().getUserId();
    org.junit.jupiter.api.Assertions.assertEquals(userId, before.getUser().getUserId());
    assertTrue(identityProvider.projections().get(userId).enabled());

    deleteUserAnonymousScheduler.performDeletionWorkflow();

    var retainedAnonymous = sessionRepository.findById(currentSession.getId()).orElseThrow();
    var retainedRegistered = sessionRepository.findById(registered.getId()).orElseThrow();
    org.junit.jupiter.api.Assertions.assertEquals(
        Session.RegistrationType.ANONYMOUS, retainedAnonymous.getRegistrationType());
    org.junit.jupiter.api.Assertions.assertEquals(
        SessionStatus.DONE, retainedAnonymous.getStatus());
    org.junit.jupiter.api.Assertions.assertEquals(
        Session.RegistrationType.REGISTERED, retainedRegistered.getRegistrationType());
    org.junit.jupiter.api.Assertions.assertEquals(
        SessionStatus.DONE, retainedRegistered.getStatus());
    org.junit.jupiter.api.Assertions.assertEquals(
        priorUpdateDate, retainedRegistered.getUpdateDate());
    org.junit.jupiter.api.Assertions.assertEquals(userId, retainedRegistered.getUser().getUserId());
    assertTrue(userService.getUser(userId).isPresent());
    assertTrue(identityProvider.projections().get(userId).enabled());
    org.assertj.core.api.Assertions.assertThat(identityProvider.commands())
        .noneMatch(
            command ->
                command.operation().equals("account.delete") && command.target().equals(userId));
  }

  private void prepareCurrentSessionForDeletion() {
    prepareForDeletion(currentSession);
  }

  private void prepareForDeletion(Session session) {
    session.setStatus(SessionStatus.DONE);
    var oneMinuteBeforeDeletionPeriodIsOver =
        LocalDateTime.now().minusMinutes(deletionPeriodInMinutes);
    session.setUpdateDate(oneMinuteBeforeDeletionPeriodIsOver);
    sessionRepository.save(session);
  }

  private void assertSessionAndUserDoNotExistInDatabase(Long sessionId, String userId) {
    var sessionOptional = sessionRepository.findById(sessionId);
    assertFalse(sessionOptional.isPresent());

    var userOptional = userService.getUser(userId);
    assertFalse(userOptional.isPresent());
  }

  @Test
  void performDeletionWorkflow_Should_deleteUser_When_UserSessionsAreNull() {
    currentSession.getUser().setSessions(null);
    userRepository.save(currentSession.getUser());
    prepareCurrentSessionForDeletion();

    deleteUserAnonymousScheduler.performDeletionWorkflow();

    assertSessionAndUserDoNotExistInDatabase(
        currentSession.getId(), currentSession.getUser().getUserId());
  }

  @Test
  void performDeletionWorkflow_Should_commitDeletion_When_errorNotificationDependencyFails() {
    prepareCurrentSessionForDeletion();
    when(matrixSynapseService.deactivateUser(anyString()))
        .thenThrow(new IllegalStateException("matrix deletion failed"));
    doThrow(new IllegalStateException("error notification failed"))
        .when(workflowErrorMailService)
        .buildAndSendErrorMail(anyList());

    assertDoesNotThrow(deleteUserAnonymousScheduler::performDeletionWorkflow);

    assertSessionAndUserDoNotExistInDatabase(
        currentSession.getId(), currentSession.getUser().getUserId());
  }

  /**
   * Closes the PreDev failure mode behind #745: one user whose database delete fails must no longer
   * take the rest of the batch down with it.
   *
   * <p>The failure is provoked, not stubbed. With the preceding session deletes flushed and the
   * user detached, {@code DeleteDatabaseAskerAction} takes Hibernate's merge path and fails while
   * re-resolving the sessions removed moments earlier in the same run — the stack seen live.
   * Stubbing the repository would reproduce the shape but not the consequence, because only a
   * genuine failure marks the persistence context rollback-only.
   *
   * <p>Before the per-user transaction boundary, that rollback-only context discarded the whole
   * batch even though the notification exception was caught, so the next hourly run repeated every
   * user's irreversible Matrix and Keycloak calls. The poisoned user itself still cannot be
   * retained — its own transaction is doomed — but it is now the only one lost, and it is reported.
   */
  @Test
  void performDeletionWorkflow_Should_isolateFailedUser_When_databaseOriginFailurePoisonsIt() {
    var healthySession = createAnotherAnonymousSession();
    prepareForDeletion(currentSession);
    prepareForDeletion(healthySession);
    var poisonedUserId = currentSession.getUser().getUserId();
    var healthyUserId = healthySession.getUser().getUserId();
    poisonDatabaseDeleteOf(currentSession.getUser());
    doThrow(new IllegalStateException("error notification failed"))
        .when(workflowErrorMailService)
        .buildAndSendErrorMail(anyList());

    assertDoesNotThrow(deleteUserAnonymousScheduler::performDeletionWorkflow);

    assertDatabaseUserDeleteFailed();
    assertFalse(
        sessionRepository.findById(healthySession.getId()).isPresent(),
        "an unaffected user must still be deleted when another user in the batch fails");
    assertFalse(
        userService.getUser(healthyUserId).isPresent(),
        "an unaffected user must still be deleted when another user in the batch fails");
    assertTrue(
        sessionRepository.findById(currentSession.getId()).isPresent(),
        "the poisoned user's own transaction is doomed and stays uncommitted");
    assertTrue(userService.getUser(poisonedUserId).isPresent());
  }

  /**
   * Makes one user's database delete fail the way the database itself would.
   *
   * <p>A {@code user_agency} row is left behind by suppressing only that user's agency cleanup, so
   * the following user delete violates the restricting foreign key. Hibernate raises the error at
   * flush and marks the persistence context rollback-only, which is what a stubbed exception cannot
   * do and what makes this user's commit fail for real.
   */
  private void poisonDatabaseDeleteOf(User user) {
    userAgencyRepository.save(new UserAgency(user, 1L));
    doReturn(List.of())
        .when(userAgencyRepository)
        .findByUser(argThat(candidate -> user.getUserId().equals(candidate.getUserId())));
  }

  /**
   * Guards the reproduction itself: the workflow error handed to the notification step has to come
   * from the database delete of the user, otherwise this test would pass without exercising the
   * failure mode it exists for.
   */
  @SuppressWarnings("unchecked")
  private void assertDatabaseUserDeleteFailed() {
    var captor = ArgumentCaptor.forClass(List.class);
    verify(workflowErrorMailService).buildAndSendErrorMail(captor.capture());

    List<DeletionWorkflowError> workflowErrors = captor.getValue();
    assertTrue(
        workflowErrors.stream()
            .anyMatch(
                error ->
                    DeletionSourceType.ASKER.equals(error.getDeletionSourceType())
                        && DeletionTargetType.DATABASE.equals(error.getDeletionTargetType())
                        && "Unable to delete user".equals(error.getReason())),
        "expected a database user-delete workflow error, got: " + workflowErrors);
  }
}
