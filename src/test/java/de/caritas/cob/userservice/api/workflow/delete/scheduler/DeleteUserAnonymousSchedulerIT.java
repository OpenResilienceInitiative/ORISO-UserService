package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_AIDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAnonymousEnquiryDTO;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.testConfig.ApiControllerTestConfig;
import de.caritas.cob.userservice.api.testConfig.ConsultingTypeManagerTestConfig;
import de.caritas.cob.userservice.api.testConfig.KeycloakTestConfig;
import de.caritas.cob.userservice.api.testConfig.TestAgencyControllerApi;
import de.caritas.cob.userservice.api.workflow.delete.service.WorkflowErrorMailService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectRetrievalFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({
  KeycloakTestConfig.class,
  ApiControllerTestConfig.class,
  ConsultingTypeManagerTestConfig.class
})
class DeleteUserAnonymousSchedulerIT {

  @Autowired private DeleteUserAnonymousScheduler deleteUserAnonymousScheduler;

  @Autowired private CreateAnonymousEnquiryFacade createAnonymousEnquiryFacade;

  @Autowired private SessionRepository sessionRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private UserService userService;

  @Value("${user.anonymous.deleteworkflow.periodMinutes}")
  private long deletionPeriodInMinutes;

  @MockitoBean AgencyServiceApiControllerFactory agencyServiceApiControllerFactory;

  @MockitoBean MatrixSynapseService matrixSynapseService;

  @MockitoBean WorkflowErrorMailService workflowErrorMailService;

  @MockitoSpyBean UserRepository spiedUserRepository;

  private Session currentSession;

  @BeforeEach
  public void setup() throws MatrixCreateUserException {
    var matrixUserResponse = new MatrixCreateUserResponseDTO();
    matrixUserResponse.setUserId("@anonymous:matrix.test");
    when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.ok(matrixUserResponse));
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

  @AfterEach
  public void cleanDatabase() {
    this.sessionRepository.deleteAll();
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

  private void prepareCurrentSessionForDeletion() {
    currentSession.setStatus(SessionStatus.DONE);
    var oneMinuteBeforeDeletionPeriodIsOver =
        LocalDateTime.now().minusMinutes(deletionPeriodInMinutes);
    currentSession.setUpdateDate(oneMinuteBeforeDeletionPeriodIsOver);
    sessionRepository.save(currentSession);
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
   * Regression guard for the PreDev failure mode behind #745: the workflow error originates in the
   * <em>database</em> delete, not in an external call.
   *
   * <p>On PreDev, {@code DeleteDatabaseAskerAction} fails with {@code
   * ObjectRetrievalFailureException} while merging the detached user, which makes the workflow
   * error list non-empty and drives the error-notification path. Before the notification became
   * best-effort, that path rolled back the session deletion that had already succeeded, while the
   * irreversible Matrix and Keycloak deletions had already happened — so the next hourly run
   * repeated them.
   *
   * <p>The session deletion must therefore stay committed even when the user delete fails and the
   * notification fails on top of it.
   */
  @Test
  void performDeletionWorkflow_Should_commitSessionDeletion_When_userDeleteFailsInDatabase() {
    prepareCurrentSessionForDeletion();
    var sessionId = currentSession.getId();
    var userId = currentSession.getUser().getUserId();
    doThrow(new ObjectRetrievalFailureException("user merge failed during deletion", userId))
        .when(spiedUserRepository)
        .delete(any());
    doThrow(new IllegalStateException("error notification failed"))
        .when(workflowErrorMailService)
        .buildAndSendErrorMail(anyList());

    assertDoesNotThrow(deleteUserAnonymousScheduler::performDeletionWorkflow);

    assertFalse(
        sessionRepository.findById(sessionId).isPresent(),
        "completed session deletion must stay committed when the user delete fails");
    assertTrue(
        userService.getUser(userId).isPresent(),
        "a failed user delete must leave the row intact rather than lose it silently");
    verify(workflowErrorMailService).buildAndSendErrorMail(anyList());
  }
}
