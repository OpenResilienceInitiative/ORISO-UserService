package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

/** Executes the real registry, facade, deletion service and every consultant action. */
class TrackedConsultantCompensationPrivacyTest {
  static final List<Class<?>> ACTIONS =
      List.of(
          DeleteKeycloakConsultantAction.class,
          DeleteMatrixConsultantAction.class,
          DeleteDatabaseConsultantAgencyAction.class,
          DeleteChatAction.class,
          DeleteAppointmentServiceConsultantAction.class,
          DeleteCaseHandoverRequestsForConsultantAction.class,
          DeleteConsultantDraftMessagesAction.class,
          DeleteConsultantEventNotificationsAction.class,
          DeleteConsultantMessageEmailDeliveriesAction.class,
          DeleteDatabaseConsultantAction.class);
  static final String PRIVATE = "CompensationPasswordEmailUsernameCanary";

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
  @SuppressWarnings({"rawtypes", "unchecked"})
  void actualActionChainNeverLogsNestedFailuresOrPrivateDto(int failingAction) throws Exception {
    var consultant =
        Consultant.builder()
            .id("synthetic-native")
            .firstName("Synthetic")
            .lastName("Person")
            .username(PRIVATE)
            .email(PRIVATE + "@example.invalid")
            .matrixUserId("@" + PRIVATE + ":matrix.invalid")
            .build();
    Map<String, ActionCommand> actions = new LinkedHashMap<>();
    for (int index = 0; index < ACTIONS.size(); index++) {
      var type = ACTIONS.get(index);
      boolean inject = index == failingAction;
      var constructor = type.getConstructors()[0];
      Object[] dependencies =
          Arrays.stream(constructor.getParameterTypes())
              .map(
                  dependency ->
                      mock(
                          dependency,
                          call -> {
                            if (dependency == ChatRepository.class
                                && call.getMethod().getName().equals("findByChatOwner")) {
                              var chat = new Chat();
                              chat.setMatrixRoomId("!" + PRIVATE + ":matrix.invalid");
                              return List.of(chat);
                            }
                            if (inject) throw new IllegalStateException(PRIVATE);
                            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
                          }))
              .toArray();
      actions.put(type.getSimpleName(), (ActionCommand) constructor.newInstance(dependencies));
    }
    var application = mock(ApplicationContext.class);
    when(application.getBeansOfType(ActionCommand.class)).thenReturn(actions);
    var service =
        new DeleteUserAccountService(
            mock(UserRepository.class),
            mock(ConsultantRepository.class),
            new ActionsRegistry(application),
            mock(WorkflowErrorMailService.class),
            mock(DeletionLifecycleService.class),
            mock(
                de.caritas.cob.userservice.api.workflow.delete.service.UserHardDeleteClaimService
                    .class));
    var facade =
        new RollbackFacade(
            mock(IdentityAccountRemover.class),
            mock(de.caritas.cob.userservice.api.service.UserAgencyService.class),
            mock(de.caritas.cob.userservice.api.service.session.SessionService.class),
            mock(de.caritas.cob.userservice.api.service.user.UserService.class),
            service);
    var root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var capture = new ListAppender<ILoggingEvent>();
    capture.start();
    root.addAppender(capture);
    try {
      try {
        facade.rollbackConsultantAccount(consultant, true);
      } catch (RuntimeException failure) {
        assertThat(failure.getMessage()).isEqualTo(PRIVATE);
      }
      var logs =
          capture.list.stream()
              .map(
                  event ->
                      event.getFormattedMessage()
                          + (event.getThrowableProxy() == null
                              ? ""
                              : ThrowableProxyUtil.asString(event.getThrowableProxy())))
              .collect(java.util.stream.Collectors.joining("\n"));
      assertThat(logs).contains("Tracked consultant").doesNotContain(PRIVATE);
      assertThat(consultant.getDeleteDate()).isNotNull();
    } finally {
      root.detachAppender(capture);
      capture.stop();
    }
  }
}
